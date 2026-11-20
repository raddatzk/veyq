package me.raddatz.veyq.mobile.android.apps.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.ApplicationUi
import me.raddatz.veyq.mobile.android.core.ArrangedListScreen
import me.raddatz.veyq.mobile.android.core.CreateAction
import me.raddatz.veyq.mobile.android.core.CreateDialog
import me.raddatz.veyq.mobile.android.core.HeaderContribution
import me.raddatz.veyq.mobile.android.core.KindPage
import me.raddatz.veyq.mobile.android.core.LocalListStates
import me.raddatz.veyq.mobile.android.core.Registry
import me.raddatz.veyq.mobile.android.core.Screen
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.sharing.GuestPage

@Composable
private fun potHeader(node: NodeAt, model: AppModel): HeaderContribution? {
    val state = model.pot.collectAsStateWithLifecycle().value
    val view = state?.takeIf { it.ref.nodeId == node.nodeId }?.view ?: return null
    val ref = state.ref
    return HeaderContribution(
        subtitle = view.subtitle?.let { say(it) },
        // a cover and a copy write: core offers them where Expenses is on (the web's `canCopy`, `expensesRights`)
        facts = me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = view.canWrite),
        download = { out -> if (model.downloadPot(ref, out)) emptyList() else null },
    )
}

/** Expenses' own list (its Pots), with "New pot" (uc.expenses.create-pot). */
@Composable
private fun ExpensesList(s: Screen.Signed, model: AppModel, onOpen: (ObjectRef) -> Unit = model::open) {
    var creating by remember { mutableStateOf(false) }
    CreateAction(stringResource(R.string.create_pot), shown = s.spaces.isNotEmpty() && me.raddatz.veyq.mobile.AppKinds.Create.POT in model.createsHere(isHome = false), onClick = { creating = true }, state = LocalListStates.current.of("app:EXPENSES")) { list, bottom ->
        ArrangedListScreen(s.applicationList(ApplicationId.EXPENSES), onOpen = onOpen, state = list, bottom = bottom)
    }
    if (creating) PotCreateSheet(s, model) { creating = false }
}

/**
 * A Pot of a link: the member's Pot page over the guest's Pot (the app model routes the page's reads and writes to it while
 * it is shown), its Entries, Overview and — where the core offers them (`PotView.settingsOffered`: a guest who writes,
 * #1035) — Settings as tabs of its own; the guest view has no bar.
 */
@Composable
private fun GuestPot(page: GuestPage.Pot, model: AppModel) {
    DisposableEffect(page.model) {
        model.showGuestPot(page.model)
        onDispose { model.showGuestPot(null) }
    }
    val ref = remember(page.node) {
        ObjectRef(page.node.scopeId, page.node.nodeId, page.node.kind, me.raddatz.veyq.mobile.navigation.ApplicationId.EXPENSES, page.node.name)
    }
    val pot by page.model.state.collectAsStateWithLifecycle()
    val places = listOfNotNull(
        "expenses.entries" to me.raddatz.veyq.mobile.words.Msg.TAB_EXPENSES_ENTRIES,
        "expenses.overview" to me.raddatz.veyq.mobile.words.Msg.TAB_EXPENSES_OVERVIEW,
        ("expenses.settings" to me.raddatz.veyq.mobile.words.Msg.TAB_EXPENSES_SETTINGS).takeIf { pot?.view?.settingsOffered == true },
    )
    var chosen by remember(page.node) { mutableStateOf("expenses.entries") }
    val at = places.indexOfFirst { it.first == chosen }.coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = at, containerColor = MaterialTheme.colorScheme.background) {
            places.forEachIndexed { i, (key, word) -> Tab(selected = i == at, onClick = { chosen = key }, text = { Text(say(word)) }) }
        }
        Box(Modifier.weight(1f)) { PotScreen(ref, BarTab(places[at].first), model) }
    }
}

/** New pot at the shell's place now (core's `PotCreateSheet`), the web's `createDialog` of a Pot. */
private val newPot: CreateDialog = { s, model, _, _, onClose -> PotCreateSheet(s, model, onClose) }

/**
 * Expenses' UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerExpenses`): a Pot's page — its
 * currency, cover and copy in core's header, its Entries, Overview and Settings (`PotScreen`), a link's Pot —, its own
 * list of Pots and New pot.
 */
internal object ExpensesUi {
    /** Expenses' Pot: its currency under its name, its cover and copy while Expenses is on, its ZIP. */
    private val pot = KindPage(
        header = { node, model -> potHeader(node, model) },
        body = { ref, tab, model -> PotScreen(ref, tab, model) },
        guest = { page, _, model, _ -> if (page is GuestPage.Pot) GuestPot(page, model) },
    )

    fun register(registry: Registry) {
        registry.kind(pot, PotSession.KIND)
        registry.application(
            ApplicationUi(
                ApplicationId.EXPENSES,
                list = { s, model, onOpen -> ExpensesList(s, model, onOpen) },
                creates = mapOf(me.raddatz.veyq.mobile.AppKinds.Create.POT to newPot),
            ),
        )
    }
}
