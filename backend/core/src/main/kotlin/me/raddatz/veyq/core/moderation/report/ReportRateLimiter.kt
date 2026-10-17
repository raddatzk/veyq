package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.ratelimit.TokenBucketCounter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.Locale

/**
 * The handler-side bounds of a report (ADR 0051, ADR 0093 § 5), beside the boundary's per-address (`IP`) and
 * per-account (`USER`) filters:
 *  - [allowShare] — a link's report budget (the `SHARE` axis of a guest's report, owner decision Q8): a guest has no
 *    account to count open reports against, and a link's guests can all sit behind one proxy address.
 *  - [allowReceipt] — how many receipts one address gets (owner decision Q6): the address is the reporter's to choose,
 *    so without this bound the report route would mail any address on request.
 * In-memory and per-instance like every limiter here; the open-report cap of a member is a database count, not this.
 */
@ApplicationScoped
class ReportRateLimiter(
    private val perShareMax: Int,
    private val receiptsPerAddress: Int,
    private val windowSeconds: Long,
) {
    @Inject
    constructor(config: VeyqConfig) : this(
        config.moderation().report().perShareMaxRequests(),
        config.moderation().report().receiptsPerAddress(),
        config.moderation().report().windowSeconds(),
    )

    private val shares = TokenBucketCounter()
    private val receipts = TokenBucketCounter()

    /** Spend one report token of [shareId]; false once its budget is exhausted. */
    fun allowShare(shareId: String): Boolean =
        shares.consume(shareId, System.nanoTime(), perShareMax.toLong(), windowSeconds) == null

    /** Spend one receipt token of [address] (case-folded); false once exhausted — the report stands, the mail is skipped. */
    fun allowReceipt(address: String): Boolean =
        receipts.consume(address.trim().lowercase(Locale.ROOT), System.nanoTime(), receiptsPerAddress.toLong(), windowSeconds) == null
}
