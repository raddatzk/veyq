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
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.expenses.EditorChange
import me.raddatz.veyq.mobile.expenses.EntryForm
import me.raddatz.veyq.mobile.expenses.EntryEditor as EditorModel
import me.raddatz.veyq.mobile.expenses.EntryForm.FormSplit
import me.raddatz.veyq.mobile.expenses.EntryForm.Kind
import me.raddatz.veyq.mobile.expenses.EntryForm.Mode
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.expenses.RevisionFormat

/** The reader's decimal separator, as amounts are typed over. */
internal fun separator(): Char = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

/** Whether the editor's fields take input: false for a reader, who sees the same entry read only (inbox #401: rights
 *  only remove functions, never a reader's own screen). */
internal val LocalEditable = androidx.compose.runtime.staticCompositionLocalOf { true }

/** A problem of the form or the write path in words (the web's `expenses.entry.problem.*`). */
@Composable
internal fun problemText(problem: String): String = say(me.raddatz.veyq.mobile.words.Notices.entryProblem(problem))

/**
 * The entry editor (the web's `EntryEditor.vue` and `EntrySplit.vue`): an expense, a refund or a transfer; payers with
 * their amounts (an expense's amount is their sum), items with exact amounts and their own splits, the rest by its split
 * (by shares, percent or amount, "everyone equally" as one line), another currency with its rate, and the shares in the
 * base currency as the save seals them. Every rule and the editor's state are core's [EditorModel] (opened by the Pot's
 * model); this draws it and sends the edits. [entryId] null adds a new entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorSheet(
    view: PotView, model: AppModel, entryId: String?,
    /** Receipts it starts with: what another app shared to this Pot (ADR 0129). */
    initialPending: List<me.raddatz.veyq.mobile.expenses.PotSession.PendingReceipt> = emptyList(),
    /** Runs once the entry was saved, before [onClose] (a share's new expense hands back that it landed). */
    onSaved: () -> Unit = {},
    onClose: () -> Unit,
) {
    val sep = remember { separator() }
    val editor = remember(entryId) { model.entryEditor(entryId, sep, initialPending) }
    if (editor == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
        return
    }
    androidx.compose.runtime.DisposableEffect(editor) { onDispose { editor.close() } }
    // Set at once after each edit (a text field's value must not lag what was typed) and from the model's flow (the rate).
    var s by remember(editor) { mutableStateOf(editor.current) }
    androidx.compose.runtime.LaunchedEffect(editor) { editor.state.collect { s = it } }
    fun edit(c: EditorChange) {
        editor.change(c)
        s = editor.current
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val form = s.form
    val editable = s.editable
    val people = s.people
    fun at(field: String, item: Int? = null) = s.refusal?.takeIf { it.at == field && it.item == item }
    val nameOf = people.associate { it.id to it.name }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalEditable provides editable) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(when { s.isNew -> R.string.ed_new_title; editable -> R.string.ed_edit_title; else -> R.string.ed_view_title }),
                style = MaterialTheme.typography.titleLarge,
            )
            if (s.invalid) Text(stringResource(R.string.ed_invalid), color = MaterialTheme.colorScheme.error)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Kind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = form.kind == k, onClick = { edit(EditorChange.Kind(k)) }, enabled = editable,
                        shape = SegmentedButtonDefaults.itemShape(i, Kind.entries.size),
                    ) {
                        Text(stringResource(when (k) { Kind.EXPENSE -> R.string.ed_expense; Kind.REFUND -> R.string.ed_refund; Kind.TRANSFER -> R.string.pot_transfer }))
                    }
                }
            }
            OutlinedTextField(
                form.title, { edit(EditorChange.Title(it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(stringResource(if (form.kind == Kind.TRANSFER) R.string.ed_note else R.string.entry_title)) },
                isError = at("title") != null, supportingText = at("title")?.let { { Text(problemText(it.problem)) } },
            )
            DateField(form.date, at("date")?.problem) { edit(EditorChange.Day(it)) }
            if (form.kind != Kind.TRANSFER) CategoryField(view, form.category) { edit(EditorChange.Category(it)) }
            CurrencyField(view, form, at("rate")?.problem, s.rate, ::edit)

            if (form.kind == Kind.TRANSFER) {
                OutlinedTextField(
                    form.amount, { edit(EditorChange.Amount(it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = editable,
                    label = { Text(stringResource(R.string.entry_amount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = at("amount") != null, supportingText = at("amount")?.let { { Text(problemText(it.problem)) } },
                )
                PersonField(stringResource(R.string.ed_from), people, form.from) { edit(EditorChange.From(it)) }
                PersonField(stringResource(R.string.ed_to), people, form.to) { edit(EditorChange.To(it)) }
                at("transfer")?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
            } else {
                Payers(form, people, at("payers")?.problem, ::edit)
                Text(
                    stringResource(R.string.entry_amount) + ": " + money(form.payersTotal, form.currency, form.minorUnits),
                    fontWeight = FontWeight.SemiBold,
                )
                form.items.forEachIndexed { i, item ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            item.label, { v -> edit(EditorChange.ItemLabel(i, v)) },
                            singleLine = true, label = { Text(stringResource(R.string.ed_item)) }, modifier = Modifier.weight(1f), enabled = editable,
                        )
                        OutlinedTextField(
                            item.amount, { v -> edit(EditorChange.ItemAmount(i, v)) },
                            singleLine = true, label = { Text(stringResource(R.string.entry_amount)) }, modifier = Modifier.width(120.dp).padding(start = 8.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = editable,
                        )
                        if (editable) IconButton(onClick = { edit(EditorChange.RemoveItem(i)) }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.ed_remove_item))
                        }
                    }
                    SplitEditor(item.split, i, people, form.itemAmount(item), form, sep, ::edit)
                    at("item", i)?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
                }
                if (editable) TextButton(onClick = { edit(EditorChange.AddItem) }) { Text(stringResource(R.string.ed_add_item)) }
                at("items")?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
                if (form.showsRest) {
                    HorizontalDivider()
                    Text(stringResource(if (form.items.isEmpty()) R.string.ed_whole else R.string.ed_rest), fontWeight = FontWeight.SemiBold)
                    SplitEditor(form.rest, null, people, form.restLeft, form, sep, ::edit)
                    at("rest")?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
                }
                Preview(view, form, s.preview, nameOf)
            }
            HorizontalDivider()
            EntryReceipts(view, model, s, onPick = { editor.addReceipts(it); s = editor.current }, onDrop = { editor.dropReceipt(it); s = editor.current })
            s.refusal?.takeIf { it.at == null }?.let { Text(problemText(it.problem), color = MaterialTheme.colorScheme.error) }
            s.notice?.let { Text(say(it), color = MaterialTheme.colorScheme.error) }
            if (entryId != null) {
                HorizontalDivider()
                EntryHistory(view, entryId)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!s.isNew && editable) {
                    TextButton(onClick = { editor.askDelete(); s = editor.current }, enabled = !s.saving) {
                        Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onClose) { Text(stringResource(if (editable) R.string.entry_cancel else R.string.receipts_close)) }
                if (editable) Button(
                    onClick = {
                        scope.launch {
                            val saved = editor.save()
                            s = editor.current
                            if (saved.done) {
                                // A receipt the space's storage had no room for was left out; the entry is saved.
                                saved.notice?.let { android.widget.Toast.makeText(context, context.text(it), android.widget.Toast.LENGTH_LONG).show() }
                                onSaved()
                                onClose()
                            }
                        }
                    },
                    enabled = !s.saving,
                ) { Text(stringResource(R.string.entry_save)) }
            }
        }
        }
    }
    if (s.asking) {
        AlertDialog(
            onDismissRequest = { editor.cancelAsk(); s = editor.current },
            text = { Text(stringResource(R.string.entry_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { if (editor.delete()) onClose() else s = editor.current } }) {
                    Text(stringResource(R.string.entry_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { editor.cancelAsk(); s = editor.current }) { Text(stringResource(R.string.entry_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: String, problem: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val millis = runCatching { java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            runCatching { java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrDefault(date),
            {}, readOnly = true, label = { Text(stringResource(R.string.ed_date)) }, modifier = Modifier.fillMaxWidth(), enabled = LocalEditable.current,
            isError = problem != null, supportingText = problem?.let { { Text(problemText(it)) } },
        )
        // A read-only field takes no click: a transparent button over it opens the picker.
        TextButton(onClick = { open = true }, enabled = LocalEditable.current, modifier = Modifier.matchParentSize()) {}
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
    val editable = LocalEditable.current
    ExposedDropdownMenuBox(open, { open = it && editable }, modifier) {
        OutlinedTextField(
            options.firstOrNull { it.first == selected }?.second ?: "", {}, readOnly = true, label = { Text(label) }, enabled = editable,
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
private fun CurrencyField(view: PotView, form: EntryForm, problem: String?, note: EditorModel.Rate?, edit: (EditorChange) -> Unit) {
    val base = view.currency ?: return
    Row(verticalAlignment = Alignment.Top) {
        Dropdown(stringResource(R.string.ed_currency), currencies.map { it to it }, form.currency, Modifier.width(140.dp)) { code -> edit(EditorChange.Currency(code)) }
        if (form.currency != base) {
            OutlinedTextField(
                form.rate, { edit(EditorChange.RateTyped(it)) }, singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp), enabled = LocalEditable.current,
                label = { Text(stringResource(R.string.ed_rate, form.currency, base)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = problem != null,
                supportingText = {
                    Text(
                        problem?.let { problemText(it) } ?: when (note?.state) {
                            EditorModel.RateState.FILLING -> stringResource(R.string.ed_rate_loading)
                            EditorModel.RateState.FILLED -> stringResource(R.string.ed_rate_filled, localDay(note.day.orEmpty()))
                            EditorModel.RateState.MISSING -> stringResource(R.string.ed_rate_missing, form.currency)
                            null -> stringResource(R.string.ed_rate_hint)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun Payers(form: EntryForm, people: List<PotView.Person>, problem: String?, edit: (EditorChange) -> Unit) {
    Text(stringResource(if (form.kind == Kind.REFUND) R.string.ed_received_by else R.string.entry_paid_by), fontWeight = FontWeight.SemiBold)
    form.payers.forEachIndexed { i, p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonField(stringResource(R.string.ed_person), people, p.participant, Modifier.weight(1f)) { id -> edit(EditorChange.Payer(i, id)) }
            OutlinedTextField(
                p.amount, { v -> edit(EditorChange.Paid(i, v)) },
                singleLine = true, label = { Text(stringResource(R.string.entry_amount)) }, modifier = Modifier.width(120.dp).padding(start = 8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = LocalEditable.current,
            )
            if (form.canRemovePayer && LocalEditable.current) {
                IconButton(onClick = { edit(EditorChange.RemovePayer(i)) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.ed_remove_payer, people.firstOrNull { it.id == p.participant }?.name ?: ""))
                }
            }
        }
    }
    problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error) }
    if (form.nextPayer(people.map { it.id }) != null && LocalEditable.current) {
        TextButton(onClick = { edit(EditorChange.AddPayer) }) { Text(stringResource(R.string.ed_add_payer)) }
    }
}

/** A split ([item]'s, or the rest's for null): one line while it is everyone equally, else its mode and one row per person (`EntrySplit.vue`). */
@Composable
private fun SplitEditor(split: FormSplit, item: Int?, people: List<PotView.Person>, part: Long, form: EntryForm, sep: Char, edit: (EditorChange) -> Unit) {
    if (!split.expanded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(say(EntryForm.collapsedWords(split, people)), Modifier.weight(1f))
            if (LocalEditable.current) TextButton(onClick = { edit(EditorChange.Expand(item)) }) { Text(stringResource(R.string.ed_adjust)) }
        }
        return
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Mode.entries.forEachIndexed { i, m ->
            SegmentedButton(selected = split.mode == m, onClick = { edit(EditorChange.SplitMode(item, m)) }, enabled = LocalEditable.current, shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)) {
                Text(stringResource(when (m) { Mode.SHARES -> R.string.ed_by_shares; Mode.PERCENT -> R.string.ed_by_percent; Mode.AMOUNTS -> R.string.ed_by_amount }))
            }
        }
    }
    for (p in people) {
        val row = split.rows.firstOrNull { it.participant == p.id }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(row != null, { on -> edit(EditorChange.Toggle(item, p.id, on)) }, enabled = LocalEditable.current)
            Text(p.name, Modifier.weight(1f))
            if (row != null) {
                val value = when (split.mode) { Mode.SHARES -> row.shares; Mode.PERCENT -> row.percent; Mode.AMOUNTS -> row.amount }
                OutlinedTextField(
                    value, { v -> edit(EditorChange.Share(item, p.id, v)) },
                    singleLine = true, modifier = Modifier.width(110.dp), enabled = LocalEditable.current,
                    keyboardOptions = KeyboardOptions(keyboardType = if (split.mode == Mode.SHARES) KeyboardType.Number else KeyboardType.Decimal),
                    suffix = { if (split.mode == Mode.PERCENT) Text("%") },
                )
            }
        }
    }
    EntryForm.splitHint(split, part, form.currency, form.minorUnits, sep)?.let { Text(say(it), style = MaterialTheme.typography.bodySmall) }
    if (LocalEditable.current) TextButton(onClick = { edit(EditorChange.Equally(item)) }) { Text(stringResource(R.string.ed_everyone_equally)) }
}

/** The shares in the base currency as the save seals them (`sharesPreview`). */
@Composable
private fun Preview(view: PotView, form: EntryForm, rows: List<PotView.ShareRow>, nameOf: Map<String, String>) {
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

private fun localDay(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)
