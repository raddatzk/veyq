package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.RevisionFormat
import me.raddatz.veyq.mobile.navigation.BarTab
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import me.raddatz.veyq.mobile.navigation.ObjectRef
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency

/**
 * An open Pot (ADR 0122 decision 4: Expenses' bar is its open Pot's Entries, Overview and Settings, as the web's Pot has
 * them). What it shows comes from core's [PotView]; this only lays it out and formats money.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PotScreen(ref: ObjectRef, tab: BarTab?, model: AppModel) {
    val state by model.pot.collectAsStateWithLifecycle()
    LaunchedEffect(ref) { model.loadPot(ref) }
    val s = state?.takeIf { it.ref == ref }
    val view = s?.view
    // Live while the Pot is on screen and the app started: what another device writes shows up (core's `LivePoll`).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(ref, view != null) {
        if (view != null) owner.repeatOnLifecycle(Lifecycle.State.STARTED) { model.livePot() }
    }
    var refreshing by remember { mutableStateOf(false) }
    when {
        s == null || (view == null && !s.failed) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        view == null -> Note(stringResource(R.string.list_empty))
        !view.ready -> PotSettings(view, s, model)
        else -> Column(Modifier.fillMaxSize()) {
            if (view.missing.isNotEmpty()) LedgerNotice(view)
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    model.refreshPot { refreshing = false }
                },
                modifier = Modifier.weight(1f),
            ) {
                when (tab?.key) {
                    "expenses.overview" -> Overview(view, s, model)
                    "expenses.settings" -> PotSettings(view, s, model)
                    else -> Entries(view, s, model)
                }
            }
        }
    }
}

/** The ledger check's notice (ADR 0108 decision 5): what the read found missing or altered, so the balances may be wrong. */
@Composable
private fun LedgerNotice(view: PotView) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.pot_ledger_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        for (m in view.missing) {
            val name = piece(view.missingName(m))
            Text("• $name", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = role(LocalPalette.current.muted), modifier = Modifier.padding(24.dp))
}

/** Minor units of [currency] as the person's locale writes money. */
fun money(minor: Long, currency: String?, minorUnits: Int): String {
    // D8: the core hands every amount with its currency; none is never the locale's, but a number alone (the desktops' rule)
    val f = if (currency == null) NumberFormat.getNumberInstance() else NumberFormat.getCurrencyInstance()
    currency?.let { runCatching { f.currency = Currency.getInstance(it) } }
    f.minimumFractionDigits = minorUnits
    f.maximumFractionDigits = minorUnits
    return f.format(BigDecimal.valueOf(minor, minorUnits))
}

/** An entry's `YYYY-MM-DD` as the person's locale writes a day; the text itself when it is no date. */
internal fun localDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)

@Composable
internal fun categoryName(id: String?, view: PotView): String = if (id == null) "" else piece(view.categoryLabel(id))

@Composable
private fun personName(name: String?) = piece(PotView.person(name))

