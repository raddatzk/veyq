package me.raddatz.veyq.core.crypto

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/** Whether the server's config-secret KEK can actually open the stored secrets (ADR 0050). */
enum class ConfigSecretKeyState {
    /** A KEK is available and every stored sealed value carries its fingerprint — or nothing is sealed yet. */
    OK,

    /** No KEK is available (generation failed / unwritable path) — sealing is unavailable, and SMTP that needs a
     *  password cannot send: member invites and password recovery refuse with `503` (ADR 0055 §2/§4). */
    ABSENT,

    /** A KEK is present but is NOT the one that sealed a stored value (the key file changed/was lost) — that value
     *  can't be decrypted; re-enter it to reseal under the current key. */
    MISMATCH,
}

/**
 * A holder of sealed values, contributing the fingerprint each of them carries (ADR 0089 decision 4) to the
 * instance-wide [ConfigSecretKeyGuard.readState]. One per table that seals: the tenant SMTP passwords and the
 * object-store setting. A new sealed column is a new contribution, or the instance-wide state cannot see it.
 */
interface SealedValueFingerprints {
    /** The fingerprint of every value currently stored sealed — one entry per value, duplicates kept. */
    fun sealedFingerprints(): List<String>
}

/**
 * The config-secret KEK tripwire (ADR 0050), **per sealed value** (ADR 0089 decision 4). Every sealed value carries
 * the [ConfigSecretSeal.fingerprint] of the key that sealed it, so a lost or replaced key file is a **detected**
 * [ConfigSecretKeyState.MISMATCH] for exactly the values it sealed, rather than a silent unseal failure — and
 * re-entering one secret under the new key cannot hide that another is still sealed under the old one, which a
 * single instance-wide fingerprint advanced on every reseal did.
 *
 * Nothing here writes. There is no "adopt on first read" any more: a value is born with its fingerprint, so every
 * read — the Server Owner's verification read after a restore above all — changes nothing.
 */
@ApplicationScoped
class ConfigSecretKeyGuard(
    private val seal: ConfigSecretSeal,
    private val holders: Instance<SealedValueFingerprints>,
) {

    /** The fingerprint a value sealed now records, or null when no KEK is available (sealing is then refused). */
    fun currentFingerprint(): String? = seal.fingerprint()

    /**
     * The state of ONE sealed value, by the fingerprint it carries: [ConfigSecretKeyState.ABSENT] with no KEK; OK
     * when it was sealed under the current key or when [fingerprint] is null (no value stored, nothing to open);
     * MISMATCH otherwise.
     */
    fun stateOf(fingerprint: String?): ConfigSecretKeyState {
        val current = seal.fingerprint() ?: return ConfigSecretKeyState.ABSENT
        if (fingerprint == null) return ConfigSecretKeyState.OK
        return if (fingerprint == current) ConfigSecretKeyState.OK else ConfigSecretKeyState.MISMATCH
    }

    /**
     * The instance-wide state — the Server Owner's admin-channel read, which a restore and an upgrade run as their
     * verification step (ADR 0056 §5): ABSENT with no KEK; MISMATCH when ANY stored sealed value was sealed under
     * another key; OK otherwise, including when nothing is sealed yet.
     */
    fun readState(): ConfigSecretKeyState {
        val current = seal.fingerprint() ?: return ConfigSecretKeyState.ABSENT
        val foreign = holders.any { holder -> holder.sealedFingerprints().any { it != current } }
        return if (foreign) ConfigSecretKeyState.MISMATCH else ConfigSecretKeyState.OK
    }
}
