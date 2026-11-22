package me.raddatz.veyq.mobile.tree

import me.raddatz.veyq.mobile.crypto.Naming

/**
 * What the node tree's rules read of each kind, handed in by their caller (the platform never names an application): its
 * application's route slug, the folding its names take for their slug token, and where it may be placed. On the apps the
 * platform's registry builds it from each application's part (`AppKinds.facts`); on the web the façade builds it once from
 * the web's registry (`@veyq/core-js/tree` `kindFacts`). Both are held to `contracts/platform/tree/v1-vectors.json`'s
 * `kinds`.
 *
 * A kind not listed folds by [NAME] with no route — the server stores any kind, and a token must still be computable
 * for it — and is placed nowhere (fail closed).
 */
class KindFacts(val kinds: List<Kind>) {
    /**
     * One kind's facts: [route] its application's route slug (null: none), [folding] how its names fold for the slug token
     * ([NAME] or [FILE_NAME]), [root] and [parents] its placement.
     */
    class Kind(val kind: String, val route: String?, val folding: String, val root: Boolean, val parents: List<String>)

    private fun of(kind: String): Kind? {
        for (k in kinds) if (k.kind == kind) return k
        return null
    }

    /** Whether [kind] is one of [kinds]. */
    fun knows(kind: String): Boolean = of(kind) != null

    /**
     * The string a node's slug token HMACs, and so what its name is unique by among its siblings (ADR 0080 decision 16):
     * the name folded by its kind's folding, behind `<route>/` where its application claimed one. Two route groups never
     * meet: a Memory and a Pot of one name coexist; the plain names (folders, Depot, kept links) are one group.
     */
    fun tokenInput(kind: String, name: String): String {
        val k = of(kind)
        val slug = if (k?.folding == FILE_NAME) Naming.fileNameSlug(name) else Naming.slugify(name)
        val route = k?.route
        return if (route != null) "$route/$slug" else slug
    }

    /** Whether a node of [kind] may be placed under a parent of [parentKind] (null: the scope root): a hint, the server decides. */
    fun placementAllows(kind: String, parentKind: String?): Boolean {
        val k = of(kind) ?: return false
        return if (parentKind == null) k.root else parentKind in k.parents
    }

    companion object {
        /** The platform's container kind: the way down in the move's browser, the one a guest places into. */
        const val FOLDER = "FOLDER"

        /**
         * A container's folding, [Naming.slugify] (folders, Memories, chapters, Pots, drop boxes, kept links). The foldings are
         * named, never a function an application brings: a folding is frozen into every stored token (ADR 0080; pinned by
         * `contracts/crypto/vectors/v1.json#name_folding`), so a new one is a platform change with its vectors.
         */
        const val NAME = "NAME"

        /** A file's folding, [Naming.fileNameSlug], which keeps its punctuation. */
        const val FILE_NAME = "FILE_NAME"
    }
}
