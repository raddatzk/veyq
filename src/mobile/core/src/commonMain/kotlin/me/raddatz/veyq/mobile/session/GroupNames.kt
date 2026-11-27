package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8

/**
 * A group's name (en.spaces.group-scope; ADR 0027, ADR 0072, ADR 0115), the port of the web's `inviteName.ts` and the name
 * half of `scopes.ts`: the canonical `encryptedName` is sealed (`group-name`, bound to the scope and its name epoch) under a
 * value key `K_v` of its own, minted fresh at every write, whose wrap the scope key holds at its epoch (`value-key`, the
 * address of `group-name` in the SEAL schema). A rotation re-wraps `K_v` and never touches the name. A pending consent
 * invitee is handed that `K_v` sealed to its identity (`group-name-key`, bound to the scope and the name epoch), so it reads
 * the same name the members read. Pinned by `v1.json#value_key` and `seal.sym['group-name']`; every open is null on
 * anything that does not open, never a throw.
 */
object GroupNames {
    fun nameContext(scopeId: String, nameEpoch: Long) = Context("group-name", "scopeId" to scopeId, "nameEpoch" to nameEpoch)
    private fun keyContext(scopeId: String, nameEpoch: Long) = Context("group-name-key", "scopeId" to scopeId, "nameEpoch" to nameEpoch)

    private fun wrapContext(suite: V1, scopeId: String, epoch: Long, nameEpoch: Long): Context {
        val joins = JoinRecords(suite)
        return joins.valueKeyContext(scopeId, epoch, joins.valueAddress(Purposes.SEAL, nameContext(scopeId, nameEpoch)))
    }

    /** [s]'s name opened with [scopeKey] (the key of its granted epoch); null where it has none or it does not open. */
    fun open(suite: V1, s: ScopeSummary, scopeKey: ByteArray): String? {
        val sealed = s.encryptedName ?: return null
        val wrapped = s.wrappedNameValueKey ?: return null
        return openWith(suite, s.id, s.grantedEpoch.toLong(), s.nameEpoch.toLong(), scopeKey, wrapped, sealed)
    }

    fun openWith(suite: V1, scopeId: String, epoch: Long, nameEpoch: Long, scopeKey: ByteArray, wrapped: String, sealed: String): String? = runCatching {
        val k = suite.open(wrapContext(suite, scopeId, epoch, nameEpoch), scopeKey, unb64(wrapped))
        try {
            suite.open(nameContext(scopeId, nameEpoch), k, unb64(sealed)).decodeToString()
        } finally {
            k.fill(0)
        }
    }.getOrNull()

    /** The name's `K_v` opened from its wrap under [scopeKey] at [epoch]; the caller zeroes it. Null where it does not open. */
    fun valueKey(suite: V1, scopeId: String, epoch: Long, nameEpoch: Long, scopeKey: ByteArray, wrapped: String): ByteArray? =
        runCatching { suite.open(wrapContext(suite, scopeId, epoch, nameEpoch), scopeKey, unb64(wrapped)) }.getOrNull()

    /** A fresh name: [name] sealed at [nameEpoch] under a fresh `K_v`, its wrap under [scopeKey] at [epoch], and `K_v` (the caller's to hand on and zero). */
    class Sealed(val encryptedName: String, val wrappedNameValueKey: String, val valueKey: ByteArray)

    fun seal(suite: V1, scopeId: String, epoch: Long, nameEpoch: Long, scopeKey: ByteArray, name: String): Sealed {
        val k = suite.p.randomBytes(32)
        return Sealed(
            b64(suite.seal(nameContext(scopeId, nameEpoch), k, utf8(name))),
            b64(suite.seal(wrapContext(suite, scopeId, epoch, nameEpoch), scopeKey, k)),
            k,
        )
    }

    /** [valueKey] sealed to an invitee's identity bundle (base64) at (scope, name epoch): the consent proposal's `sealedNameKey`. */
    fun wrapToInvitee(suite: V1, scopeId: String, nameEpoch: Long, valueKey: ByteArray, inviteeIdentityB64: String): String =
        b64(suite.sealTo(keyContext(scopeId, nameEpoch), suite.deserializeIdentityPublic(unb64(inviteeIdentityB64)).kem, valueKey))

    /** A pending invitation's name opened with this account's own identity: null when it has no key or nothing opens. */
    fun openAsInvitee(suite: V1, identity: IdentityPrivate, scopeId: String, nameEpoch: Long, sealedNameKey: String?, encryptedName: String): String? {
        if (sealedNameKey == null) return null
        return runCatching {
            val k = suite.openAs(keyContext(scopeId, nameEpoch), suite.xwingKey(identity.kemSeed), unb64(sealedNameKey))
            try {
                suite.open(nameContext(scopeId, nameEpoch), k, unb64(encryptedName)).decodeToString()
            } finally {
                k.fill(0)
            }
        }.getOrNull()
    }
}
