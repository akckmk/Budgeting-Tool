package com.budgetingtool.app.sync

import com.budgetingtool.app.data.SyncBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

@Serializable
sealed class SyncMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val deviceId: String, val deviceName: String, val protocol: Int = PROTOCOL_VERSION) : SyncMessage()

    @Serializable
    @SerialName("changes")
    data class Changes(val bundle: SyncBundle) : SyncMessage()

    @Serializable
    @SerialName("ping")
    data object Ping : SyncMessage()

    /** Pairing only: sent by the joining phone, encrypted with the one-time invite code. */
    @Serializable
    @SerialName("join")
    data class JoinRequest(val deviceId: String, val deviceName: String) : SyncMessage()

    /** Pairing only: the household key, sent after the inviting phone's owner taps Allow. */
    @Serializable
    @SerialName("welcome")
    data class JoinAccepted(val householdKey: String, val inviterName: String) : SyncMessage()

    @Serializable
    @SerialName("denied")
    data object JoinDenied : SyncMessage()

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

val SyncJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    encodeDefaults = true
}

/** Length-prefixed, encrypted message frames over a TCP socket. */
class FramedChannel(private val socket: Socket, private val crypto: SyncCrypto) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Mutex()

    suspend fun send(msg: SyncMessage) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val bytes = crypto.seal(SyncJson.encodeToString(SyncMessage.serializer(), msg).encodeToByteArray())
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    /** Blocking read; call from Dispatchers.IO. */
    fun receive(): SyncMessage {
        val len = input.readInt()
        if (len <= 0 || len > MAX_FRAME) throw IOException("Bad frame size $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return SyncJson.decodeFromString(SyncMessage.serializer(), crypto.open(buf).decodeToString())
    }

    fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_FRAME = 64 * 1024 * 1024
    }
}
