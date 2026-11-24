package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.UploadQueue

/**
 * What keeps a left link's keys going while its guest's uploads do (inbox #1016; ADR 0145 § A guest's uploads): the jobs
 * one [GuestView] put on the app's queue — each as a batch that is no account's, so a sign-out or a switch spares it — and,
 * once the view is left, the release of its keys and client deferred until none of those jobs waits or runs and no settle
 * hook of the view still runs ([busy]). Held in memory only: nothing of a job, a key or the session is written anywhere.
 * Released, each failed job of the view is made final (its keys are gone, a retry could not send).
 */
internal class GuestUploadHold(private val queue: UploadQueue?) {
    /** Every job this view queued. */
    private val jobs = MutableStateFlow<Set<UploadQueue.Job>>(emptySet())

    /** The jobs queued whose settle the queue has not told yet (one enqueued may not show in the queue's state yet). */
    private val unsettled = MutableStateFlow<Set<UploadQueue.Job>>(emptySet())

    /** Settle hooks of the view still running (a folder upload's take-back of its empty folders). */
    private val hooks = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var releasing = false

    /** [list] queued on the app's queue as a guest's batch ([UploadQueue.enqueueSpared]); false without a queue. */
    fun enqueue(
        list: List<UploadQueue.Job>, label: String, key: String?,
        onSettled: ((UploadQueue.Item) -> Unit)?, onFinished: ((UploadQueue.Batch) -> Unit)?,
    ): Boolean {
        val q = queue ?: return false
        if (list.isEmpty()) return true
        jobs.update { it + list }
        unsettled.update { it + list }
        q.enqueueSpared(list, label, key, { item ->
            unsettled.update { it - item.job }
            onSettled?.invoke(item)
        }, onFinished)
        return true
    }

    /** [block] counted as a settle hook while it runs: the release waits for it. */
    suspend fun <T> busy(block: suspend () -> T): T {
        hooks.update { it + 1 }
        try {
            return block()
        } finally {
            hooks.update { it - 1 }
        }
    }

    /** Whether one of the view's jobs waits or runs, or a settle hook runs, now. */
    val underway: Boolean get() = !idle(queue?.state?.value, unsettled.value, hooks.value)

    private fun idle(q: UploadQueue.State?, waiting: Set<UploadQueue.Job>, running: Int): Boolean {
        if (running > 0 || waiting.isNotEmpty()) return false
        val own = jobs.value
        return q == null || q.batches.none { b -> b.items.any { it.active && it.job in own } }
    }

    /**
     * [release] run once none of the view's jobs waits or runs and no hook does — at once where none ever did —, then the
     * view's failed jobs made final. Called once, at the view's close.
     */
    fun releaseWhenIdle(release: () -> Unit) {
        if (releasing) return
        releasing = true
        val q = queue
        if (q == null || !underway) {
            finish(release)
            return
        }
        scope.launch {
            combine(q.state, unsettled, hooks) { s, w, h -> idle(s, w, h) }.first { it }
            finish(release)
        }
    }

    private fun finish(release: () -> Unit) {
        try {
            release()
        } finally {
            queue?.endRetries(jobs.value)
            scope.cancel()
        }
    }
}
