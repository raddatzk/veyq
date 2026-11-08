package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.EntryForm.FormSplit
import me.raddatz.veyq.mobile.expenses.EntryForm.Kind
import me.raddatz.veyq.mobile.expenses.EntryForm.Mode
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.RevisionFormat

/** The reader's decimal separator, as amounts are typed over. */
internal fun separator(): Char = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

/** A problem of the form or the write path in words (the web's `expenses.entry.problem.*`). */
@Composable
internal fun problemText(problem: String): String = stringResource(
    when (problem) {
        "title" -> R.string.problem_title
        "date" -> R.string.problem_date
        "amount" -> R.string.problem_amount
        "rate" -> R.string.problem_rate
        "payers" -> R.string.problem_payers
        "split" -> R.string.problem_split
        "split-shares" -> R.string.problem_split_shares
        "split-percents" -> R.string.problem_split_percents
        "split-amounts" -> R.string.problem_split_amounts
        "items" -> R.string.problem_items
        "transfer" -> R.string.problem_transfer
        "payers-unbalanced" -> R.string.problem_payers_unbalanced
        "receivers-unbalanced" -> R.string.problem_receivers_unbalanced
        "items-above-total" -> R.string.problem_items_above_total
        "rest-unallocated" -> R.string.problem_rest_unallocated
        "percent-unbalanced" -> R.string.problem_percent_unbalanced
        "amounts-unbalanced" -> R.string.problem_amounts_unbalanced
        "named-twice" -> R.string.problem_named_twice
        else -> R.string.problem_unknown_participant
    },
)

