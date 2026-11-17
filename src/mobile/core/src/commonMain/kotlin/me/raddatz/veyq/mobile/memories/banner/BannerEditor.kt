package me.raddatz.veyq.mobile.memories.banner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.blob.UploadSource

/**
 * The banner's dialog (uc.memory.banner, ADR 0144 decision 5) — the web's `BannerDialog.vue` state and `useBannerEditor`
 * save, with no network and no key: the platform picks one file through the picker it already uses for uploads, the core
 * refuses or decodes it ([BannerWrite.pick]: an image above 40 MB before a byte is read, a picture or a video's first frame
 * that does not decode), hands the decoded picture to the dialog's two previews (wide 16:9 and phone 1:1, drawn as the
 * banner is, [BannerFrame]), and offers **Save banner** once a new file is picked; the save runs [save] with its progress,
 * and a cancel (the caller cancels the coroutine [save] runs in) leaves the old banner as it was. **Remove banner** is one
 * write. A peer's change between the dialog's open and its save is [Said.STALE]: nothing written, the page shows the
 * peer's.
 */
class BannerEditor internal constructor(
    /** Whether the Memory has a banner now (Remove is offered). */
    hasBanner: Boolean,
    private val pictures: BannerWrite.Pictures,
    private val write: suspend (BannerWrite.Picked, progress: (Double) -> Unit) -> BannerWrite.Outcome,
    private val removeBanner: suspend () -> BannerWrite.Outcome,
) {
    /** What the dialog says: a pick refused, or a save that did not go. */
    enum class Said { TOO_LARGE, UNREADABLE, VIDEO_UNREADABLE, NOT_MEDIA, STALE, BUSY, FAILED }

    data class State(
        val hasBanner: Boolean,
        /** A file is being decoded. */
        val picking: Boolean = false,
        /** The file picked and decoded: its previews' picture; null until one is. */
        val picked: BannerWrite.Picked? = null,
        /** A save or a removal runs; [progress] the fraction of the upload sent (null: a removal, or not started). */
        val saving: Boolean = false,
        val progress: Double? = null,
        val said: Said? = null,
        /** The save or removal went: the dialog closes. */
        val done: Boolean = false,
    ) {
        /** **Save banner** appears once a new file is picked. */
        val canSave: Boolean get() = picked != null && !saving && !picking && !done
        val canRemove: Boolean get() = hasBanner && !saving && !picking && !done
    }

    private val _state = MutableStateFlow(State(hasBanner))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /** [source] picked: refused with what the dialog says, or decoded for the previews (replacing an earlier pick). */
    suspend fun pick(source: UploadSource) {
        if (current.saving || current.done) return
        _state.update { it.copy(picking = true, said = null) }
        try {
            val picked = BannerWrite.pick(source, pictures)
            _state.update { it.copy(picking = false, picked = picked) }
        } catch (e: BannerWrite.Refused) {
            _state.update { it.copy(picking = false, picked = null, said = said(e.code)) }
        } catch (e: CancellationException) {
            _state.update { it.copy(picking = false) }
            throw e
        }
    }

    /** The picked file made the banner; what it came to, or null where nothing was picked. */
    suspend fun save(): BannerWrite.Outcome? {
        val picked = current.picked?.takeIf { current.canSave } ?: return null
        return run(0.0) { write(picked) { f -> _state.update { it.copy(progress = f.coerceIn(0.0, 1.0)) } } }
    }

    /** The banner removed; what it came to, or null where there is none. */
    suspend fun remove(): BannerWrite.Outcome? {
        if (!current.canRemove) return null
        return run(null) { removeBanner() }
    }

    private suspend fun run(progress: Double?, op: suspend () -> BannerWrite.Outcome): BannerWrite.Outcome {
        _state.update { it.copy(saving = true, progress = progress, said = null) }
        var out: BannerWrite.Outcome? = null
        try {
            out = try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BannerWrite.Outcome.Refused(null)
            }
            return out
        } finally {
            // a cancel (no outcome) says nothing: the old banner stays, the dialog can pick or save again
            val ended = out
            _state.update {
                val idle = it.copy(saving = false, progress = null)
                when (ended) {
                    null -> idle
                    BannerWrite.Outcome.Done -> idle.copy(done = true)
                    BannerWrite.Outcome.Stale -> idle.copy(said = Said.STALE)
                    BannerWrite.Outcome.Busy -> idle.copy(said = Said.BUSY)
                    is BannerWrite.Outcome.Refused -> idle.copy(said = Said.FAILED)
                }
            }
        }
    }

    private fun said(code: String): Said = when (code) {
        BannerWrite.TOO_LARGE -> Said.TOO_LARGE
        BannerWrite.UNREADABLE -> Said.UNREADABLE
        BannerWrite.VIDEO_UNREADABLE -> Said.VIDEO_UNREADABLE
        BannerWrite.NOT_MEDIA -> Said.NOT_MEDIA
        else -> Said.FAILED
    }
}
