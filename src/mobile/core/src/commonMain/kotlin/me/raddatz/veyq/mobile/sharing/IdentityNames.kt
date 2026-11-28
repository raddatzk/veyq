package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * A link guest's own display name, opened (the web's `core/sharing/guestIdentity.ts` `openIdentityName`; cr-0101, ADR 0072,
 * ADR 0080 decision 6): sealed `share-identity-name` bound to (scope, share, identity) under its link's root key — this node
 * or an ancestor for a READ_WRITE guest — or a WRITE_ONLY guest's own `K_up`. A member names a guest's upload by it
 * (Depot's `Uploaders`, Memories' `Buckets`). Every open is null on anything that does not open, never a throw.
 */
object IdentityNames {
    /**
     * [sealedName] (base64) of guest [identityId] of [shareId] in [scopeId], opened under the first of [keys] that opens it
     * (nearest first: the node's own, then each ancestor's); null where the base64 does not read or no key opens it.
     */
    fun open(suite: V1, scopeId: String, shareId: String, identityId: String, sealedName: String, keys: Sequence<ByteArray>): String? {
        val sealed = runCatching { unb64(sealedName) }.getOrNull() ?: return null
        val ctx = GuestTree.identityName(scopeId, shareId, identityId)
        return keys.firstNotNullOfOrNull { k -> runCatching { suite.openField(ctx, k, sealed).decodeToString() }.getOrNull() }
    }
}
