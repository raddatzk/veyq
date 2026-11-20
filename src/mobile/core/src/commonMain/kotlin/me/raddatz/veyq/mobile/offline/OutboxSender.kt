package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Unreachable

/**
 * One run of the outbox (ADR 0153 decision 4): after a reconnect, and when an account opened online finds
 * writes waiting ([me.raddatz.veyq.mobile.session.VeyqAccount.sendQueued]). Oldest first, one at a time, each through
 * its [Queued] value's sender — the online path, never a copy of it; a write that waits for another ([QueuedWrite.after])
 * goes once that one is sent, and stays while it is held or refused, so a held write holds only what depends on it.
 *
 * Each ends as [QueuedOutcome]: sent → gone from the outbox; held or refused → kept with its reason for the person (never
 * tried again by a run: the person opens or discards it); waiting → kept as it was. Nothing is retried for ever: the
 * server not reached (a request that did not get there turns the account offline) ends the run with the write still
 * waiting, for the next reconnect; any other failure is refused.
 */
class OutboxSender internal constructor(
    private val outbox: Outbox,
    /** The tenant a write is sent in, opened once per run; null where the account no longer belongs to it. */
    private val tenantOf: suspend (tenantId: String) -> TenantSession?,
    private val online: () -> Boolean,
    private val senderOf: (Queued) -> QueuedSender,
    /**
     * Whether the run may begin another write: a scheduled run's time left (`BackgroundRun`, ADR 0153 decision 8). Asked
     * only between writes — a write begun is finished and its outcome kept —, so what is left waits for the next run.
     */
    private val go: () -> Boolean = { true },
) {
    /** What a run came to: how many were sent, held, refused, and still wait. */
    data class Report(val sent: Int, val held: Int, val refused: Int, val waiting: Int)

    internal suspend fun run(): Report {
        var sent = 0
        var held = 0
        var refused = 0
        val tenants = HashMap<String, TenantSession?>()
        try {
            for (write in outbox.writes()) {
                if (!online() || !go()) break
                if (write.state != QueuedWrite.State.WAITING) continue
                if (write.after != null && outbox.write(write.after) != null) continue
                val outcome = try {
                    val q = Queued.of(write.op)
                    val tenant = if (write.tenant in tenants) tenants[write.tenant] else tenantOf(write.tenant).also { tenants[write.tenant] = it }
                    when {
                        q == null -> QueuedOutcome.Refused("unknown")
                        tenant == null -> QueuedOutcome.Refused("tenant_gone")
                        else -> senderOf(q).send(tenant, write)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Unreachable) {
                    QueuedOutcome.Waiting
                } catch (e: Exception) {
                    if (online()) QueuedOutcome.Refused("failed") else QueuedOutcome.Waiting
                }
                // a write that failed because the server went away is no refusal: it waits, and the run ends
                if (outcome !is QueuedOutcome.Sent && !online()) break
                when (outcome) {
                    QueuedOutcome.Sent -> { outbox.remove(write.id); sent++ }
                    is QueuedOutcome.Held -> { outbox.update(write.copy(state = QueuedWrite.State.HELD, reason = outcome.reason)); held++ }
                    is QueuedOutcome.Refused -> { outbox.update(write.copy(state = QueuedWrite.State.REFUSED, reason = outcome.reason)); refused++ }
                    QueuedOutcome.Waiting -> Unit
                }
            }
        } finally {
            tenants.values.forEach { it?.close() }
        }
        val waiting = outbox.writes().count { it.state == QueuedWrite.State.WAITING }
        return Report(sent, held, refused, waiting)
    }
}
