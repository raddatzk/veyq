package me.raddatz.veyq.core.sharing

/**
 * Which node kinds a `WRITE_ONLY` guest may create inside its own identity folder (ADR 0080 decision 11), decided by
 * **the application that owns the link's root kind** — the same owner that decides whether that root takes identity
 * folders at all (Depot: a `DEPOT_DROPBOX`, whose guests create only `FILE`s; owner decision 2026-10-08, inbox #377 (c)).
 * Core's `ShareNodesHandler` asks it for every `WRITE_ONLY` create, in addition to [GuestNodeRules] (the identity
 * folder is a core `FOLDER`, whose owner lets a guest create a folder or a kind its application declares,
 * [GuestCreatableKinds]) and `NodeKindRules` (where a kind may live at all), never
 * instead of them. Keyed on the root kind, not on the container: the container is always core's own `FOLDER`, which
 * cannot name an application's kind, and what a drop box takes is the drop box's question.
 *
 * **Exactly one implementation per root kind, and none is the default** — the partition of [ShareRoots] and
 * [GuestNodeRules]. A root kind no implementation handles, or one two implementations claim, lets a `WRITE_ONLY` guest
 * create nothing (`illegal_scope`); so core alone, and an application that declares nothing, hand a write-only guest no
 * tree rights by accident.
 */
interface WriteOnlyGuestKinds {
    /** The kinds a `WRITE_ONLY` guest of a link rooted at a node of [rootKind] may create inside its identity folder, or
     *  null when this implementation does not handle [rootKind]. */
    fun creatable(rootKind: String): Set<String>?

    companion object {
        /** Whether [kind] is creatable under a link rooted at [rootKind]: the one implementation of [rules] that handles
         *  [rootKind] decides; none or several → nothing is. */
        fun allows(rules: List<WriteOnlyGuestKinds>, rootKind: String, kind: String): Boolean =
            kind in (rules.mapNotNull { it.creatable(rootKind) }.singleOrNull() ?: emptySet())
    }
}
