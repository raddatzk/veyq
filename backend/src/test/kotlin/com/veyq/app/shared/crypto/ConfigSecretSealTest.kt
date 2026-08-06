package com.veyq.app.shared.crypto

import com.veyq.app.shared.config.testVeyqConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Guards for the at-rest config-secret seal (ADR 0050 + amendment). The load-bearing properties: a sealed secret
 * is only ever opaque ciphertext (tampering or a wrong KEK fails GCM auth), an unavailable KEK is an error not a
 * plaintext fallback, the KEK is a **server-managed key file** (generated `0600` on first construction), and its
 * DB **fingerprint is a hash, never the key** (so a DB dump stays useless).
 */
class ConfigSecretSealTest {

    @TempDir
    lateinit var tmp: Path

    /** A seal whose key file holds [keyBytes] (base64). Writes the file first, then points the config at it. */
    private fun sealWith(name: String = "kek", keyBytes: ByteArray = ByteArray(32) { it.toByte() }): ConfigSecretSeal {
        val path = tmp.resolve(name)
        Files.writeString(path, Base64.getEncoder().encodeToString(keyBytes))
        return sealAt(path)
    }

    private fun sealAt(path: Path): ConfigSecretSeal =
        ConfigSecretSeal(testVeyqConfig(mapOf("veyq.security.config-secret-file" to path.toString())))

    @Test
    fun sealUnsealRoundTrips() {
        val seal = sealWith()
        val secret = "smtp-p@ssw0rd!"
        val sealed = seal.seal(secret)
        assertNotEquals(secret, sealed.ciphertext, "ciphertext must not be the plaintext")
        assertEquals(secret, seal.unseal(sealed), "unseal must recover the plaintext")
    }

    @Test
    fun eachSealUsesAFreshNonce() {
        val seal = sealWith()
        assertNotEquals(seal.seal("x").nonce, seal.seal("x").nonce, "a fresh nonce per seal")
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val seal = sealWith()
        val tampered = seal.seal("secret").let { it.copy(ciphertext = flipLastByte(it.ciphertext)) }
        assertThrows(AEADBadTagException::class.java) { seal.unseal(tampered) }
    }

    @Test
    fun aWrongKekCannotOpenTheSecret() {
        val sealed = sealWith("a", ByteArray(32) { 1 }).seal("secret")
        val other = sealWith("b", ByteArray(32) { 2 })
        assertThrows(AEADBadTagException::class.java) { other.unseal(sealed) }
    }

    @Test
    fun generatesAKeyFileOnFirstConstructionWith0600Perms() {
        val path = tmp.resolve("fresh.key")
        assertFalse(Files.exists(path), "precondition: no key file yet")
        val seal = sealAt(path)
        assertTrue(seal.configured, "a generated key ⇒ configured")
        assertTrue(Files.exists(path), "the key file is written on first construction")
        // It is a real, usable 32-byte key.
        assertEquals(32, Base64.getDecoder().decode(Files.readString(path).trim()).size)
        assertEquals("s", seal.unseal(seal.seal("s")), "the generated key round-trips")
        // 0600 on a POSIX filesystem (the deploy target); skipped where POSIX is unsupported.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path),
                "a generated key file must be owner-read/write only",
            )
        }
    }

    @Test
    fun readsAnExistingKeyFileRatherThanRegenerating() {
        val path = tmp.resolve("stable.key")
        val first = sealAt(path)
        val sealed = first.seal("keep")
        // A second seal over the SAME path must load the same key (not generate a new one) and open the ciphertext.
        assertEquals("keep", sealAt(path).unseal(sealed), "an existing key file is reused, not overwritten")
    }

    @Test
    fun theFingerprintIsStableAndIsNotTheRawKey() {
        val keyBytes = ByteArray(32) { 7 }
        val seal = sealWith("fp", keyBytes)
        val fp = seal.fingerprint()!!
        assertEquals(fp, seal.fingerprint(), "the fingerprint is deterministic")
        // It is a HASH, not the key: it does not equal the raw key nor its base64 (the DB-dump property, ADR 0050).
        val fpBytes = Base64.getDecoder().decode(fp)
        assertFalse(fpBytes.contentEquals(keyBytes), "the fingerprint must not be the raw key bytes")
        assertNotEquals(Base64.getEncoder().encodeToString(keyBytes), fp, "nor the key's base64")
    }

    @Test
    fun aDifferentKeyHasADifferentFingerprint() {
        assertNotEquals(
            sealWith("k1", ByteArray(32) { 1 }).fingerprint(),
            sealWith("k2", ByteArray(32) { 2 }).fingerprint(),
            "distinct keys ⇒ distinct fingerprints (the tripwire can tell them apart)",
        )
    }

    @Test
    fun anUnavailableKekIsAnErrorNotAPlaintextLeak() {
        // Point the key file inside a path whose parent is a regular FILE ⇒ generation fails ⇒ degrade (not crash).
        val notADir = Files.createFile(tmp.resolve("blocker"))
        val seal = sealAt(notADir.resolve("key")) // parent = a file ⇒ createDirectories throws ⇒ configured=false
        assertFalse(seal.configured, "an un-writable key path ⇒ not configured (degrade, no plaintext)")
        assertThrows(IllegalStateException::class.java) { seal.seal("secret") }
        assertEquals(null, seal.fingerprint(), "no key ⇒ no fingerprint")
    }

    @Test
    fun aMalformedKeyFileFailsLoudlyAtConstruction() {
        val path = tmp.resolve("bad.key")
        Files.writeString(path, Base64.getEncoder().encodeToString(ByteArray(16))) // 16 bytes ≠ AES-256
        assertThrows(IllegalArgumentException::class.java) { sealAt(path) }
    }

    private fun flipLastByte(b64: String): String {
        val bytes = Base64.getDecoder().decode(b64)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }
}
