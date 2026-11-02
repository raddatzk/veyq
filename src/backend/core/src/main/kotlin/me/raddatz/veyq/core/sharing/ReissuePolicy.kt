package me.raddatz.veyq.core.sharing

/**
 * Whether a share's link may be held by someone who has since left its scope, so it can come back only as a
 * **re-issue** — a new id and a new `K_share` — never by re-activation (ADR 0075 point 4). Any one of:
 *  - a bulk switch-off turned it off ([reissueOnly], point 5): "a link may have leaked", whoever made it;
 *  - its **owner** is no longer a member: they hold their own link;
 *  - it carries a recovery copy ([mintedEpoch] non-null: the epoch it was minted at with one) that predates the scope's
 *    current state — minted **below the current epoch**, or **before a member removal** ([removalsAtMint] below
 *    [removals]): every member at the time could rebuild the link from the copy, one removed since included. The
 *    copy's epoch is the mint's: a rotation re-wraps the copy's value key and leaves the copy and its epoch (ADR 0115
 *    decision 2), and a re-issue mints a new row. The counter carries what the epoch cannot: a tenant never
 *    rotates on a removal (ADR 0025), and a tenant removal only *queues* a co-shared group's rotation. The epoch half
 *    stays beside it for its one "false positive" — a key rotation after a compromise (ADR 0060 §5), when a stolen
 *    device could rebuild every link as well as a departed member could.
 * A share with no copy was never rebuildable by another member, so only the first two judge it.
 *
 * The one predicate behind both readers — the re-activation refusal and the manage list's `reissueRequired` — so
 * the panel's mark and the refusal cannot disagree.
 */
object ReissuePolicy {
    fun required(
        reissueOnly: Boolean, ownerIsMember: Boolean, mintedEpoch: Int?, currentEpoch: Int, removalsAtMint: Int, removals: Int,
    ): Boolean = reissueOnly || !ownerIsMember || (mintedEpoch != null && (mintedEpoch < currentEpoch || removalsAtMint < removals))
}
