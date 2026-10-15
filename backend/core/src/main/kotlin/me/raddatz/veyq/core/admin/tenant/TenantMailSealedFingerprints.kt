package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.SealedValueFingerprints
import jakarta.enterprise.context.ApplicationScoped

/**
 * The tenant SMTP passwords' part of the instance-wide config-secret state (ADR 0089 decision 4): the fingerprint
 * each stored password was sealed under. A ciphertext with no fingerprint names no key at all, so it counts as
 * foreign — it can only be one this key did not seal.
 */
@ApplicationScoped
class TenantMailSealedFingerprints : SealedValueFingerprints {
    override fun sealedFingerprints(): List<String> =
        TenantMailSettingsEntity.list("passwordCiphertext is not null").map { it.passwordFingerprint ?: "" }
}
