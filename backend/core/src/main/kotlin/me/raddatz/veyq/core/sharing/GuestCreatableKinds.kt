package me.raddatz.veyq.core.sharing

/**
 * Which of an application's kinds a `READ_WRITE` guest may create under a `FOLDER` (ADR 0080 decision 6, owner decision
 * 2026-10-14): core's [FolderGuestNodeRules] lets a guest create a `FOLDER`, and any other kind **only when its own
 * application declares it here** — Files declares `FILE` (a contributor's upload lands in one), Memories declares
 * nothing, so a guest creates no Memory, no drop box and no node of an application that says nothing. Core names no
 * application kind (ADR 0079): the declaration is the application's, and core only reads it.
 *
 * **Fails closed.** With no implementation a guest creates folders only (`CoreAloneSliceTest`). A kind two
 * implementations declare is creatable by nobody ([allows]) — and it **fails startup** ([requireDisjoint], run by
 * [FolderGuestNodeRules] on `StartupEvent`): two applications claiming one kind is a composition error, never a
 * question for CDI bean order.
 *
 * It narrows only a create inside a folder: moving and renaming inside a folder stay any kind's ([FolderGuestNodeRules]),
 * and where a kind may live at all is still `NodeKindRules`', asked after it.
 */
interface GuestCreatableKinds {
    /** The kinds of this application a `READ_WRITE` guest may create inside a `FOLDER`. */
    fun kinds(): Set<String>

    companion object {
        /** Whether exactly one of [declared] names [kind]; none or several → no. */
        fun allows(declared: List<GuestCreatableKinds>, kind: String): Boolean = declared.count { kind in it.kinds() } == 1

        /** Every kind more than one declaration names, with the declarers that name it (keyed by their names). */
        fun overlaps(declared: Map<String, Set<String>>): Map<String, List<String>> =
            declared.flatMap { (declarer, kinds) -> kinds.map { it to declarer } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .mapValues { (_, declarers) -> declarers.sorted() }
                .toSortedMap()

        /** Throws — and so fails startup — when two declarations name one kind; [declared] maps a declarer's name to its kinds. */
        fun requireDisjoint(declared: Map<String, Set<String>>) {
            val overlaps = overlaps(declared)
            check(overlaps.isEmpty()) {
                "GuestCreatableKinds: a kind is declared guest-creatable by more than one application, so veyq does not " +
                    "start (ADR 0080 decision 6): " + overlaps.entries.joinToString("; ") { (kind, by) -> "$kind by ${by.joinToString()}" }
            }
        }
    }
}
