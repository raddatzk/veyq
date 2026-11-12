package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * "New pot" (the web's `PotCreateDialog.vue`, ADR 0127 decision 2): the space it goes to (the one shown, else the
 * first), the base currency it starts with (the person's language's), its first people — names typed and members of that
 * space, read again when the space changes (a member of another space goes) —, and the create: in the folder shown where
 * the sheet creates in the space shown ([PotCreate.parentIn]), else at the space's root. The name arrives with [create]
 * and [canCreate], so no draft of it lives here. Every app draws [state] and decides nothing.
 *
 * Opened by [PotModel.createSheet]; a test opens it over a [Door] it plays ([open]).
 */
class PotCreateSheet internal constructor(
    private val door: Door,
    /** The shell's place when the sheet opened: where New was pressed. */
    private val place: Shell,
    first: State,
    parent: CoroutineScope,
    /** Where the members' read and the create run: off the caller's thread. */
    private val work: CoroutineDispatcher,
) {
    interface Door {
        /** The current members of [scopeId]; null when they do not read. */
        suspend fun members(scopeId: String): List<Person>?

        /** Creates the Pot ([PotCreate.create]); where it exists, the shell's lists are read again and it is opened. */
        suspend fun create(scopeId: String, parentId: String?, name: String, base: Ledger.BaseCurrency, people: List<PotCreate.NewPerson>): PotCreate.Result
    }

    data class State(
        /** The spaces a Pot may go to; the picker is shown only for more than one. */
        val spaces: List<SpaceRef>,
        /** The space chosen; null when the tenant has none. */
        val scopeId: String?,
        val currency: String,
        /** The people the Pot starts with, in the order added. */
        val people: List<PotCreate.NewPerson> = emptyList(),
        /** The chosen space's members still to offer (those not added). */
        val offered: List<Person> = emptyList(),
        val saving: Boolean = false,
        /** Why the last create did not land; or that the Pot exists without its people ([PotCreate.Outcome.PEOPLE_NOT_ADDED]). */
        val notice: Notice? = null,
        /** The Pot exists: the sheet has nothing more to do. */
        val done: Boolean = false,
    )

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var people = PotCreate.People()
    private var members: List<Person> = emptyList()
    /** Which members' read is the one asked last: an answer for a space left since is dropped. */
    private var membersAsk = 0

    private val _state = MutableStateFlow(first)
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    init {
        first.scopeId?.let(::readMembers)
    }

    private fun shown() = _state.update { it.copy(people = people.list, offered = people.offered(members)) }

    /** The space [scopeId] chosen: its members read, a member of the space left dropped from the people. */
    fun space(scopeId: String) {
        if (scopeId == _state.value.scopeId || _state.value.spaces.none { it.scopeId == scopeId }) return
        // nothing offered until the space's members read; a person kept or dropped once they did
        members = emptyList()
        _state.update { it.copy(scopeId = scopeId) }
        shown()
        readMembers(scopeId)
    }

    private fun readMembers(scopeId: String) {
        val ask = ++membersAsk
        scope.launch {
            val read = withContext(work) { runCatching { door.members(scopeId) }.getOrNull() }.orEmpty()
            if (ask != membersAsk) return@launch
            members = read
            people = people.keep(read)
            shown()
        }
    }

    fun currency(code: String) = _state.update { it.copy(currency = code) }

    /** A name typed added (once, any case); whether there was one to add, so the host empties its field. */
    fun addTyped(text: String): Boolean {
        if (text.isBlank()) return false
        people = people.typed(text)
        shown()
        return true
    }

    /** Member [userId] of the chosen space added. */
    fun addMember(userId: String) {
        val m = members.firstOrNull { it.userId == userId } ?: return
        people = people.add(PotCreate.NewPerson.member(m.userId, m.displayName))
        shown()
    }

    /** The person at [index] of [State.people] taken out again. */
    fun remove(index: Int) {
        val p = people.list.getOrNull(index) ?: return
        people = people.remove(p)
        shown()
    }

    /** Whether a Pot named [name] may be created now: a name, a space, no create running. */
    fun canCreate(name: String): Boolean = name.isNotBlank() && _state.value.scopeId != null && !_state.value.saving

    /**
     * Creates the Pot named [name], a name still [typed] in the people's field taken first; whether it exists (its
     * people may still have failed: the notice says so, and the Pot opens, where they can be added on its Settings).
     */
    suspend fun create(name: String, typed: String = ""): Boolean {
        addTyped(typed)
        if (!canCreate(name)) return false
        val s = _state.value
        val scopeId = s.scopeId ?: return false
        _state.update { it.copy(saving = true, notice = null) }
        val base = Ledger.BaseCurrency(s.currency, Currencies.minorUnits(s.currency))
        val parentId = PotCreate.parentIn(place, scopeId)
        val r = withContext(work) {
            runCatching { door.create(scopeId, parentId, name, base, people.list) }.getOrDefault(PotCreate.Result(PotCreate.Outcome.REFUSED))
        }
        val created = r.nodeId != null
        val notice = r.outcome.takeIf { it != PotCreate.Outcome.CREATED }?.let { Notice(Notices.potCreateRefused(it.name)) }
        _state.update { it.copy(saving = false, notice = notice, done = created) }
        return created
    }

    /** The sheet is gone: a members' read still running is forgotten. */
    fun close() {
        scope.cancel()
    }

    companion object {
        /** The sheet over [door] (a model's, or a test's outside the core: the bridge's dispatch plays one). */
        fun open(door: Door, place: Shell, spaces: List<SpaceRef>, language: String, parent: CoroutineScope, work: CoroutineDispatcher): PotCreateSheet =
            PotCreateSheet(door, place, State(spaces, PotCreate.defaultSpace(place, spaces), PotCreate.defaultCurrency(language)), parent, work)
    }
}
