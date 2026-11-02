package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeEntity

/**
 * The one thing under the scope key a rotation re-seals rather than re-wraps (ADR 0115 decision 2, owner 2026-11-02): a
 * scope-root node's directory entry — its key wrapped under the scope key, its name and its slug token. Exempt from
 * "a rotation writes only wraps" for two reasons: any member may write a root entry anyway (ADR 0111 decision 11, re-key
 * where you may write), so a rotator re-sealing it can do nothing a member could not; and the slug token is a keyed MAC
 * under the scope key, which must move with the key. The re-wrap binds `placementRevision + 1` (the `content-key` and
 * `node-name` seals), as every placement.
 *
 * Its own file, named by `scripts/validate_scope_rekey_arity.py`: the rotation's commit ([ScopeRekeyCommit]) and every
 * [ScopeRekeyParticipant] may assign no column but a value key's wrap, and this file — which names no entity holding a
 * value — is where the exempt root columns are written.
 */
internal object ScopeRootEntries {
    /** Swap a root's re-sealed entry in: the wrap of its (unchanged) key, its name, and — for a live root — its slug token. */
    fun reseal(node: NodeEntity, wrappedContentKey: String, encryptedName: String, slugToken: String?) {
        node.wrappedContentKey = wrappedContentKey
        node.encryptedName = encryptedName
        slugToken?.let { node.slugToken = it }
        node.placementRevision += 1
    }
}
