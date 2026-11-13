package me.raddatz.veyq.mobile.qr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * A scan: the screen model every app's camera view feeds (ADR 0129 decision 2, D3 of the QR CR). The host hands it
 * greyscale frames ([offer]) and says what its camera did ([camera]); the model decodes one frame at a time off the
 * host's thread and settles on the first code [accepts] maps to a [T] — the kit's `veyqkit:` parser, a pairing parser.
 * A code it does not accept is skipped silently and the scan goes on, as the web's `KitScanner` skips one; what the
 * code said stays here, and only [State.Found]'s [T] — what the caller's model made of it — leaves.
 *
 * A frame is the luminance plane (the Y of YUV, or a GRAY8 conversion) at most [MAX_EDGE] px on its long edge: the host
 * scales down, the model refuses larger. [close] when the view goes.
 */
class QrScan<T : Any>(
    private val accepts: (String) -> T?,
    private val decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    sealed interface State<out T> {
        /** The camera is being opened. */
        data object Starting : State<Nothing>

        /** This device has no camera; the caller offers the other way (ADR 0059 §1, a kit picked as a file). */
        data object NoCamera : State<Nothing>

        /** The camera was refused to the app; the caller says where to allow it. */
        data object Denied : State<Nothing>

        /** Frames are read. */
        data object Scanning : State<Nothing>

        /** A code was accepted as [value]; no frame is read after it. */
        data class Found<out T>(val value: T) : State<T>

        /** The scan was closed; no frame is read after it. */
        data object Closed : State<Nothing>
    }

    /** What the host's camera did. */
    enum class Camera {
        /** It runs: frames follow. */
        RUNNING,

        /** The device has none. */
        NONE,

        /** The app may not use it. */
        DENIED,
    }

    private val _state = MutableStateFlow<State<T>>(State.Starting)
    val state: StateFlow<State<T>> = _state.asStateFlow()
    val current: State<T> get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State<T>) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /** True while a frame is being decoded: a frame offered meanwhile is dropped. */
    private val decoding = MutableStateFlow(false)
    @Volatile
    private var job: Job? = null

    /**
     * True when [offer] would take a frame now: the scan is [State.Scanning] and no decode runs. A camera asks before it
     * copies a frame out of its own buffer, so a frame that would be dropped costs no copy. [offer] still decides.
     */
    val ready: Boolean get() = _state.value == State.Scanning && !decoding.value

    /** The host's camera state; once a code is found or the scan closed, it changes nothing. */
    fun camera(camera: Camera) {
        _state.update {
            if (it is State.Found || it == State.Closed) it
            else when (camera) {
                Camera.RUNNING -> State.Scanning
                Camera.NONE -> State.NoCamera
                Camera.DENIED -> State.Denied
            }
        }
    }

    /**
     * Offers a frame: taken (true) and decoded off the caller's thread while the scan is [State.Scanning] and no other
     * frame is being decoded, else dropped (false) — the host simply sends its next frame. A frame over [MAX_EDGE] px on
     * its long edge is refused with an IllegalArgumentException: the host scales down.
     */
    fun offer(frame: QrFrame): Boolean {
        require(maxOf(frame.width, frame.height) <= MAX_EDGE) { "a ${frame.width}x${frame.height} frame: at most $MAX_EDGE px on the long edge" }
        if (_state.value != State.Scanning) return false
        if (!decoding.compareAndSet(expect = false, update = true)) return false
        job = scope.launch {
            try {
                val found = decode(frame)?.let { text -> runCatching { accepts(text) }.getOrNull() }
                if (found != null) _state.update { if (it == State.Scanning) State.Found(found) else it }
            } finally {
                decoding.value = false
            }
        }
        return true
    }

    /** Ends the scan: a decode in flight is cancelled and nothing is found after. */
    fun close() {
        _state.value = State.Closed
        job?.cancel()
    }

    companion object {
        /** The longest frame edge read: a camera's 720p, which reads a code at arm's length, and no more work per frame. */
        const val MAX_EDGE = 1280
    }
}
