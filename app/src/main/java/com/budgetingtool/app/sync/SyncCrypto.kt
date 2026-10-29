package com.budgetingtool.app.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM keyed from a shared secret.
 *
 * Two kinds of secret use this:
 * - The household key: 32 random characters (160 bits) generated on the first phone. It is never
 *   shown, typed or emailed; it only travels phone-to-phone during pairing, encrypted.
 * - A one-time invite code: 12 characters, single-use, expires after 24 hours, and useless
 *   without the inviting phone's owner tapping Allow. This is the only thing sent by email/text.
 */
class SyncCrypto(code: String) {
    val normalizedCode: String = normalize(code)

    /** Public, non-secret tag used to find the spouse's phone on the network. */
    val householdTag: String = sha256Hex("tag:$normalizedCode").take(10)

    private val key = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(normalizedCode.toCharArray(), SALT, 120_000, 256))
            .encoded,
        "AES",
    )

    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return iv + cipher.doFinal(gzip(plain))
    }

    /** Throws if the data was made with a different code or was tampered with. */
    fun open(blob: ByteArray): ByteArray {
        require(blob.size > 28) { "Message too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        cipher.updateAAD(AAD)
        return gunzip(cipher.doFinal(blob, 12, blob.size - 12))
    }

    companion object {
        private val SALT = "BudgetingTool/household/v1".toByteArray()
        private val AAD = "BudgetingTool/sync/v1".toByteArray()
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O or 1/I mix-ups
        const val HOUSEHOLD_KEY_LENGTH = 32 // 160 bits
        const val INVITE_LENGTH = 12 // 60 bits, single-use and short-lived
        private val random = SecureRandom()

        fun generateCode(length: Int = HOUSEHOLD_KEY_LENGTH): String =
            (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
                .let(::format)

        fun normalize(code: String): String = code.uppercase().filter { it in ALPHABET }

        fun format(code: String): String = normalize(code).chunked(4).joinToString("-")

        fun generateInviteCode(): String = generateCode(INVITE_LENGTH)

        fun isValidHouseholdKey(code: String): Boolean = normalize(code).length == HOUSEHOLD_KEY_LENGTH

        fun isValidInvite(code: String): Boolean = normalize(code).length == INVITE_LENGTH

        private fun sha256Hex(s: String) =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun gzip(b: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

        private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
    }
}
