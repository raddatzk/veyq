package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.navigation.NameAnswer
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * The core's one naming flow of a writing link's guest (ADR 0154 decision 1; the web's `requestGuest`): a write offered to
 * a guest not named yet — *Add entry*, Replace, a level's New, a gallery's *Add photos*, a WRITE_ONLY page's upload — asks
 * the guest's name at the write, never before and never by a platform's own rule. A write calls [request]; while it
 * waits, [asking] is true and the platform draws its name dialog, answering [name] (the dialog's save, a [NameAnswer]) or
 * [decline] (its cancel). One question at a time: a second write waits on the same answer. Which identity a name makes
 * is the link's role's, decided here alone: a READ_WRITE guest's (`nameGuest`), a WRITE_ONLY uploader's with its own
 * `K_up` (`nameUploader`) — no platform and no bridge branches on the role (#1042).
 */
class GuestNaming internal constructor(private val access: GuestAccess) {
    /**
     * Whether the screen offers the guest line (a READ_WRITE link): the platforms show "You are here as …" or the way to
     * name itself above the page only where it does — never by reading the link's role themselves. A WRITE_ONLY page says
     * who sent in its own body; its writes are still asked through [request].
     */
    val offered: Boolean get() = access.role == GuestAccess.READ_WRITE

    private val _asking = MutableStateFlow(false)

    /** Whether a write waits for the guest's name: the platform shows the name dialog while it does. */
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    private var waiting: CompletableDeferred<GuestSession?>? = null
    private val one = Mutex()

    /**
     * The guest's session for a write: at once where it named itself; on a READ link null; else the name asked
     * ([asking]) and awaited — null where the guest declined.
     */
    suspend fun request(): GuestSession? {
        access.session.value?.let { return it }
        if (!access.writes) return null
        val answer = one.withLock {
            access.session.value?.let { return it }
            waiting ?: CompletableDeferred<GuestSession?>().also { waiting = it; _asking.value = true }
        }
        return answer.await()
    }

    /**
     * The name dialog's save: an empty name is a [NameAnswer.problem] (the dialog asks again); a name the server did not
     * take is said ([NameAnswer.notice], the write that asked goes on unnamed — declined); once named, the waiting write
     * goes on as the guest.
     */
    suspend fun name(displayName: String): NameAnswer {
        val n = displayName.trim()
        if (n.isEmpty()) return NameAnswer.problem(Notice(Msg.SHARE_GUEST_NAME_EMPTY))
        val session = runCatching { if (access.role == GuestAccess.WRITE_ONLY) access.nameUploader(n) else access.nameGuest(n) }.getOrNull()
        answer(session)
        return if (session != null) NameAnswer.DONE else NameAnswer.said(Notice(Msg.SHARE_JOIN_FAILED))
    }

    /** The name dialog's cancel: the waiting write is not made. */
    suspend fun decline() = answer(null)

    private suspend fun answer(session: GuestSession?) {
        val w = one.withLock { waiting.also { waiting = null; _asking.value = false } }
        w?.complete(session)
    }
}
