package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The tray over the app's one [UploadQueue] alone, no application's and no tenant's (ADR 0145 decision 6): what the tray
 * draws — the queue's batches, each file's state, its cancel and retry, a finished batch's close — and the tray's
 * acknowledge, a batch all in and none failed leaving by itself [UploadQueue.ACKNOWLEDGE_AFTER_MS] after it is
 * ([UploadQueue.State.clean]), run here on the model's clock, never by a view; one with a failure waits for its close.
 *
 * Signed out it is the whole tray: the sign-in and unlock screens draw it over a link guest's uploads left running
 * (inbox #1250), each app holding one for its process. Signed in, Depot's `DepotUploads` adds what a level's uploads say
 * and keeps one of its own; both acknowledging the same batch is harmless ([UploadQueue.acknowledge] of a hidden batch
 * changes nothing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadsTray(private val queue: UploadQueue, dispatcher: CoroutineDispatcher) {
    /** The tray over [queue], on [Dispatchers.Default]. */
    constructor(queue: UploadQueue) : this(queue, Dispatchers.Default)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private var acknowledging: Job? = null
    private var acknowledgeKey: List<Pair<String, Boolean>>? = null

    /** The queue as the tray shows it. */
    val state: StateFlow<UploadQueue.State> get() = queue.state
    val current: UploadQueue.State get() = queue.state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (UploadQueue.State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        scope.launch {
            queue.state.collect { q ->
                // keyed by the batches and whether each is in: a change starts the moment anew
                val key = q.batches.map { it.id to it.finished }
                if (key != acknowledgeKey) {
                    acknowledgeKey = key
                    acknowledging?.cancel()
                    val clean = q.clean
                    acknowledging = if (clean.isEmpty()) null else scope.launch {
                        delay(UploadQueue.ACKNOWLEDGE_AFTER_MS)
                        clean.forEach { queue.acknowledge(it.id) }
                    }
                }
            }
        }
    }

    fun cancel(id: String) = queue.cancel(id)
    fun retry(id: String) = queue.retry(id)
    fun cancelAll() = queue.cancelAll()

    /** The tray closed batch [batchId] (one with a failure stays until this). */
    fun acknowledge(batchId: String) = queue.acknowledge(batchId)

    /** The tray goes: nothing more is watched or acknowledged; the queue runs on. */
    fun close() = scope.cancel()
}
