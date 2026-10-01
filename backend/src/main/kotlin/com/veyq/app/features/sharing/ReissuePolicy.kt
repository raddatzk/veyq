package com.veyq.app.features.sharing

/**
 * Whether a share's link may be held by someone who has since left its scope, so it can come back only as a
 * **re-issue** — a new id and a new `K_share` — never by re-activation (ADR 0075 Decision 4). Two ways, either one:
 *  - its **owner** is no longer a member: they hold their own link;
 *  - its recovery copy is sealed **below the scope's current epoch**: every member of that epoch could rebuild the
 *    link from the copy, a member removed since included (a removal rotates the epoch, ADR 0024). A share with no
 *    copy ([copyEpoch] null) was never rebuildable, so only the owner half judges it.
 * The epoch half has one false positive — a key rotation after a compromise moves the epoch too (ADR 0060 §5) —
 * which is why the manage panel words its hint as "made before the group's key last changed".
 */
object ReissuePolicy {
    fun required(ownerIsMember: Boolean, copyEpoch: Int?, currentEpoch: Int): Boolean =
        !ownerIsMember || (copyEpoch != null && copyEpoch < currentEpoch)
}
