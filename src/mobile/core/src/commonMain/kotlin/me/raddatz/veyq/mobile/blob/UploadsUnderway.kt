package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every upload under way in this process, by the queue it runs in ([UploadQueue.underwayKey]): what the platforms'
 * background keeps the process running for while the app is left (ADR 0145 decision 1 — Android's worker promoted to a
 * `dataSync` foreground service with its notification, iOS's continued-processing task and background time) and what
 * that shows as progress. A queue is named while one of its files waits or runs, and gone once none does: a failed file
 * waiting for the person's retry holds nothing. Counts and sizes only, never a name.
 */
object UploadsUnderway {
    /** [files] waiting or running; [done] bytes of the [total] the queue's batches not yet acknowledged set out to send. */
    data class Progress(val files: Int, val done: Long, val total: Long)

    private val _uploads = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Each queue with files under way: empty while none is. */
    val uploads: StateFlow<Map<String, Progress>> = _uploads.asStateFlow()

    /** What every queue's uploads come to; null while none is under way. */
    fun total(uploads: Map<String, Progress> = _uploads.value): Progress? =
        if (uploads.isEmpty()) null else Progress(uploads.values.sumOf { it.files }, uploads.values.sumOf { it.done }, uploads.values.sumOf { it.total })

    /** A host that cannot collect a flow (Swift): [listener] told the total at once and at each change, until [Watch.stop]. */
    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun watch(listener: (Progress?) -> Unit): Watch = Watch(watching.launch { uploads.collect { listener(total(it)) } })

    internal fun set(queue: String, progress: Progress?) = _uploads.update {
        if (progress == null) it - queue else if (it[queue] == progress) it else it + (queue to progress)
    }
}
