package com.veyq.core.crypto

import com.veyq.core.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A server-held config secret sealed at rest: opaque AES-256-GCM [ciphertext] + its random [nonce], both base64.
 * Stored as two columns; only a [ConfigSecretSeal] holding the KEK can open it (ADR 0050).
 */
data class SealedSecret(val ciphertext: String, val nonce: String)

/**
 * Seals server-held **operator config secrets** (first consumer: a tenant's SMTP password, ADR 0048) at rest
 * under a Key-Encryption-Key. AES-256-GCM via JCA — standard primitives, no home-grown crypto. The DB holds only
 * ciphertext + nonce, so a database dump *alone* yields no usable secret (ADR 0050); opening needs the KEK.
 *
 * **KEK source (ADR 0050): a server-managed key file**, not an env value. On construction the server
 * reads the base64 32-byte key from [VeyqConfig.Security.configSecretFile]; if that file does not exist it
 * **generates** one (32 random bytes), writes it `0600`, and logs loudly to back it up. An operator supplying
 * their own key mounts it and points the path at it. A read/generate **IO** failure (unwritable path) degrades to
 * [configured] = false rather than crashing; a present-but-**malformed** key still fails loudly at construction.
 * Degraded, any tenant whose SMTP carries a `username` becomes non-sendable (`TenantMailSender.sendable`), so
 * member invites and password recovery are **refused** with `503` (ADR 0055 §2/§4) — only the Server-Owner's
 * tenant-bootstrap invite still degrades to a copy-link (§3).
 *
 * This is operator-side crypto for the server's OWN infrastructure secrets — distinct from, and no threat to, the
 * client-side content E2EE the server never touches (ADR 0012).
 *
 * [fingerprint] derives a stable, non-invertible HKDF fingerprint of the active KEK; the startup guard stores it
 * in the DB so a lost/changed key becomes a **detected** state instead of a silent unseal failure (ADR 0050).
 * A 256-bit full-entropy key is not brute-forceable from its hash, so the fingerprint leaks nothing.
 */
@ApplicationScoped
class ConfigSecretSeal(config: VeyqConfig) {

    private val key: SecretKey? = readOrGenerate(config.security().configSecretFile())

    /** True when a KEK is available. False lets a caller degrade — refuse the send (ADR 0055 §2/§4), or fall back
     *  to the Server-Owner bootstrap copy-link (§3) — rather than storing a secret it cannot seal. */
    val configured: Boolean get() = key != null

    /** Seal a plaintext secret. Throws if no KEK is available — never returns the plaintext unsealed. */
    fun seal(plaintext: String): SealedSecret {
        val k = requireKey()
        val nonce = ByteArray(NONCE_BYTES).also(rng::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, k, nonce).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SealedSecret(encoder.encodeToString(ct), encoder.encodeToString(nonce))
    }

    /** Open a sealed secret. Throws on a wrong KEK or tampered ciphertext/nonce (GCM auth), or if no KEK. */
    fun unseal(sealed: SealedSecret): String {
        val k = requireKey()
        val pt = cipher(Cipher.DECRYPT_MODE, k, decoder.decode(sealed.nonce))
            .doFinal(decoder.decode(sealed.ciphertext))
        return String(pt, Charsets.UTF_8)
    }

    /**
     * A stable, non-invertible fingerprint of the active KEK — `HKDF-SHA256(ikm=KEK, salt="", info=<label>)`,
     * base64 — or null when no KEK is available. Domain-separated by the label so the stored value can never be
     * confused with key material; safe in the DB because a full-entropy key is not brute-forceable from its hash.
     */
    fun fingerprint(): String? {
        val k = key ?: return null
        return encoder.encodeToString(hkdfSha256(k.encoded, ByteArray(0), FINGERPRINT_INFO, KEY_BYTES))
    }

    private fun requireKey(): SecretKey = key
        ?: error("the config-secret KEK is not available (veyq.security.config-secret-file) — cannot seal/unseal")

    private fun cipher(mode: Int, k: SecretKey, nonce: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(mode, k, GCMParameterSpec(TAG_BITS, nonce)) }

    private fun readOrGenerate(pathStr: String): SecretKey? {
        val path = Paths.get(pathStr)
        return try {
            if (Files.exists(path)) {
                val bytes = Base64.getDecoder().decode(Files.readString(path).trim())
                require(bytes.size == KEY_BYTES) {
                    "config-secret key file $path must hold a base64 $KEY_BYTES-byte key (AES-256); decoded ${bytes.size} bytes"
                }
                SecretKeySpec(bytes, "AES")
            } else {
                val bytes = ByteArray(KEY_BYTES).also(rng::nextBytes)
                writeKeyFile(path, encoder.encodeToString(bytes))
                log.warnf(
                    "Generated a new config-secret key at %s — BACK THIS UP: losing it makes stored secrets " +
                        "(e.g. tenant SMTP passwords) unrecoverable. Keep it on a persisted volume.",
                    path.toAbsolutePath(),
                )
                SecretKeySpec(bytes, "AES")
            }
        } catch (e: IllegalArgumentException) {
            // A present-but-malformed key file is operator error — fail loudly at construction (never fall back).
            throw e
        } catch (e: Exception) {
            // An IO failure (unwritable/unreadable path) must not crash the operator-blind rest of veyq, which needs
            // no KEK. Degrade: configured=false → password-authenticated SMTP is unsendable, so the send paths
            // refuse (ADR 0050; ADR 0055 §2/§4). Only the ServerOwner bootstrap still copy-links (§3).
            log.errorf(
                "Could not read or generate the config-secret key at %s (%s) — config-secret sealing is UNAVAILABLE; " +
                    "any tenant whose SMTP needs a password cannot send, so member invites and password recovery " +
                    "are refused with 503. Fix the path/permissions to enable it.",
                pathStr, e.message,
            )
            null
        }
    }

    private fun writeKeyFile(path: Path, base64Key: String) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(base64Key.toByteArray(Charsets.UTF_8))
        }
        // Best-effort 0600 (owner read/write only); a no-op on a non-POSIX filesystem.
        runCatching { Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC = "HmacSHA256"
        val FINGERPRINT_INFO = "veyq/v1/config-secret-key-fingerprint".toByteArray(Charsets.UTF_8)
        val rng = SecureRandom()
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
        val log: Logger = Logger.getLogger(ConfigSecretSeal::class.java)

        /** HKDF-SHA256 (RFC 5869): extract-then-expand. Used only for the non-secret KEK fingerprint. */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val prk = hmac(if (salt.isEmpty()) ByteArray(mac().macLength) else salt, ikm)
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
                val n = minOf(t.size, length - pos)
                t.copyInto(out, pos, 0, n)
                pos += n
                counter++
            }
            return out
        }

        private fun mac(): Mac = Mac.getInstance(HMAC)
        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            mac().apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
    }
}
