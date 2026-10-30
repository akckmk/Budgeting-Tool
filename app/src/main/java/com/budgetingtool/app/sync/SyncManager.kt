package com.budgetingtool.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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

data class PeerInfo(
    val deviceId: String,
    val name: String,
    val address: String,
    val lastActivity: Long,
    /** What the other phone reported holding after its last save; null until it confirms. */
    val transactions: Int? = null,
    val statements: Int? = null,
)

data class SyncUiState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val householdCode: String? = null,
    val localAddress: String? = null,
    val port: Int = 0,
    val peers: List<PeerInfo> = emptyList(),
    val lastSyncAt: Long = 0,
    val log: List<String> = emptyList(),
    /** Most recent connection problem, shown on the sharing screen. Cleared on success. */
    val lastError: String? = null,
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
        /** True for the one connection both phones agree to keep when they dial each other at once. */
        val preferred: Boolean,
        val protocol: Int,
        @Volatile var lastActivity: Long = System.currentTimeMillis(),
    ) {
        /** Newest change the other phone has confirmed saving (our clock). */
        @Volatile var ackedUpTo: Long = 0
        @Volatile var peerTransactions: Int? = null
        @Volatile var peerStatements: Int? = null
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
    private val lastDialError = ConcurrentHashMap<String, String>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
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
        SyncService.start(appContext)
    }

    fun leaveHousehold() {
        SyncService.stop(appContext)
        stop()
        prefs.householdCode = null
        prefs.knownPeers = emptySet()
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
            watchNetwork()
            connectKnown() // don't wait for the first retry tick
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
        networkCallback?.let { cb -> runCatching { connectivity().unregisterNetworkCallback(cb) } }
        networkCallback = null
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
            rememberPeer(addr.address.hostAddress ?: host.trim(), port)
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
            delay(RETRY_MS)
            connectKnown()
        }
    }

    /** Remembers where the other phone was reachable, so it can be found again without discovery. */
    fun rememberPeer(host: String, port: Int) {
        if (host.isBlank() || port <= 0) return
        val entry = "$host:$port"
        prefs.knownPeers = (listOf(entry) + prefs.knownPeers.filter { it != entry }).take(MAX_KNOWN_PEERS).toSet()
    }

    /** Dials every address we know for the other phone (discovered, typed, or remembered) that isn't connected. */
    fun connectKnown() {
        val session = session ?: return
        val targets = HashMap<String, Endpoint>(endpoints)
        prefs.knownPeers.forEach { entry ->
            val host = entry.substringBeforeLast(':')
            val port = entry.substringAfterLast(':').toIntOrNull() ?: return@forEach
            targets.putIfAbsent("known:$entry", Endpoint(InetSocketAddress.createUnresolved(host, port), null, manual = true))
        }
        for ((key, ep) in targets) {
            val already = if (ep.peerShortId != null) {
                connections.keys.any { it.replace("-", "").startsWith(ep.peerShortId) }
            } else {
                connections.values.any { it.address.hostAddress == ep.address.hostString }
            }
            if (already || !connecting.add(key)) continue
            // Both phones dial; if they meet twice, [handle] keeps the same single connection on both sides.
            session.launch(Dispatchers.IO) {
                try {
                    val addr = if (ep.address.isUnresolved) InetSocketAddress(InetAddress.getByName(ep.address.hostString), ep.address.port) else ep.address
                    val s = Socket()
                    s.connect(addr, 5_000)
                    lastDialError.remove(key)
                    handle(s, outgoing = true)
                } catch (e: Exception) {
                    val why = "Couldn't reach ${ep.address.hostString}:${ep.address.port} (${e.message ?: e.javaClass.simpleName})"
                    if (lastDialError.put(key, why) != why) log(why)
                    _state.update { it.copy(lastError = if (connections.isEmpty()) why else null) }
                } finally {
                    connecting.remove(key)
                }
            }
        }
    }

    /** Wi-Fi came back or changed: refresh our address and try the other phone right away. */
    private fun watchNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.update { it.copy(localAddress = localIpv4()) }
                session?.launch { delay(1_500); connectKnown() }
            }
            override fun onLost(network: Network) {
                _state.update { it.copy(localAddress = localIpv4()) }
            }
        }
        runCatching { connectivity().registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun connectivity() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Tries to connect now and pushes anything unsent. */
    fun syncNow() {
        scope.launch(Dispatchers.IO) {
            connectKnown()
            connections.values.forEach { c -> runCatching { sendChanges(c, full = true) } }
        }
    }

    private suspend fun handle(socket: Socket, outgoing: Boolean = false) {
        val c = crypto ?: return socket.close()
        socket.tcpNoDelay = true
        socket.soTimeout = 90_000
        val ch = FramedChannel(socket, c)
        var conn: Connection? = null
        try {
            ch.send(SyncMessage.Hello(prefs.deviceId, deviceName(), port = server?.localPort ?: 0))
            val hello = withContext(Dispatchers.IO) { ch.receive() } as? SyncMessage.Hello
                ?: throw IllegalStateException("Unexpected handshake")
            if (hello.deviceId == prefs.deviceId) return ch.close()
            // If both phones dial each other at the same moment, keep the connection dialed by the
            // phone with the smaller id. Both sides apply the same rule, so they keep the same one.
            val dialer = if (outgoing) prefs.deviceId else hello.deviceId
            val preferred = dialer == minOf(prefs.deviceId, hello.deviceId)
            val newConn = Connection(hello.deviceId, hello.deviceName, socket.inetAddress, ch, preferred, hello.protocol)
            val replaced: Connection?
            synchronized(connections) {
                val existing = connections[hello.deviceId]
                if (existing != null && (existing.preferred || !preferred)) return ch.close()
                replaced = existing
                connections[hello.deviceId] = newConn
            }
            replaced?.channel?.close()
            conn = newConn
            socket.inetAddress.hostAddress?.let { ip -> if (hello.port > 0) rememberPeer(ip, hello.port) }
            lastDialError.clear()
            _state.update { it.copy(lastError = null) }
            publishPeers()
            log("Connected to ${hello.deviceName}")
            coroutineScope {
                launch {
                    while (isActive) {
                        delay(30_000)
                        ch.send(SyncMessage.Ping)
                    }
                }
                // Every new connection starts with a full catch-up, so nothing missed earlier stays missing.
                launch { sendChanges(newConn, full = true) }
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
                                } else if (msg.full) {
                                    log("Checked everything with ${newConn.name}: already up to date")
                                }
                                // Saved: tell the sender, with our totals so both screens can compare.
                                if (msg.sentAt > 0) {
                                    val (t, st, a) = repo.counts()
                                    newConn.channel.send(SyncMessage.Ack(msg.sentAt, t, st, a))
                                }
                                publishPeers()
                            }
                            is SyncMessage.Ack -> {
                                newConn.ackedUpTo = maxOf(newConn.ackedUpTo, msg.upTo)
                                newConn.peerTransactions = msg.transactions
                                newConn.peerStatements = msg.statements
                                newConn.lastActivity = System.currentTimeMillis()
                                _state.update { it.copy(lastSyncAt = newConn.lastActivity) }
                                publishPeers()
                            }
                            else -> newConn.lastActivity = System.currentTimeMillis()
                        }
                    }
                }
            }
        } catch (e: AEADBadTagException) {
            log("A phone with a different household key tried to connect and was refused.")
            _state.update { it.copy(lastError = "The other phone has a different household key. Stop sharing on it and join again with a new invite.") }
        } catch (e: Exception) {
            conn?.let { log("Disconnected from ${it.name}") }
        } finally {
            ch.close()
            conn?.let { connections.remove(it.deviceId, it) }
            publishPeers()
        }
    }

    /**
     * Sends changes the other phone hasn't confirmed yet ([full] = everything).
     * Changes only count as delivered when the other phone sends back an [SyncMessage.Ack]; a send
     * into a connection that silently died is simply sent again next time.
     */
    private suspend fun sendChanges(conn: Connection, full: Boolean = false) = conn.sendMutex.withLock {
        val startedAt = System.currentTimeMillis()
        if (conn.protocol < 2) {
            // Older app on the other phone: it can't acknowledge, so fall back to send-and-hope.
            val since = if (full) 0L else prefs.watermark(conn.deviceId)
            val bundle = repo.changesSince(if (since == 0L) 0L else since - CLOCK_MARGIN_MS)
            if (!bundle.isEmpty()) conn.channel.send(SyncMessage.Changes(bundle))
            prefs.setWatermark(conn.deviceId, startedAt)
        } else {
            val since = if (full || conn.ackedUpTo == 0L) 0L else conn.ackedUpTo - CLOCK_MARGIN_MS
            val bundle = repo.changesSince(since)
            if (full || !bundle.isEmpty()) {
                conn.channel.send(SyncMessage.Changes(bundle, sentAt = startedAt, full = full))
                if (!bundle.isEmpty()) log("Sent ${bundle.size} update(s) to ${conn.name}")
            }
        }
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
            connections.values.forEach { c -> runCatching { sendChanges(c, full = true) } }
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
            s.copy(
                peers = connections.values.map {
                    PeerInfo(it.deviceId, it.name, it.address.hostAddress ?: "", it.lastActivity, it.peerTransactions, it.peerStatements)
                },
            )
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
        private const val RETRY_MS = 10_000L
        private const val MAX_KNOWN_PEERS = 4
        private val FILE_MAGIC = "BTSYNC1\n".toByteArray()
    }
}
