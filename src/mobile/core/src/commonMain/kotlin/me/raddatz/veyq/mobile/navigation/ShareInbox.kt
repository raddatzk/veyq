package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.blob.UploadSource

/**
 * Files another app shared to veyq, or dropped onto it (ADR 0138), from their arrival to where they went. They wait in the
 * pick mode until the node chosen takes them by its kind's [ShareAnswer]: into its editor ([take]: a Pot's new expense,
 * whose editor closed unsaved gives them back, [giveBack], and whose save ends the share, [saved]) or into its upload queue
 * ([queue]: a Memory's or a chapter's *Add here*, which ends the share). Each file is a source the platform reads a frame
 * at a time — where the system put it, or the app's one copy of it — and nothing reads one whole here.
 *
 * [release] hands a file back to its platform once nothing reads it again (a copy removed): a cancel's, a replaced share's,
 * the files a target left out, and an editor's at its save. A file queued is the upload queue's to hand back
 * (`MediaUploads`' `left`). Each platform reads the files and moves the shell to Home when they wait.
 */
class ShareInbox(private val release: (UploadSource) -> Unit = {}) {
    /** The files waiting for the place they go, in the order shared, and how many did not read at all. */
    class Incoming(val files: List<UploadSource>, val refused: Int)

    /** What a target took: the files it takes, and how many it left out by its kind's answer. */
    class Taken(val files: List<UploadSource>, val refused: Int)

    private val _waiting = MutableStateFlow<Incoming?>(null)
    /** The files waiting (the pick mode is on); null when none wait. */
    val waiting: StateFlow<Incoming?> = _waiting.asStateFlow()

    /** What an editor took, until its save or its close. */
    private var taken: Incoming? = null

    /**
     * Files arrive: they wait for the place the person picks. A share still waiting is replaced, its files let go; one an
     * editor took (and holds in memory) is no longer given back, so its close leaves the new one waiting.
     */
    fun receive(incoming: Incoming) {
        val before = listOfNotNull(_waiting.value, taken).flatMap { it.files }
        taken = null
        _waiting.value = incoming
        before.filter { it !in incoming.files }.forEach(::let)
    }

    /**
     * Whether a node at [place] whose kind answers [answer] can be chosen for what waits: its kind has an answer, the member
     * may put files there, and the answer takes at least one of them. Anything else the pick mode dims.
     */
    fun offers(answer: ShareAnswer?, place: ShareAnswer.Place): Boolean {
        if (answer == null || !answer.mayChoose(place)) return false
        val files = _waiting.value?.files ?: return false
        return answer.split(files, { it.type }, { it.name }, { it.size }).first.isNotEmpty()
    }

    /** The pick mode's cancel: the share ends, its files let go. */
    fun cancel() {
        val w = _waiting.value ?: return
        _waiting.value = null
        w.files.forEach(::let)
    }

    /**
     * The waiting files taken by the editor of the node opened as their place ([ShareAnswer.How.EDITOR]): those its
     * [answer] takes, in order. Null when none waits or it takes none. Until the editor's [saved] or [giveBack] nothing is
     * let go.
     */
    fun take(answer: ShareAnswer): Taken? {
        val w = _waiting.value ?: return null
        val (fit, out) = answer.split(w.files, { it.type }, { it.name }, { it.size })
        if (fit.isEmpty()) return null
        taken = w
        _waiting.value = null
        return Taken(fit, out.size)
    }

    /** The editor closed unsaved: the files wait again; what waits now, or null when nothing was taken. */
    fun giveBack(): Incoming? {
        val t = taken ?: return null
        taken = null
        _waiting.value = t
        return t
    }

    /** The editor the files went into was saved: the share is done, every file of it let go. */
    fun saved() {
        val t = taken ?: return
        taken = null
        t.files.forEach(::let)
    }

    /**
     * The waiting files queued into the node chosen ([ShareAnswer.How.QUEUE], its *Add here*): those its [answer] takes go
     * to [enqueue] (whose queue hands each back once done), the rest are let go, and the share ends — once: a second call
     * finds nothing waiting. Null when nothing waits, or the answer takes none of it (they keep waiting).
     */
    fun queue(answer: ShareAnswer, enqueue: (List<UploadSource>) -> Int): Taken? {
        val w = _waiting.value ?: return null
        val (fit, out) = answer.split(w.files, { it.type }, { it.name }, { it.size })
        if (fit.isEmpty()) return null
        _waiting.value = null
        out.forEach(::let)
        enqueue(fit)
        return Taken(fit, out.size)
    }

    private fun let(file: UploadSource) {
        runCatching { release(file) }
    }
}
