package me.raddatz.veyq.mobile.navigation

/**
 * Core's own rights over a node, one fail-closed answer for every kind (ADR 0154 decision 1: a gate is the core's): the
 * favourite heart (inbox #409, ADR 0068), the cover (inbox #411) and what a kept link offers (ADR 0120). The caller reads
 * its session into the inputs — on the web the `Access` and the registry (`@veyq/core-js/navigation`, held by
 * `contracts/platform/plans/v1-vectors.json`'s `rights`); a [NodeHeader.Rights] is what a header and a row then plan with.
 * The apps reach the same three through [NodeHeader.rights]' inputs (a member's pin set, the kind's cover, the page's link).
 */
object CoreRights {
    /**
     * The heart: a member's (the pin set is sealed under the member's key, ADR 0068 § 2: a guest has none), on a [node] —
     * the scope root is none — of a kind this client draws ([known]: a pin on a kind it cannot open would list a thing the
     * sidebar cannot open). A pending deletion keeps it: a pin is the member's preference, not a write to the tree.
     */
    fun favorite(member: Boolean, node: Boolean, known: Boolean): Boolean = member && node && known

    /**
     * Set or change a node's cover: a write to the node, so a member's whose keys are open ([keys]) on a space not pending
     * deletion ([readonly]), on a [node] of a kind that takes one ([takesCover]: known and opted in) whose application is
     * switched on ([applicationOn]), never in a guest's identity folder ([inIdentityFolder], ADR 0080 decision 11).
     */
    fun cover(
        member: Boolean, keys: Boolean, readonly: Boolean, node: Boolean, inIdentityFolder: Boolean, applicationOn: Boolean,
        takesCover: Boolean,
    ): Boolean = member && keys && !readonly && node && !inIdentityFolder && applicationOn && takesCover

    /** What a kept link offers: [open] its link (and hand its share's report to where the share lives), and the node's
     *  [rename], [move] and [delete] as core's node rights answered them. */
    class Bookmark(val open: Boolean, val rename: Boolean, val move: Boolean, val delete: Boolean)

    private val NO_BOOKMARK = Bookmark(open = false, rename = false, move = false, delete = false)

    /**
     * A kept link's rights: a member's only — no link serves one (ADR 0120 decision 7) —, on a [node]; it opens once the
     * member's keys are ([keys]), and its [rename], [move] and [delete] are the node's.
     */
    fun bookmark(member: Boolean, node: Boolean, keys: Boolean, rename: Boolean, move: Boolean, delete: Boolean): Bookmark =
        if (!member || !node) NO_BOOKMARK else Bookmark(keys, rename, move, delete)
}
