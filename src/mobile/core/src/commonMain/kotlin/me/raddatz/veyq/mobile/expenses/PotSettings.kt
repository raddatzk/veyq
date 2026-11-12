package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * An open Pot's settings and people (the web's `SettingsPanel.vue` and `PeoplePanel.vue`, ADR 0129 decision 2): the base
 * currency, chosen only while the Pot holds no money entry; its own categories, added and renamed; its people added by
 * name or as a member, renamed, (de)activated, and erased only once asked. Each change is one write of the open Pot
 * ([PotModel], saving shown on its state) whose refusal this model says ([State.notice]); the typed text arrives with the
 * call, so no draft lives here. What may be done to whom is [PotView]'s (`PersonEntry`); a call it does not allow sends
 * nothing. Every action answers whether it was stored, so a host clears its field on the answer.
 *
 * Opened by [PotModel.settings].
 */
class PotSettings internal constructor(private val pot: PotModel, private val scope: CoroutineScope) {
    /** The person whose erase is asked: done only on [erase]. [named] — someone an entry names is deleted, else removed. */
    data class Asked(val personId: String, val name: String, val named: Boolean)

    data class State(
        /** An erase asked, waiting for its answer. */
        val asking: Asked? = null,
        /** Why the last change was not stored; null after one that was. */
        val notice: Notice? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    /**
     * The base currency set to [code] (its digits the core's table): nothing to do when it is the Pot's already; refused,
     * and said, once the Pot holds a money entry or the reader may not change the settings.
     */
    suspend fun setBaseCurrency(code: String): Boolean {
        val view = pot.view() ?: return false
        if (code == view.currency) return true
        if (!view.currencyEditable) return refused(Notice(if (view.currencyFixed) Msg.SETTINGS_CURRENCY_FIXED else Msg.PEOPLE_FAILED))
        return send(PotModel.Op.BaseCurrency(code, Currencies.minorUnits(code)))
    }

    /** An own category named [name] added. */
    suspend fun addCategory(name: String): Boolean {
        val view = pot.view() ?: return false
        if (name.isBlank() || !view.canAddCategory) return false
        return send(PotModel.Op.AddCategory(name))
    }

    /** Own category [id] renamed to [name]. */
    suspend fun renameCategory(id: String, name: String): Boolean {
        val view = pot.view() ?: return false
        if (name.isBlank() || !view.canChangeSettings || view.ownCategories.none { it.first == id }) return false
        return send(PotModel.Op.RenameCategory(id, name))
    }

    /** A person named [name] added, joining now. */
    suspend fun addPerson(name: String): Boolean {
        val view = pot.view() ?: return false
        if (name.isBlank() || !view.canWrite) return false
        return send(PotModel.Op.AddPerson(name))
    }

    /** Member [userId] of the space added as a person, where they are one to add. */
    suspend fun addMember(userId: String): Boolean {
        val view = pot.view() ?: return false
        if (!view.canWrite || view.addableMembers.none { it.userId == userId }) return false
        return send(PotModel.Op.AddMember(userId))
    }

    /** Person [personId] renamed to [name], where they are renamable (one added by name). */
    suspend fun rename(personId: String, name: String): Boolean {
        val p = entry(personId)?.takeIf { it.renamable } ?: return false
        if (name.isBlank()) return false
        return send(PotModel.Op.Rename(p.id, name))
    }

    /** Person [personId] deactivated, or activated again, where they may be (settled, or inactive). */
    suspend fun toggleActive(personId: String): Boolean {
        val p = entry(personId)?.takeIf { it.canToggle } ?: return false
        return send(PotModel.Op.SetActive(p.id, !p.active))
    }

    /** Person [personId]'s erase asked, where they may be erased (settled). */
    fun askErase(personId: String) {
        val p = entry(personId)?.takeIf { it.canErase } ?: return
        _state.update { it.copy(asking = Asked(p.id, p.name, p.named)) }
    }

    fun cancelAsk() = _state.update { it.copy(asking = null) }

    /** The person asked about erased; nothing without an erase asked. */
    suspend fun erase(): Boolean {
        val asked = _state.value.asking ?: return false
        _state.update { it.copy(asking = null) }
        if (entry(asked.personId)?.canErase != true) return false
        return send(PotModel.Op.Erase(asked.personId))
    }

    /** Forgets the last refusal (the screen left). */
    fun clear() = _state.update { it.copy(notice = null) }

    private fun entry(personId: String): PotView.PersonEntry? = pot.view()?.personEntries?.firstOrNull { it.id == personId }

    private suspend fun send(op: PotModel.Op): Boolean {
        val outcome = pot.written(op, said = false)
        val stored = outcome == PotSession.WriteOutcome.WRITTEN
        _state.update { it.copy(notice = if (stored) null else Notice(Notices.potRefused(outcome.name))) }
        return stored
    }

    private fun refused(notice: Notice): Boolean {
        _state.update { it.copy(notice = notice) }
        return false
    }
}
