package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The app's one upload queue (the web's `core/blob/queue.ts`, ported rule for rule): batches of jobs, each one an
 * application supplies, run ONE AT A TIME in enqueue order across batches. It knows nothing of what a job uploads or
 * where: the application's job closes over its own target and keeps what it later reads back (a Depot name collision,
 * a replace's current content). Operator-blind is the job's concern alone; the queue never sees a byte or a key.
 *
 * Its rules: progress is a fraction of the job (no speed, no ETA); a job is [Status.PREPARING] until its first progress,
 * then [Status.UPLOADING]; a transient failure is retried once by itself, a hard one waits for [retry] — except a refusal
 * no retry can help ([QUOTA_EXCEEDED], [APPLICATION_OFF], or one the job declares final), which fails at once and offers
 * none; a cancel settles [Status.CANCELLED], never failed; a job given up ([Status.FAILED] or cancelled) is
 * [Job.abandon]ed once, never between the automatic retry's attempts. While one job is on the wire, the next job with an
 * on-device stage is [Job.prepare]d ahead (the web's inbox #220): one ahead, never a byte sent by it.
 *
 * Its state is [state] (and [watch], for Swift): a snapshot per change. Every rule runs on one serial lane of
 * [dispatcher]; a job's work runs on [dispatcher] itself. The hooks of [enqueue] are called on that lane, never on the
 * main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadQueue(dispatcher: CoroutineDispatcher) {
    /** The app's queue, on [Dispatchers.Default]. */
    constructor() : this(Dispatchers.Default)

    enum class Status { QUEUED, PREPARING, UPLOADING, DONE, FAILED, CANCELLED }

    /** A job's refusal, by its code (`quota_exceeded`, an application's own `exists`, …): what [Item.error] says. */
    class Failed(val code: String) : Exception(code)

    /** One unit of upload work, an application's. */
    interface Job {
        /** What the tray names it by. */
        val name: String
        val sizeBytes: Long

        /** Whether [prepare] has an on-device stage worth running ahead. */
        val prepares: Boolean get() = false

        /**
         * The job's on-device stage (a medium's tiers and metadata), run AHEAD while the job before it is on the wire: once
         * at most from the queue, and again by the job's own [run], which awaits the same work — the job keeps what it
         * prepared. It sends nothing and writes nothing, so [abandon] never has anything of it to undo.
         */
        suspend fun prepare() {}

        /**
         * Does the work, telling [progress] the fraction done (0..1; its first call ends the on-device stage); may run
         * again — the automatic retry, the person's retry. Throws [Failed] with a code for a refusal; anything else thrown
         * is `upload_failed`, a cancel cancels it.
         */
        suspend fun run(progress: (Double) -> Unit)

        /** Whether [code] is a refusal no retry of this job can change (Depot: a taken name, a replaced file). */
        fun isFinal(code: String): Boolean = false

        /** Undoes what a run left half-done that no one will finish, once the job is given up; best effort. */
        suspend fun abandon() {}
    }

    /** A job in the queue as it stands. [job] is the application's own, to read back what it kept. */
    class Item(
        val id: String,
        val name: String,
        val sizeBytes: Long,
        val status: Status,
        val progress: Double,
        val error: String?,
        val final: Boolean,
        val attempts: Int,
        val job: Job,
    ) {
        val active: Boolean get() = status == Status.QUEUED || status == Status.PREPARING || status == Status.UPLOADING

        /** A failed item the person may try again: not one no retry helps. */
        val retryable: Boolean get() = status == Status.FAILED && isRetryable(error, final)
    }

    /** One enqueue: its [label] (where the files go), the caller's [key] for that place, its items, whether all settled. */
    class Batch(val id: String, val label: String, val key: String?, val items: List<Item>, val finished: Boolean) {
        /** The batch's done part by size, its cancelled items left out. */
        val progress: Double
            get() {
                val counted = items.filter { it.status != Status.CANCELLED }
                val total = counted.sumOf { it.sizeBytes.coerceAtLeast(1) }.toDouble()
                return if (total == 0.0) 1.0 else counted.sumOf { it.sizeBytes.coerceAtLeast(1) * it.progress } / total
            }
    }

    /** The batches not yet acknowledged, in enqueue order. */
    class State(val batches: List<Batch>) {
        /** How many items still wait or run. */
        val active: Int get() = batches.sumOf { b -> b.items.count { it.active } }

        /** How many items failed. */
        val failed: Int get() = batches.sumOf { b -> b.items.count { it.status == Status.FAILED } }

        /** The whole's progress (0..1), each item weighted by its size (at least a byte), cancelled ones left out; 1 with none. */
        val progress: Double
            get() {
                val items = batches.flatMap { it.items }.filter { it.status != Status.CANCELLED }
                val total = items.sumOf { it.sizeBytes.coerceAtLeast(1).toDouble() }
                return if (total == 0.0) 1.0 else items.sumOf { it.sizeBytes.coerceAtLeast(1) * it.progress } / total
            }

        /**
         * The batches the tray acknowledges by itself, [ACKNOWLEDGE_AFTER_MS] after they are: all in and none failed, once
         * nothing runs. One with a failure waits for the person to close it.
         */
        val clean: List<Batch>
            get() = if (active > 0) emptyList() else batches.filter { b -> b.finished && b.items.none { it.status == Status.FAILED } }
    }

    companion object {
        /** One automatic retry on a transient failure. */
        const val MAX_ATTEMPTS = 2

        /** How long a batch all in stays in the tray before it is acknowledged by itself. */
        const val ACKNOWLEDGE_AFTER_MS = 3_000L

        /** The tenant or the link has no room left (ADR 0073 §6): no retry helps. */
        const val QUOTA_EXCEEDED = "quota_exceeded"

        /** The application is switched off here (`422 capability_disabled`): no retry helps until it is switched on. */
        const val APPLICATION_OFF = "capability_disabled"

        /** What a run that threw anything but [Failed] failed with. */
        const val UPLOAD_FAILED = "upload_failed"

        fun isRetryable(error: String?, final: Boolean): Boolean = error != QUOTA_EXCEEDED && error != APPLICATION_OFF && !final
    }

    private val worker = dispatcher
    private val lane = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + lane)

    private class Entry(val id: String, val job: Job) {
        var status = Status.QUEUED
        var progress = 0.0
        var error: String? = null
        var final = false
        var attempts = 0
        var cancelRequested = false
        var work: kotlinx.coroutines.Job? = null
        fun item() = Item(id, job.name, job.sizeBytes, status, progress, error, final, attempts, job)
        val settled get() = status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED
    }

    private class Group(
        val id: String, val label: String, val key: String?, val seq: Long, val entries: MutableList<Entry>,
        val onSettled: ((Item) -> Unit)?, val onFinished: ((Batch) -> Unit)?,
    ) {
        var finished = false
        var acknowledged = false
        fun batch() = Batch(id, label, key, entries.map { it.item() }, finished)
    }

    private val groups = ArrayList<Group>()
    private var seq = 0L
    private var running = false
    private var ahead: Entry? = null

    private val _state = MutableStateFlow(State(emptyList()))
    val state: StateFlow<State> = _state.asStateFlow()

    /** A watch of [state] for a platform without flows: stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(worker).launch { state.collect { listener(it) } })

    private fun publish() {
        _state.value = State(groups.filter { !it.acknowledged }.map { it.batch() })
    }

    private fun newId() = Random.nextLong().toULong().toString(16)

    /**
     * Queues [jobs] as one batch labelled [label] (where they go), [key] the caller's name for that place. [onSettled] is
     * told each item as it settles (done, failed or cancelled), [onFinished] the batch once all its items settled.
     */
    fun enqueue(jobs: List<Job>, label: String, key: String?, onSettled: ((Item) -> Unit)?, onFinished: ((Batch) -> Unit)?) {
        if (jobs.isEmpty()) return
        val entries = jobs.map { Entry(newId(), it) }.toMutableList()
        val id = newId()
        scope.launch {
            groups += Group(id, label.ifEmpty { "${entries.size}" }, key, seq++, entries, onSettled, onFinished)
            publish()
            schedule()
        }
    }

    /** The first item in batch order that [accept]s; by default the next one to run — queued, or the one prepared ahead. */
    private fun next(accept: (Entry) -> Boolean = { it.status == Status.QUEUED || it === ahead }): Entry? {
        for (g in groups.sortedBy { it.seq }) g.entries.firstOrNull(accept)?.let { return it }
        return null
    }

    private fun groupOf(e: Entry) = groups.firstOrNull { e in it.entries }

    private fun schedule() {
        if (running) return
        val e = next() ?: return
        running = true
        scope.launch {
            try {
                runOne(e)
            } finally {
                running = false
                schedule()
            }
        }
    }

    /** Starts the next job's on-device stage while the running one is on the wire: one ahead, only a job that has one. */
    private fun prepareAhead() {
        if (ahead != null) return
        val e = next { it.status == Status.QUEUED && it.job.prepares } ?: return
        ahead = e
        e.status = Status.PREPARING
        e.progress = 0.0
        // how it settles is the job's: its run awaits the same work and meets any failure there
        e.work = CoroutineScope(worker).launch { runCatching { e.job.prepare() } }
        publish()
    }

    private suspend fun runOne(e: Entry) {
        val group = groupOf(e) ?: return
        if (e === ahead) ahead = null // its run starts now, on what it prepared
        e.status = Status.PREPARING
        e.progress = 0.0
        e.attempts += 1
        val attempt = e.attempts
        publish()
        val work = CoroutineScope(worker).async {
            e.job.run { p ->
                scope.launch {
                    if (e.attempts != attempt || e.settled || e.status == Status.QUEUED) return@launch
                    if (e.status == Status.PREPARING) {
                        e.status = Status.UPLOADING // first progress: the network phase began
                        prepareAhead() // and this job's own on-device stage is over: the next one's may start
                    }
                    e.progress = p.coerceIn(0.0, 1.0)
                    publish()
                }
            }
        }
        e.work = work
        val thrown = try {
            work.await()
            null
        } catch (t: Throwable) {
            t
        }
        if (thrown == null && !e.cancelRequested) {
            e.status = Status.DONE
            e.progress = 1.0
        } else if (e.cancelRequested || thrown is CancellationException) {
            e.status = Status.CANCELLED
        } else {
            val code = (thrown as? Failed)?.code ?: UPLOAD_FAILED
            val final = e.job.isFinal(code)
            if (e.attempts < MAX_ATTEMPTS && isRetryable(code, final)) {
                e.status = Status.QUEUED // one automatic retry on a transient failure
            } else {
                e.status = Status.FAILED
                e.error = code
                e.final = final
            }
        }
        e.work = null
        if (e.status != Status.QUEUED) {
            if (e.status != Status.DONE) abandon(e.job)
            settled(group, e)
        }
        finish(group)
        publish()
    }

    private suspend fun abandon(job: Job) {
        try {
            job.abandon()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // best effort: the job's own leftovers stay as they are
        }
    }

    private fun settled(g: Group, e: Entry) {
        runCatching { g.onSettled?.invoke(e.item()) } // a hook that throws breaks no rule here
    }

    private fun finish(g: Group) {
        if (!g.finished && g.entries.all { it.settled }) {
            g.finished = true
            runCatching { g.onFinished?.invoke(g.batch()) } // a hook that throws breaks no rule here
        }
    }

    /** Cancels item [id]: a queued or prepared one at once, a running one at its next suspension. */
    fun cancel(id: String) {
        scope.launch {
            for (g in groups) {
                val e = g.entries.firstOrNull { it.id == id } ?: continue
                when {
                    e === ahead -> {
                        // prepared ahead, not yet the runner's: settled now rather than after the job on the wire
                        ahead = null
                        e.cancelRequested = true
                        e.work?.cancel()
                        e.work = null
                        e.status = Status.CANCELLED
                        abandon(e.job)
                        settled(g, e)
                    }
                    e.status == Status.QUEUED -> {
                        e.status = Status.CANCELLED
                        abandon(e.job) // a re-queued retry may have left something of its earlier run
                        settled(g, e)
                    }
                    e.status == Status.PREPARING || e.status == Status.UPLOADING -> {
                        e.cancelRequested = true
                        e.work?.cancel() // the runner settles it cancelled
                    }
                    else -> {}
                }
                finish(g)
                publish()
                return@launch
            }
        }
    }

    fun cancelBatch(batchId: String) {
        scope.launch { groups.firstOrNull { it.id == batchId }?.entries?.forEach { cancel(it.id) } }
    }

    fun cancelAll() {
        scope.launch { groups.forEach { g -> g.entries.forEach { cancel(it.id) } } }
    }

    /** Tries failed item [id] again, its attempts counted afresh; an item that is not failed is left alone. */
    fun retry(id: String) {
        scope.launch {
            for (g in groups) {
                val e = g.entries.firstOrNull { it.id == id } ?: continue
                if (e.status != Status.FAILED) return@launch
                e.status = Status.QUEUED
                e.attempts = 0
                e.error = null
                e.final = false
                e.cancelRequested = false
                g.finished = false
                publish()
                schedule()
                return@launch
            }
        }
    }

    /** Drops settled item [id] (an in-flight one is cancelled first); its batch goes with its last item. */
    fun remove(id: String) {
        scope.launch {
            for (g in groups) {
                val e = g.entries.firstOrNull { it.id == id } ?: continue
                if (!e.settled) return@launch
                g.entries.remove(e)
                if (g.entries.isEmpty()) groups.remove(g)
                publish()
                return@launch
            }
        }
    }

    /** Hides batch [batchId] from [state] (the tray closed it). */
    fun acknowledge(batchId: String) {
        scope.launch {
            groups.firstOrNull { it.id == batchId }?.acknowledged = true
            groups.removeAll { it.acknowledged && it.finished }
            publish()
        }
    }

    /** Cancels everything and empties the queue (a sign-out, a switch of tenant). */
    fun clear() {
        scope.launch {
            for (g in groups) for (e in g.entries) {
                e.cancelRequested = true
                e.work?.cancel()
            }
            groups.clear()
            ahead = null
            publish()
        }
    }
}
