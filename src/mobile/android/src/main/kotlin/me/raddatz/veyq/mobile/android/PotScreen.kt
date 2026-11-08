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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import me.raddatz.veyq.mobile.navigation.ObjectRef
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency

/**
 * An open Pot (ADR 0122 decision 4: Expenses' bar is its open Pot's Entries, Overview and Settings, as the web's Pot has
 * them). What it shows comes from core's [PotView]; this only lays it out and formats money.
 */
@Composable
fun PotScreen(ref: ObjectRef, tab: BarTab?, model: AppModel) {
    val state by model.pot.collectAsStateWithLifecycle()
    LaunchedEffect(ref) { model.loadPot(ref) }
    val s = state?.takeIf { it.ref == ref }
    val view = s?.view
    when {
        s == null || (view == null && !s.failed) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        view == null -> Note(stringResource(R.string.list_empty))
        !view.ready -> PotSettings(view, s, model)
        else -> Column(Modifier.fillMaxSize()) {
            if (view.missing.isNotEmpty()) LedgerNotice(view)
            Box(Modifier.weight(1f)) {
                when (tab?.key) {
                    "expenses.overview" -> Overview(view)
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
            val name = when (m.kind) {
                PotView.MissingKind.TRANSFER -> stringResource(R.string.pot_transfer)
                PotView.MissingKind.ERASED_PERSON -> stringResource(R.string.pot_deleted_person)
                PotView.MissingKind.UNKNOWN -> stringResource(R.string.pot_ledger_unknown_entry)
                else -> m.name
            }
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
    val f = NumberFormat.getCurrencyInstance()
    currency?.let { runCatching { f.currency = Currency.getInstance(it) } }
    f.minimumFractionDigits = minorUnits
    f.maximumFractionDigits = minorUnits
    return f.format(BigDecimal.valueOf(minor, minorUnits))
}

/** An entry's `YYYY-MM-DD` as the person's locale writes a day; the text itself when it is no date. */
private fun localDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)

@Composable
internal fun categoryName(id: String?, view: PotView): String = when (id) {
    "food" -> stringResource(R.string.cat_food)
    "groceries" -> stringResource(R.string.cat_groceries)
    "accommodation" -> stringResource(R.string.cat_accommodation)
    "transport" -> stringResource(R.string.cat_transport)
    "leisure" -> stringResource(R.string.cat_leisure)
    "household" -> stringResource(R.string.cat_household)
    "other" -> stringResource(R.string.cat_other)
    null -> ""
    else -> view.ownCategories.firstOrNull { it.first == id }?.second ?: stringResource(R.string.cat_unknown)
}

@Composable
private fun personName(name: String?) = name ?: stringResource(R.string.pot_deleted_person)

@Composable
private fun Entries(view: PotView, state: AppModel.PotState, model: AppModel) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, PotView.Editing>?>(null) }
    val palette = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        if (view.entries.isEmpty()) Note(stringResource(R.string.pot_no_entries))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            items(view.entries, key = { it.id }) { e ->
                val canEdit = view.canWrite && e.kind != null
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = canEdit) { view.editing(e.id, separator())?.let { editing = e.id to it } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title ?: stringResource(R.string.pot_transfer), fontWeight = FontWeight.SemiBold)
                        val who = e.who.map { personName(it.ifEmpty { null }) }
                        val line = listOfNotNull(
                            e.date?.let(::localDate),
                            e.category?.let { categoryName(it, view) },
                            when (e.kind) {
                                PotView.Kind.EXPENSE -> stringResource(R.string.pot_expense_by, who.joinToString())
                                PotView.Kind.REFUND -> stringResource(R.string.pot_refund_to, who.joinToString())
                                PotView.Kind.TRANSFER -> who.joinToString(" → ")
                                null -> null
                            },
                            e.foreignAmount?.let { money(it, e.foreignCurrency, e.foreignMinorUnits) },
                        ).joinToString(" · ")
                        Text(line, style = MaterialTheme.typography.bodySmall, color = role(palette.muted))
                        val badge = when (e.status) {
                            Ledger.Status.INVALID -> stringResource(R.string.pot_invalid)
                            Ledger.Status.UNREADABLE -> stringResource(R.string.pot_unreadable)
                            else -> if (e.edited) stringResource(R.string.pot_edited) else null
                        }
                        badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = role(if (e.status == Ledger.Status.LIVE) palette.muted else palette.danger)) }
                    }
                    e.amount?.let { Text(money(it, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider(color = role(palette.line))
            }
        }
        if (view.canWrite && view.people.isNotEmpty()) {
            // Creating is the web's green role (`--app-primary`).
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.pot_add)) },
                containerColor = role(palette.primary),
                contentColor = role(palette.onFill),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    if (adding) EntryEditor(view, state, model, null) { adding = false }
    editing?.let { EntryEditor(view, state, model, it) { editing = null } }
}

@Composable
private fun Overview(view: PotView) {
    val palette = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item { Section(stringResource(R.string.pot_balances)) }
        items(view.balances, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(personName(p.name), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.pot_paid_consumed, money(p.paid, view.currency, view.minorUnits), money(p.share, view.currency, view.minorUnits)),
                        style = MaterialTheme.typography.bodySmall, color = role(palette.muted),
                    )
                }
                Text(
                    when {
                        p.balance > 0 -> stringResource(R.string.pot_is_owed, money(p.balance, view.currency, view.minorUnits))
                        p.balance < 0 -> stringResource(R.string.pot_owes, money(-p.balance, view.currency, view.minorUnits))
                        else -> stringResource(R.string.pot_settled)
                    },
                    color = role(if (p.balance < 0) palette.danger else if (p.balance > 0) palette.primary else palette.muted),
                )
            }
        }
        if (view.settleUp.isNotEmpty()) {
            item { Section(stringResource(R.string.pot_settle_up)) }
            items(view.settleUp) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.pot_pays, personName(t.from), personName(t.to)), Modifier.weight(1f))
                    Text(money(t.amount, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold)
                }
            }
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

