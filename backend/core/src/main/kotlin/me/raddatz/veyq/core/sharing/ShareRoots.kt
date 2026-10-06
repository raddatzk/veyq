package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * Which share roles a node of a kind may be the root of (ADR 0080 decision 6: shareability is separate from guest
 * writes). The owner of a kind answers for it: core for `FOLDER` ([FolderShareRoots]), an application for its own
 * kinds. `SharingHandler` asks it when a share is created and when a share's role changes; the answer is the role
 * names of [ShareEntity] (`READ`, `READ_WRITE`, `WRITE_ONLY`).
 *
 * **Exactly one implementation per kind, and none is the default** — the same partition as `NodeKindRules`. A kind
 * no implementation handles, or one two implementations claim, may be the root of no share (`illegal_scope`), rather
 * than letting CDI bean order choose. An empty collection therefore shares nothing, `FOLDER` included: fail closed.
 *
 * Being a share root says nothing about who runs a guest's upload under it: that is [ShareGuestWrites], which fails
 * closed on its own for a kind no application writes (a `READ_WRITE` folder share takes no upload today).
 */
interface ShareRoots {
    /** The roles a share rooted at a node of [kind] may have, or null when this implementation does not handle [kind]. */
    fun roles(kind: String): Set<String>?

    companion object {
        /** The one implementation of [roots] that handles [kind] decides; none or several → no role at all. */
        fun roles(roots: List<ShareRoots>, kind: String): Set<String> =
            roots.mapNotNull { it.roles(kind) }.singleOrNull() ?: emptySet()

        /** Whether a share of [role] may be rooted at a node of [kind] ([roles]). */
        fun allows(roots: List<ShareRoots>, kind: String, role: String): Boolean = role in roles(roots, kind)
    }
}

/**
 * Core's own kind: a `FOLDER` may be shared to read (`READ`) or to contribute (`READ_WRITE`). Never `WRITE_ONLY`:
 * a drop box is a node of its own, created with the share (ADR 0080 decision 11), not an existing folder.
 */
@ApplicationScoped
class FolderShareRoots : ShareRoots {
    override fun roles(kind: String): Set<String>? =
        if (kind == NodeEntity.FOLDER) setOf(ShareEntity.READ, ShareEntity.READ_WRITE) else null
}
