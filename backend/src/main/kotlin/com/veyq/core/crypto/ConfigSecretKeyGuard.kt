package com.veyq.core.crypto

import com.veyq.core.instance.InstanceStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Whether the server's config-secret KEK can actually open the stored secrets (ADR 0050). */
enum class ConfigSecretKeyState {
    /** A KEK is available and matches the fingerprint that sealed the stored secrets. */
    OK,

    /** No KEK is available (generation failed / unwritable path) — sealing is unavailable, and SMTP that needs a
     *  password cannot send: member invites and password recovery refuse with `503` (ADR 0055 §2/§4). */
    ABSENT,

    /** A KEK is present but is NOT the one that sealed the stored secrets (the key file changed/was lost) — the
     *  stored password can't be decrypted; re-enter it to reseal under the current key. */
    MISMATCH,
}

/**
 * The config-secret KEK tripwire (ADR 0050). Turns a lost/changed key file from a **silent** unseal
 * failure into a **detected** [ConfigSecretKeyState.MISMATCH] by comparing the active KEK's [ConfigSecretSeal.fingerprint]
 * against the one stored on the [veyq_instance][InstanceStore] singleton. DB-touching, so it lives here rather
 * than in the pure-JVM [ConfigSecretSeal].
 */
@ApplicationScoped
class ConfigSecretKeyGuard(
    private val seal: ConfigSecretSeal,
    private val instance: InstanceStore,
) {

    /**
     * The current state. [ConfigSecretKeyState.ABSENT] when no KEK is available. Otherwise compares the active
     * KEK's fingerprint to the stored one: **no stored fingerprint yet ⇒ adopt** the current key (write it) ⇒ OK;
     * a match ⇒ OK; a difference ⇒ MISMATCH. The adopt-write is idempotent and fires only on the first configured
     * read (any real deployment adopts on its first mail-settings read or send). A MISMATCH is **never**
     * auto-overwritten here — that would defeat detection; it clears only via [recordReseal].
     */
    @Transactional
    fun state(): ConfigSecretKeyState {
        val fp = seal.fingerprint() ?: return ConfigSecretKeyState.ABSENT
        val stored = instance.configSecretFingerprint()
        if (stored == null) {
            instance.setConfigSecretFingerprint(fp)
            return ConfigSecretKeyState.OK
        }
        return if (stored == fp) ConfigSecretKeyState.OK else ConfigSecretKeyState.MISMATCH
    }

    /**
     * Record that the stored secrets were just (re)sealed under the CURRENT key — call after a successful reseal.
     * Advances the stored fingerprint to the current key, so a prior MISMATCH clears to OK (the recovery path: the
     * owner re-enters the SMTP password). A no-op when unconfigured.
     */
    @Transactional
    fun recordReseal() {
        val fp = seal.fingerprint() ?: return
        instance.setConfigSecretFingerprint(fp)
    }
}