@Composable
private fun Entries(view: PotView, state: me.raddatz.veyq.mobile.expenses.PotModel.State, model: AppModel) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<PotView.EntryRow?>(null) }
    val palette = LocalPalette.current
    val hero = LocalNodeHero.current
    CreateAction(stringResource(R.string.pot_add), shown = view.canWrite && view.people.isNotEmpty(), onClick = { adding = true }) { list, bottom ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
            item(key = "hero") { hero(16.dp) }
            if (view.entries.isEmpty()) item(key = "empty") { Note(stringResource(if (view.canWrite) R.string.pot_no_entries else R.string.pot_no_entries_readonly)) }
            items(view.entries, key = { it.id }) { e ->
                // Every viewer opens an entry: a writer to edit it, a reader to read it (one dialog, inbox #401).
                Row(
                    Modifier.fillMaxWidth().clickable { editing = e.id }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title ?: stringResource(R.string.pot_transfer), fontWeight = FontWeight.SemiBold)
                        Text(view.line(e).map { piece(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                        view.badge(e)?.let { b -> Text(say(b.notice), style = MaterialTheme.typography.labelSmall, color = role(if (b.danger) palette.danger else palette.muted)) }
                    }
                    e.amount?.let { Text(money(it, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider(color = role(palette.line))
            }
            if (view.deletedEntries.isNotEmpty()) {
                item { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Section(stringResource(R.string.entries_deleted_title)) } }
                items(view.deletedEntries, key = { "deleted:" + it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewing = e }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title ?: stringResource(R.string.pot_transfer), color = role(palette.muted))
                            Text(stringResource(R.string.entries_deleted_badge), style = MaterialTheme.typography.labelSmall, color = role(palette.muted))
                        }
                        e.amount?.let { Text(money(it, view.currency, view.minorUnits), color = role(palette.muted)) }
                    }
                    HorizontalDivider(color = role(palette.line))
                }
            }
        }
    }
    if (adding) EntryEditorSheet(view, model, null) { adding = false }
    // Files shared to veyq and placed here: a new expense with them as its receipts (ADR 0129), where entries may be added.
    var shared by remember { mutableStateOf<List<me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt>?>(null) }
    val incoming by model.incoming.collectAsStateWithLifecycle()
    LaunchedEffect(incoming, view.canWrite) {
        // Only while the shell still shows this Pot: files given back (its editor closed unsaved) move the shell Home first.
        val open = (model.screen.value as? Screen.Signed)?.shell?.openObject == state.ref
        if (incoming != null && open && model.shareInbox.goesInto(view)) shared = model.takeShare()
    }
    // Its editor closed unsaved: the files wait again in the pick mode, for this Pot or another.
    shared?.let { files ->
        var saved by remember { mutableStateOf(false) }
        EntryEditorSheet(view, model, null, initialPending = files, onSaved = { saved = true; model.shareSaved() }) {
            shared = null
            if (!saved) model.returnShare()
        }
    }
    editing?.let { EntryEditorSheet(view, model, it) { editing = null } }
    viewing?.let { DeletedEntrySheet(view, state, model, it) { viewing = null } }
}

@Composable
private fun Overview(view: PotView, state: me.raddatz.veyq.mobile.expenses.PotModel.State, model: AppModel) {
    val palette = LocalPalette.current
    val hero = LocalNodeHero.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item(key = "hero") { hero(0.dp) }
        item { Section(stringResource(R.string.pot_balances)) }
        items(view.balances, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(personName(p.name), p.userId)
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(personName(p.name), fontWeight = FontWeight.SemiBold)
                    // What makes the balance: paid and consumed always, the transfers only where there were any.
                    Text(view.balanceFacts(p).map { piece(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                }
                val b = view.balance(p)
                Text(
                    say(b.notice),
                    color = role(when (b.tone) { PotView.Tone.OWES -> palette.danger; PotView.Tone.OWED -> palette.primary; PotView.Tone.SETTLED -> palette.muted }),
                )
            }
        }
        if (view.settleUp.isNotEmpty()) {
            item { Section(stringResource(R.string.pot_settle_up)) }
            items(view.settleUp) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(say(view.pays(t)), Modifier.weight(1f))
                    Text(money(t.amount, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold)
                    if (view.canWrite) {
                        TextButton(onClick = { model.recordSettleUp(listOf(t)) }, enabled = !state.saving) {
                            Text(stringResource(R.string.balances_record))
                        }
                    }
                }
            }
            if (view.canRecordAll) {
                item {
                    TextButton(onClick = { model.recordSettleUp(view.settleUp) }, enabled = !state.saving) {
                        Text(stringResource(R.string.balances_record_all))
                    }
                }
            }
            state.message?.let { m -> item { Text(potRefusal(m), color = MaterialTheme.colorScheme.error) } }
        }
        if (view.byCategory.isNotEmpty()) {
            item { Section(stringResource(R.string.pot_by_category)) }
            items(view.byCategory, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(categoryName(c.id, view), Modifier.weight(1f))
                    Text(money(c.amount, view.currency, view.minorUnits))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(stringResource(R.string.pot_total), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(money(view.total, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

