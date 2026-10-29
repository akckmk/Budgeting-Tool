package com.budgetingtool.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.budgetingtool.app.alerts.BudgetAlerts
import com.budgetingtool.app.data.BudgetRepository
import com.budgetingtool.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import kotlin.coroutines.resume

data class PeerInfo(val deviceId: String, val name: String, val address: String, val lastActivity: Long)

data class SyncUiState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val householdCode: String? = null,
    val localAddress: String? = null,
    val port: Int = 0,
    val peers: List<PeerInfo> = emptyList(),
    val lastSyncAt: Long = 0,
    val log: List<String> = emptyList(),
)

/**
 * Phone-to-phone sync for a household, with no cloud in between.
 *
 * Each phone runs a small encrypted TCP server and advertises it on the local network (mDNS / NSD).
 * When the spouse's phone is found, they swap everything changed since they last talked, then keep
 * the connection open and push each new purchase within a second: live sync while both phones are on
 * the same Wi-Fi (or one phone's hotspot). When they're apart, each phone keeps working on its own
 * copy and they catch up the next time they meet. An encrypted file can also be exchanged by hand.
 */
class SyncManager(
    context: Context,
    private val repo: BudgetRepository,
    private val prefs: Prefs,
    private val alerts: BudgetAlerts,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private class Connection(
        val deviceId: String,
        val name: String,
        val address: InetAddress,
        val channel: FramedChannel,
        @Volatile var lastActivity: Long = System.currentTimeMillis(),
    ) {
        val sendMutex = Mutex()
    }

    private data class Endpoint(val address: InetSocketAddress, val peerShortId: String?, val manual: Boolean)

    @Volatile private var crypto: SyncCrypto? = null
    private var job: Job? = null
    /** Scope of the running sharing session; cancelled by [stop]. */
    @Volatile private var session: CoroutineScope? = null
    private var server: ServerSocket? = null
    private val connections = ConcurrentHashMap<String, Connection>()
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val shortId: String get() = prefs.deviceId.replace("-", "").take(8)

    // ---------------- household lifecycle ----------------

    fun startIfConfigured() {
        val code = prefs.householdCode
        _state.update { it.copy(configured = code != null, householdCode = code) }
        if (code != null) start(code)
    }

    fun createHousehold(): String {
        val code = SyncCrypto.generateCode()
        setCode(code)
        return code
    }

    /** Called by [PairingManager] once the other phone has approved this one. */
    fun adoptHousehold(householdKey: String): Boolean {
        if (!SyncCrypto.isValidHouseholdKey(householdKey)) return false
        setCode(SyncCrypto.format(householdKey))
        return true
    }

    private fun setCode(code: String) {
        prefs.householdCode = code
        prefs.clearWatermarks()
        _state.update { SyncUiState(configured = true, householdCode = code) }
        start(code)
    }

    fun leaveHousehold() {
        stop()
        prefs.householdCode = null
        prefs.clearWatermarks()
        _state.value = SyncUiState()
    }

    @Synchronized
    private fun start(code: String) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            session = this
            val c = SyncCrypto(code) // key derivation takes a moment; keep it off the main thread
            crypto = c
            val ss = try {
                ServerSocket(DEFAULT_PORT)
            } catch (e: Exception) {
                ServerSocket(0)
            }
            server = ss
            _state.update { it.copy(running = true, port = ss.localPort, localAddress = localIpv4()) }
            log("Sharing is on. Listening on ${localIpv4() ?: "this device"}:${ss.localPort}")
            val found = startNsd(c.householdTag, ss.localPort)

            launch { resolveLoop(found) }
            launch { acceptLoop(ss) }
            launch { reconnectLoop() }
            launch { liveSender() }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        session = null
        runCatching { server?.close() }
        server = null
        connections.values.forEach { it.channel.close() }
        connections.clear()
        endpoints.clear()
        stopNsd()
        _state.update { it.copy(running = false, peers = emptyList()) }
    }

    // ---------------- discovery ----------------

    @Suppress("DEPRECATION")
    private fun startNsd(tag: String, port: Int): Channel<NsdServiceInfo> {
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("budget-sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val myName = "$SERVICE_PREFIX$tag-$shortId"
        val info = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = log("Couldn't advertise on the network (error $errorCode)")
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = log("Network discovery failed (error $errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName.startsWith("$SERVICE_PREFIX$tag-") && !s.serviceName.contains(shortId)) {
                    resolveQueue.trySend(s)
                }
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                endpoints.remove(s.serviceName)
            }
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }
            .onSuccess { registration = reg }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc) }
            .onSuccess { discovery = disc }

        return resolveQueue
    }

    /** Android only resolves one service at a time, so resolve sequentially. */
    @Suppress("DEPRECATION")
    private suspend fun resolveLoop(found: Channel<NsdServiceInfo>) {
        val manager = nsd ?: return
        for (s in found) {
            val resolved = withTimeoutOrNull(10_000) { resolve(manager, s) } ?: continue
            val host = resolved.host ?: continue
            endpoints[s.serviceName] = Endpoint(
                InetSocketAddress(host, resolved.port),
                s.serviceName.substringAfterLast('-'),
                manual = false,
            )
            connectKnown()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, s: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            manager.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(info)
                }
            })
        }

    private fun stopNsd() {
        val m = nsd ?: return
        registration?.let { runCatching { m.unregisterService(it) } }
        discovery?.let { runCatching { m.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ---------------- connections ----------------

    /** Connect by address when discovery is blocked (some routers/guest networks block mDNS). */
    fun connectManually(host: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            val addr = runCatching { InetSocketAddress(InetAddress.getByName(host.trim()), port) }.getOrNull()
            if (addr == null) {
                log("Couldn't find $host")
                return@launch
            }
            endpoints["manual:$host:$port"] = Endpoint(addr, null, manual = true)
            connectKnown()
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) = coroutineScope {
        while (isActive) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            launch { handle(socket) }
        }
    }

    private suspend fun reconnectLoop() {
        while (true) {
            delay(20_000)
            connectKnown()
        }
    }

    private fun connectKnown() {
        val session = session ?: return
        for ((key, ep) in endpoints) {
            val already = if (ep.peerShortId != null) {
                connections.keys.any { it.replace("-", "").startsWith(ep.peerShortId) }
            } else {
                connections.values.any { it.address == ep.address.address }
            }
            // Only one side dials for discovered peers, so the two phones don't race.
            val shouldDial = ep.manual || (ep.peerShortId != null && shortId < ep.peerShortId)
            if (already || !shouldDial || !connecting.add(key)) continue
            session.launch(Dispatchers.IO) {
                try {
                    val s = Socket()
                    s.connect(ep.address, 5_000)
                    handle(s)
                } catch (e: Exception) {
                    if (ep.manual) log("Couldn't reach ${ep.address.address.hostAddress}: ${e.message}")
                } finally {
                    connecting.remove(key)
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        val c = crypto ?: return socket.close()
        socket.tcpNoDelay = true
        socket.soTimeout = 90_000
        val ch = FramedChannel(socket, c)
        var conn: Connection? = null
        try {
            ch.send(SyncMessage.Hello(prefs.deviceId, deviceName()))
            val hello = withContext(Dispatchers.IO) { ch.receive() } as? SyncMessage.Hello
                ?: throw IllegalStateException("Unexpected handshake")
            if (hello.deviceId == prefs.deviceId) return ch.close()
            val newConn = Connection(hello.deviceId, hello.deviceName, socket.inetAddress, ch)
            if (connections.putIfAbsent(hello.deviceId, newConn) != null) return ch.close()
            conn = newConn
            publishPeers()
            log("Connected to ${hello.deviceName}")
            coroutineScope {
                launch {
                    while (isActive) {
                        delay(30_000)
                        ch.send(SyncMessage.Ping)
                    }
                }
                launch { sendChanges(newConn) }
                withContext(Dispatchers.IO) {
                    while (isActive) {
                        when (val msg = ch.receive()) {
                            is SyncMessage.Changes -> {
                                val result = repo.mergeRemote(msg.bundle)
                                newConn.lastActivity = System.currentTimeMillis()
                                _state.update { it.copy(lastSyncAt = newConn.lastActivity) }
                                if (result.applied > 0) {
                                    log("Received ${result.applied} update(s) from ${newConn.name}")
                                    if (result.newSpending.isNotEmpty()) alerts.check(triggeredBy = newConn.name)
                                }
                                publishPeers()
                            }
                            else -> newConn.lastActivity = System.currentTimeMillis()
                        }
                    }
                }
            }
        } catch (e: AEADBadTagException) {
            log("A phone with a different household code tried to connect and was refused.")
        } catch (e: Exception) {
            conn?.let { log("Disconnected from ${it.name}") }
        } finally {
            ch.close()
            conn?.let { connections.remove(it.deviceId, it) }
            publishPeers()
        }
    }

    /** Sends everything changed since the last successful send to this peer. */
    private suspend fun sendChanges(conn: Connection) = conn.sendMutex.withLock {
        val since = prefs.watermark(conn.deviceId)
        val startedAt = System.currentTimeMillis()
        val bundle = repo.changesSince(if (since == 0L) 0L else since - CLOCK_MARGIN_MS)
        if (!bundle.isEmpty()) {
            conn.channel.send(SyncMessage.Changes(bundle))
            log("Sent ${bundle.size} update(s) to ${conn.name}")
        }
        prefs.setWatermark(conn.deviceId, startedAt)
        conn.lastActivity = System.currentTimeMillis()
        _state.update { it.copy(lastSyncAt = conn.lastActivity) }
    }

    @OptIn(FlowPreview::class)
    private suspend fun liveSender() {
        repo.localChanges.debounce(400).collect {
            connections.values.forEach { c -> runCatching { sendChanges(c) } }
        }
    }

    /** Re-sends everything to connected phones, e.g. after one was restored. */
    fun fullResync() {
        scope.launch(Dispatchers.IO) {
            prefs.clearWatermarks()
            connections.values.forEach { c -> runCatching { sendChanges(c) } }
        }
    }

    // ---------------- offline file exchange ----------------

    /** Writes an encrypted copy of all data that only a phone with the household code can open. */
    suspend fun exportTo(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val c = crypto ?: error("Set up sharing first")
        val bundle = repo.changesSince(0)
        out.write(FILE_MAGIC)
        out.write(c.seal(SyncJson.encodeToString(com.budgetingtool.app.data.SyncBundle.serializer(), bundle).encodeToByteArray()))
        bundle.size
    }

    suspend fun importFrom(input: InputStream): Int = withContext(Dispatchers.IO) {
        val c = crypto ?: error("Set up sharing first")
        val bytes = input.readBytes()
        require(bytes.size > FILE_MAGIC.size && bytes.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            "Not a Budgeting Tool sync file"
        }
        val plain = try {
            c.open(bytes.copyOfRange(FILE_MAGIC.size, bytes.size))
        } catch (e: AEADBadTagException) {
            error("This file was made with a different household code")
        }
        val bundle = SyncJson.decodeFromString(com.budgetingtool.app.data.SyncBundle.serializer(), plain.decodeToString())
        val r = repo.mergeRemote(bundle)
        if (r.newSpending.isNotEmpty()) alerts.check()
        log("Imported ${r.applied} update(s) from file")
        r.applied
    }

    // ---------------- helpers ----------------

    private fun deviceName() = prefs.userName.ifBlank { Build.MODEL ?: "Android phone" }

    private fun publishPeers() {
        _state.update { s ->
            s.copy(peers = connections.values.map { PeerInfo(it.deviceId, it.name, it.address.hostAddress ?: "", it.lastActivity) })
        }
    }

    private fun log(line: String) {
        val stamp = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        _state.update { it.copy(log = (listOf("$stamp  $line") + it.log).take(40)) }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val DEFAULT_PORT = 47321
        internal const val SERVICE_TYPE = "_budgettool._tcp."
        private const val SERVICE_PREFIX = "BudgetingTool-"
        private const val CLOCK_MARGIN_MS = 2 * 60_000L
        private val FILE_MAGIC = "BTSYNC1\n".toByteArray()
    }
}
