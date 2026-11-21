package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.raddatz.veyq.mobile.session.OfflineAsOf

/**
 * The kept time each page of one tenant shows offline, by its node id (ADR 0140 decision 10): a page read from the
 * offline store as of its own last answer — a Pot's ledger, a gallery's listing — says so here while it is shown, and
 * the shell's banner names that time on the open page instead of its lists' (`ShellModel.State.banner`).
 * Only a known time is held: a page offline without one, online again or gone leaves.
 */
class PagesOffline {
    private val _shown = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The node ids whose page shows the store's copy, each with the time it is as of. */
    val shown: StateFlow<Map<String, Long>> = _shown.asStateFlow()

    /** The page of [nodeId] shown offline as of [offline] (null: online, gone, or no time known). */
    fun shown(nodeId: String, offline: OfflineAsOf?) {
        val at = offline?.asOf
        _shown.update { if (at == null) it - nodeId else it + (nodeId to at) }
    }
}
