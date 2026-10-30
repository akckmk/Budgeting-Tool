package com.budgetingtool.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.budgetingtool.app.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

data class Invite(val code: String, val expiresAt: Long)

data class PairingState(
    /** Inviting phone: the open one-time invite. */
    val invite: Invite? = null,
    /** Inviting phone: someone with the invite code is asking to join. Waits for Allow/Deny. */
    val pendingRequestFrom: String? = null,
    /** Joining phone: progress text. */
    val joinStatus: String? = null,
    val joining: Boolean = false,
    val lastResult: String? = null,
)

/**
 * Pairs a second phone without the household key ever leaving the phones.
 *
 * 1. Phone A taps "Invite" and gets a 12-character, single-use code that expires in 24 hours.
 *    That code is the only thing sent by Gmail or text.
 * 2. Phone B enters the code while on the same Wi-Fi. It finds A on the local network and sends
 *    a join request encrypted with the code. Wrong codes fail to decrypt and are silently dropped.
 * 3. A's owner sees "<name> wants to join" and must tap Allow. Only then does A send the random
 *    household key, encrypted with the invite code, and the invite is destroyed.
 *
 * Someone who intercepts the email still has to be on your Wi-Fi before your spouse uses the
 * code, and you'd see and deny their request.
 */
class PairingManager(
    context: Context,
    private val prefs: Prefs,
    private val sync: SyncManager,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _state = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var inviteJob: Job? = null
    private var joinJob: Job? = null
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var decision: CompletableDeferred<Boolean>? = null

    private fun deviceName() = prefs.userName.ifBlank { Build.MODEL ?: "Android phone" }

    // ---------------- inviting phone ----------------

    fun createInvite() {
        cancelInvite()
        if (prefs.householdCode == null) sync.createHousehold()
        val invite = Invite(SyncCrypto.generateInviteCode(), System.currentTimeMillis() + INVITE_TTL_MS)
        _state.update { it.copy(invite = invite, lastResult = null) }
        inviteJob = scope.launch(Dispatchers.IO) {
            val crypto = SyncCrypto(invite.code)
            val ss = try {
                ServerSocket(PAIR_PORT)
            } catch (e: Exception) {
                ServerSocket(0)
            }
            server = ss
            register("$PAIR_PREFIX${crypto.householdTag}", ss.localPort)
            launch {
                delay(INVITE_TTL_MS)
                _state.update { it.copy(lastResult = "The invite expired. Create a new one when you're ready.") }
                cancelInvite()
            }
            try {
                while (true) {
                    val socket = ss.accept()
                    if (handleJoin(socket, crypto)) {
                        cancelInvite()
                        break
                    }
                }
            } catch (_: Exception) {
                // server closed: invite cancelled or expired
            }
        }
    }

    /** Returns true once a phone has been admitted (the invite is then used up). */
    private suspend fun handleJoin(socket: Socket, crypto: SyncCrypto): Boolean {
        socket.soTimeout = 30_000
        val ch = FramedChannel(socket, crypto)
        try {
            // Anything not encrypted with this invite code throws here and is dropped.
            val req = withContext(Dispatchers.IO) { ch.receive() } as? SyncMessage.JoinRequest ?: return false
            val d = CompletableDeferred<Boolean>()
            decision = d
            _state.update { it.copy(pendingRequestFrom = req.deviceName) }
            val allowed = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { d.await() } ?: false
            decision = null
            _state.update { it.copy(pendingRequestFrom = null) }
            val key = prefs.householdCode
            return if (allowed && key != null) {
                ch.send(SyncMessage.JoinAccepted(SyncCrypto.normalize(key), deviceName()))
                socket.inetAddress.hostAddress?.let { sync.rememberPeer(it, SyncManager.DEFAULT_PORT) }
                _state.update { it.copy(lastResult = "${req.deviceName} joined your household. Your data now syncs between your phones.") }
                true
            } else {
                ch.send(SyncMessage.JoinDenied)
                _state.update { it.copy(lastResult = "You declined ${req.deviceName}.") }
                false
            }
        } catch (_: Exception) {
            return false
        } finally {
            ch.close()
        }
    }

    fun answer(allow: Boolean) {
        decision?.complete(allow)
    }

    fun cancelInvite() {
        inviteJob?.cancel()
        inviteJob = null
        runCatching { server?.close() }
        server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        decision?.complete(false)
        _state.update { it.copy(invite = null, pendingRequestFrom = null) }
    }

    private fun register(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = PAIR_TYPE
            setPort(port)
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }.onSuccess { registration = reg }
    }

    // ---------------- joining phone ----------------

    /** [host] is optional: the inviting phone's IP, for networks that block device discovery. */
    fun join(inviteCode: String, host: String?) {
        if (!SyncCrypto.isValidInvite(inviteCode)) {
            _state.update { it.copy(joinStatus = "Invite codes are 12 letters and numbers, like ABCD-EFGH-JKLM.") }
            return
        }
        joinJob?.cancel()
        joinJob = scope.launch(Dispatchers.IO) {
            _state.update { it.copy(joining = true, joinStatus = "Checking the code…") }
            try {
                val crypto = SyncCrypto(inviteCode)
                val target = if (!host.isNullOrBlank()) {
                    InetSocketAddress(InetAddress.getByName(host.trim()), PAIR_PORT)
                } else {
                    _state.update { it.copy(joinStatus = "Looking for the other phone on this Wi-Fi…") }
                    discover(crypto.householdTag)
                }
                if (target == null) {
                    _state.update {
                        it.copy(joinStatus = "Couldn't find the other phone. Make sure both phones are on the same Wi-Fi and the invite screen is open there, or enter its IP address.")
                    }
                    return@launch
                }
                val socket = Socket()
                socket.connect(target, 8_000)
                socket.soTimeout = (APPROVAL_TIMEOUT_MS + 15_000).toInt()
                val ch = FramedChannel(socket, crypto)
                try {
                    ch.send(SyncMessage.JoinRequest(prefs.deviceId, deviceName()))
                    _state.update { it.copy(joinStatus = "Found it! Waiting for approval on the other phone…") }
                    when (val reply = ch.receive()) {
                        is SyncMessage.JoinAccepted -> {
                            if (sync.adoptHousehold(reply.householdKey)) {
                                // Remembered so the phones find each other even where discovery is blocked.
                                sync.connectManually(target.address.hostAddress ?: target.hostString, SyncManager.DEFAULT_PORT)
                                _state.update { it.copy(joinStatus = null, lastResult = "You're in! Now sharing with ${reply.inviterName}.") }
                            }
                        }
                        is SyncMessage.JoinDenied -> _state.update { it.copy(joinStatus = "The other phone declined the request.") }
                        else -> _state.update { it.copy(joinStatus = "Unexpected reply. Try again.") }
                    }
                } finally {
                    ch.close()
                }
            } catch (e: javax.crypto.AEADBadTagException) {
                _state.update { it.copy(joinStatus = "That invite code didn't match. Check it, or ask for a new invite.") }
            } catch (e: Exception) {
                _state.update { it.copy(joinStatus = "Couldn't connect (${e.message ?: e.javaClass.simpleName}). Is the invite still open on the other phone?") }
            } finally {
                _state.update { it.copy(joining = false) }
            }
        }
    }

    fun cancelJoin() {
        joinJob?.cancel()
        _state.update { it.copy(joining = false, joinStatus = null) }
    }

    @Suppress("DEPRECATION")
    private suspend fun discover(tag: String): InetSocketAddress? {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName.startsWith("$PAIR_PREFIX$tag")) found.trySend(s)
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        nsd.discoverServices(PAIR_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                for (s in found) {
                    val r = withTimeoutOrNull(10_000) { resolve(s) } ?: continue
                    val host = r.host ?: continue
                    return@withTimeoutOrNull InetSocketAddress(host, r.port)
                }
                null
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(s: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (cont.isActive) cont.resume(info)
            }
        })
    }

    companion object {
        const val PAIR_PORT = 47322
        private const val PAIR_TYPE = "_budgetpair._tcp."
        private const val PAIR_PREFIX = "BTPair-"
        private const val INVITE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val APPROVAL_TIMEOUT_MS = 120_000L
        private const val DISCOVERY_TIMEOUT_MS = 60_000L
    }
}