/**
 * The entry editor (the web's `EntryEditor.vue` and `EntrySplit.vue`): an expense, a refund or a transfer; payers with
 * their amounts (an expense's amount is their sum), items with exact amounts and their own splits, the rest by its split
 * (by shares, percent or amount, "everyone equally" as one line), another currency with its typed rate, and the shares in
 * the base currency as the save seals them. Every rule is core's [EntryForm]; a refusal is shown at its field.
 * [editing] null adds a new entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditor(view: PotView, state: AppModel.PotState, model: AppModel, editing: Pair<String, PotView.Editing>?, onClose: () -> Unit) {
    val sep = remember { separator() }
    val people = editing?.second?.people ?: view.people
    val ids = people.map { it.id }
    var form by remember {
        mutableStateOf(editing?.second?.form ?: view.blankForm(java.time.LocalDate.now().toString(), sep)!!)
    }
    var tried by remember { mutableStateOf(false) }
    val refusal = view.check(form)
    val shown = refusal.takeIf { tried }
    fun at(field: String, item: Int? = null) = shown?.takeIf { it.at == field && it.item == item }
    val nameOf = people.associate { it.id to it.name }

    ModalBottomSheet(onDismissRequest = { model.clearPotMessage(); onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(if (editing == null) R.string.ed_new_title else R.string.ed_edit_title), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Kind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = form.kind == k, onClick = { form = form.withKind(k, sep) },
                        shape = SegmentedButtonDefaults.itemShape(i, Kind.entries.size),
                    ) {
                        Text(stringResource(when (k) { Kind.EXPENSE -> R.string.ed_expense; Kind.REFUND -> R.string.ed_refund; Kind.TRANSFER -> R.string.pot_transfer }))
                    }
                }
            }
            OutlinedTextField(
                form.title, { form = form.copy(title = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (form.kind == Kind.TRANSFER) R.string.ed_note else R.string.entry_title)) },
                isError = at("title") != null, supportingText = at("title")?.let { { Text(problemText(it.problem)) } },
            )
            DateField(form.date, at("date")?.problem) { form = form.copy(date = it) }
            if (form.kind != Kind.TRANSFER) CategoryField(view, form.category) { form = form.copy(category = it) }
            // Another currency with an empty rate: the day's table fills it (one request a device and day, at most).
            var rateNote by remember { mutableStateOf<RateNote?>(null) }
            androidx.compose.runtime.LaunchedEffect(form.currency) {
                val code = form.currency
                if (!view.ratesOffered || code == view.currency || form.rate.isNotBlank()) {
                    rateNote = null
                    return@LaunchedEffect
                }
                rateNote = RateNote.Loading
                val filled = model.rateFor(code)
                if (form.currency != code) return@LaunchedEffect
                if (filled != null && form.rate.isBlank()) {
                    form = form.copy(rate = filled.rate.replace('.', sep))
                    rateNote = RateNote.Filled(filled.date)
                } else {
                    rateNote = if (filled == null) RateNote.Missing(code) else null
                }
            }
            CurrencyField(view, form, at("rate")?.problem, rateNote) { form = it }

            if (form.kind == Kind.TRANSFER) {
                OutlinedTextField(
                    form.amount, { form = form.copy(amount = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.entry_amount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = at("amount") != null, supportingText = at("amount")?.let { { Text(problemText(it.problem)) } },
                )
                PersonField(stringResource(R.string.ed_from), people, form.from) { form = form.copy(from = it) }
                PersonField(stringResource(R.string.ed_to), people, form.to) { form = form.copy(to = it) }
                at("transfer")?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
            } else {
                Payers(form, people, at("payers")?.problem, sep) { form = it }
                Text(
                    stringResource(R.string.entry_amount) + ": " + money(form.payersTotal, form.currency, form.minorUnits),
                    fontWeight = FontWeight.SemiBold,
                )
                form.items.forEachIndexed { i, item ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            item.label, { v -> form = form.copy(items = form.items.toMutableList().also { it[i] = item.copy(label = v) }) },
                            singleLine = true, label = { Text(stringResource(R.string.ed_item)) }, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            item.amount, { v -> form = form.copy(items = form.items.toMutableList().also { it[i] = item.copy(amount = v) }) },
                            singleLine = true, label = { Text(stringResource(R.string.entry_amount)) }, modifier = Modifier.width(120.dp).padding(start = 8.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        IconButton(onClick = { form = form.copy(items = form.items.toMutableList().also { it.removeAt(i) }) }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.ed_remove_item))
                        }
                    }
                    SplitEditor(item.split, people, form.itemAmount(item), form, sep) { s ->
                        form = form.copy(items = form.items.toMutableList().also { it[i] = item.copy(split = s) })
                    }
                    at("item", i)?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { form = form.copy(items = form.items + EntryForm.emptyItem(ids)) }) { Text(stringResource(R.string.ed_add_item)) }
                at("items")?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
                if (form.items.isEmpty() || form.restLeft > 0) {
                    HorizontalDivider()
                    Text(stringResource(if (form.items.isEmpty()) R.string.ed_whole else R.string.ed_rest), fontWeight = FontWeight.SemiBold)
                    SplitEditor(form.rest, people, form.restLeft, form, sep) { form = form.copy(rest = it) }
                    at("rest")?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
                }
                Preview(view, form, nameOf)
            }
            shown?.takeIf { it.at == null }?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(entryRefusal(it), color = MaterialTheme.colorScheme.error) }
            if (editing != null) {
                HorizontalDivider()
                EntryHistory(view, editing.first)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (editing != null) {
                    TextButton(onClick = { model.potWrite({ it.deleteEntry(editing.first, editing.second.basedOn) }) { onClose() } }, enabled = !state.saving) {
                        Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = { model.clearPotMessage(); onClose() }) { Text(stringResource(R.string.entry_cancel)) }
                Button(
                    onClick = {
                        tried = true
                        if (refusal == null) model.potWrite({ it.saveEntry(editing?.first, form, editing?.second?.basedOn) }) { onClose() }
                    },
                    enabled = !state.saving,
                ) { Text(stringResource(R.string.entry_save)) }
            }
        }
    }
}

@Composable
private fun entryRefusal(outcome: String): String = stringResource(
    when (outcome) {
        "STALE" -> R.string.ed_stale
        "BLOCKED" -> R.string.ed_blocked
        "BUSY" -> R.string.pot_busy
        else -> R.string.ed_save_failed
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: String, problem: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val millis = runCatching { java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            runCatching { java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrDefault(date),
            {}, readOnly = true, label = { Text(stringResource(R.string.ed_date)) }, modifier = Modifier.fillMaxWidth(),
            isError = problem != null, supportingText = problem?.let { { Text(problemText(it)) } },
        )
        // A read-only field takes no click: a transparent button over it opens the picker.
        TextButton(onClick = { open = true }, modifier = Modifier.matchParentSize()) {}
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text(stringResource(R.string.entry_save)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.entry_cancel)) } },
        ) { DatePicker(picker) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(
            options.firstOrNull { it.first == selected }?.second ?: "", {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            for ((id, text) in options) DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); open = false })
        }
    }
}

@Composable
private fun PersonField(label: String, people: List<PotView.Person>, selected: String, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (String) -> Unit) =
    Dropdown(label, people.map { it.id to it.name }, selected, modifier, onSelect)

@Composable
private fun CategoryField(view: PotView, selected: String, onSelect: (String) -> Unit) {
    val defaults = RevisionFormat.DEFAULT_CATEGORIES.map { it to categoryName(it, view) }
    Dropdown(stringResource(R.string.entry_category), defaults + view.ownCategories, selected, onSelect = onSelect)
}

@Composable
private fun CurrencyField(view: PotView, form: EntryForm, problem: String?, note: RateNote?, onChange: (EntryForm) -> Unit) {
    val base = view.currency ?: return
    Row(verticalAlignment = Alignment.Top) {
        Dropdown(stringResource(R.string.ed_currency), currencies.map { it to it }, form.currency, Modifier.width(140.dp)) { code ->
            onChange(form.withCurrency(code, minorUnitsOf(code), me.raddatz.veyq.mobile.expenses.Ledger.BaseCurrency(base, view.minorUnits)))
        }
        if (form.currency != base) {
            OutlinedTextField(
                form.rate, { onChange(form.copy(rate = it)) }, singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp),
                label = { Text(stringResource(R.string.ed_rate, form.currency, base)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = problem != null,
                supportingText = {
                    Text(
                        problem?.let { problemText(it) } ?: when (note) {
                            RateNote.Loading -> stringResource(R.string.ed_rate_loading)
                            is RateNote.Filled -> stringResource(R.string.ed_rate_filled, localDay(note.date))
                            is RateNote.Missing -> stringResource(R.string.ed_rate_missing, note.currency)
                            null -> stringResource(R.string.ed_rate_hint)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun Payers(form: EntryForm, people: List<PotView.Person>, problem: String?, sep: Char, onChange: (EntryForm) -> Unit) {
    Text(stringResource(if (form.kind == Kind.REFUND) R.string.ed_received_by else R.string.entry_paid_by), fontWeight = FontWeight.SemiBold)
    form.payers.forEachIndexed { i, p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonField(stringResource(R.string.ed_person), people, p.participant, Modifier.weight(1f)) { id ->
                onChange(form.copy(payers = form.payers.toMutableList().also { it[i] = p.copy(participant = id) }))
            }
            OutlinedTextField(
                p.amount, { v -> onChange(form.copy(payers = form.payers.toMutableList().also { it[i] = p.copy(amount = v) })) },
                singleLine = true, label = { Text(stringResource(R.string.entry_amount)) }, modifier = Modifier.width(120.dp).padding(start = 8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            if (form.payers.size > 1) {
                IconButton(onClick = { onChange(form.copy(payers = form.payers.toMutableList().also { it.removeAt(i) })) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.ed_remove_payer, people.firstOrNull { it.id == p.participant }?.name ?: ""))
                }
            }
        }
    }
    problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error) }
    val unused = people.firstOrNull { pp -> form.payers.none { it.participant == pp.id } }
    if (unused != null) {
        TextButton(onClick = { onChange(form.copy(payers = form.payers + EntryForm.Payer(unused.id, ""))) }) { Text(stringResource(R.string.ed_add_payer)) }
    }
}

/** A split: one line while it is everyone equally, else its mode and one row per person (`EntrySplit.vue`). */
@Composable
private fun SplitEditor(split: FormSplit, people: List<PotView.Person>, part: Long, form: EntryForm, sep: Char, onChange: (FormSplit) -> Unit) {
    val ids = people.map { it.id }
    if (!split.expanded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val on = split.rows.map { it.participant }
            Text(
                if (on.size == 1) stringResource(R.string.ed_split_only, people.firstOrNull { it.id == on[0] }?.name ?: "")
                else pluralStringResource(R.plurals.ed_split_everyone, on.size, on.size),
                Modifier.weight(1f),
            )
            TextButton(onClick = { onChange(split.copy(expanded = true)) }) { Text(stringResource(R.string.ed_adjust)) }
        }
        return
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Mode.entries.forEachIndexed { i, m ->
            SegmentedButton(selected = split.mode == m, onClick = { onChange(split.copy(mode = m)) }, shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)) {
                Text(stringResource(when (m) { Mode.SHARES -> R.string.ed_by_shares; Mode.PERCENT -> R.string.ed_by_percent; Mode.AMOUNTS -> R.string.ed_by_amount }))
            }
        }
    }
    for (p in people) {
        val row = split.rows.firstOrNull { it.participant == p.id }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(row != null, { on ->
                onChange(split.copy(rows = if (on) ids.mapNotNull { id -> split.rows.firstOrNull { it.participant == id } ?: EntryForm.SplitRow(id).takeIf { id == p.id } } else split.rows.filter { it.participant != p.id }))
            })
            Text(p.name, Modifier.weight(1f))
            if (row != null) {
                val value = when (split.mode) { Mode.SHARES -> row.shares; Mode.PERCENT -> row.percent; Mode.AMOUNTS -> row.amount }
                OutlinedTextField(
                    value, { v ->
                        val next = when (split.mode) { Mode.SHARES -> row.copy(shares = v); Mode.PERCENT -> row.copy(percent = v); Mode.AMOUNTS -> row.copy(amount = v) }
                        onChange(split.copy(rows = split.rows.map { if (it.participant == p.id) next else it }))
                    },
                    singleLine = true, modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = if (split.mode == Mode.SHARES) KeyboardType.Number else KeyboardType.Decimal),
                    suffix = { if (split.mode == Mode.PERCENT) Text("%") },
                )
            }
        }
    }
    when (split.mode) {
        Mode.PERCENT -> EntryForm.percentTotal(split).takeIf { it != RevisionFormat.PERCENT_WHOLE }?.let {
            Text(stringResource(R.string.ed_percent_total, me.raddatz.veyq.mobile.expenses.Amounts.text(it, 2, sep)), style = MaterialTheme.typography.bodySmall)
        }
        Mode.AMOUNTS -> EntryForm.amountsTotal(split, form.minorUnits).takeIf { it != part && part > 0 }?.let {
            Text(stringResource(R.string.ed_amounts_total, money(it, form.currency, form.minorUnits), money(part, form.currency, form.minorUnits)), style = MaterialTheme.typography.bodySmall)
        }
        Mode.SHARES -> {}
    }
    TextButton(onClick = { onChange(EntryForm.everyoneEqually(split, ids)) }) { Text(stringResource(R.string.ed_everyone_equally)) }
}

/** The shares in the base currency as the save seals them (`sharesPreview`). */
@Composable
private fun Preview(view: PotView, form: EntryForm, nameOf: Map<String, String>) {
    val rows = view.preview(form)
    if (rows.isEmpty()) return
    HorizontalDivider()
    Text(stringResource(R.string.ed_shares_in, view.currency ?: ""), fontWeight = FontWeight.SemiBold)
    for (r in rows) {
        Row {
            Text(nameOf[r.id] ?: r.name, Modifier.weight(1f))
            if (r.given != 0L) Text(stringResource(if (form.kind == Kind.REFUND) R.string.ed_received_column else R.string.ed_paid_column) + " " + money(r.given, view.currency, view.minorUnits), Modifier.padding(end = 12.dp))
            Text(money(r.share, view.currency, view.minorUnits), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** What the editor says under a filled or missing rate. */
internal sealed interface RateNote {
    data object Loading : RateNote
    data class Filled(val date: String) : RateNote
    data class Missing(val currency: String) : RateNote
}

private fun localDay(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)
