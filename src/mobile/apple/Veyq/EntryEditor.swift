import SwiftUI
import VeyqCore

/// The reader's decimal separator as a UTF-16 unit (a Kotlin `Char`), as amounts are typed over.
var decimalSeparator: unichar { (Locale.current.decimalSeparator ?? ".").utf16.first ?? 46 }

/// A problem of the form or the write path in words (the web's `expenses.entry.problem.*`).
func problemText(_ problem: String) -> String { say(Notices.shared.entryProblem(problem: problem)) }

/// A picked `yyyy-MM-dd` as the date picker takes it, and back: the form keeps the ISO text, the picker a `Date`.
private enum PickedDay {
    private static let format: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func date(_ iso: String) -> Date? { format.date(from: iso) }
    static func iso(_ date: Date) -> String { format.string(from: date) }
}

/// A split's index on the wire: an item's, or nil for the rest's.
private func at(_ item: Int?) -> KotlinInt? { item.map { KotlinInt(value: Int32($0)) } }

/// The entry editor (the web's `EntryEditor.vue` and `EntrySplit.vue`): an expense, a refund or a transfer; payers with
/// their amounts (an expense's amount is their sum), items with exact amounts and their own splits, the rest by its split
/// (by shares, percent or amount, "everyone equally" as one line), another currency with its rate, and the shares in the
/// base currency as the save seals them. Every rule and the editor's state are core's `EntryEditor`, which the app model
/// holds and watches (`openEditor` before this sheet shows); this draws `model.editorState` and sends the edits.
struct EntryEditorSheet: View {
    @Bindable var model: AppModel
    let view: PotView
    /// The entry being edited; nil adds a new one.
    let entryId: String?
    /// Runs once the entry was saved (a share's new expense hands back that it landed).
    var onSaved: (() -> Void)? = nil
    @State private var receiptsUI = ReceiptsUI()
    @Environment(\.dismiss) private var dismiss

    /// The currencies offered, by code: core's one table (the web's `Intl`).
    private static var currencies: [String] { Currencies.shared.known }
    private static var defaults: [String] { RevisionFormat.shared.DEFAULT_CATEGORIES }

    var body: some View {
        NavigationStack {
            if let s = model.editorState {
                form(s)
            } else {
                ProgressView()
            }
        }
        .tint(Role.accent)
    }

    private func shown(_ s: EntryEditor.State, _ at: String, _ item: Int? = nil) -> String? {
        guard let r = s.refusal, r.at == at, r.item?.intValue == item else { return nil }
        return r.problem
    }

    private func bind(_ get: @escaping () -> String, _ change: @escaping (String) -> EditorChange) -> Binding<String> {
        Binding(get: get, set: { model.edit(change($0)) })
    }

    @ViewBuilder private func form(_ s: EntryEditor.State) -> some View {
        let f = s.form
        let editable = s.editable
        Form {
            if s.invalid { Section { Text(say(.edInvalid)).foregroundStyle(Role.danger) } }
            Picker(say(.entryKind), selection: Binding(get: { f.kind }, set: { model.edit(EditorChange.Kind(kind: $0)) })) {
                Text(say(.edExpense)).tag(EntryForm.Kind.expense)
                Text(say(.edRefund)).tag(EntryForm.Kind.refund)
                Text(say(.potTransfer)).tag(EntryForm.Kind.transfer)
            }
            .pickerStyle(.segmented)
            .disabled(!editable)
            Section {
                TextField(f.kind == .transfer ? say(.edNote) : say(.entryTitle), text: bind({ model.editorState?.form.title ?? "" }) { EditorChange.Title(text: $0) })
                problem(shown(s, "title"))
                DatePicker(say(.edDate), selection: Binding(
                    get: { PickedDay.date(model.editorState?.form.date ?? "") ?? Date.now },
                    set: { model.edit(EditorChange.Day(iso: PickedDay.iso($0))) }
                ), displayedComponents: .date)
                problem(shown(s, "date"))
                if f.kind != .transfer {
                    Picker(say(.entryCategory), selection: Binding(get: { f.category }, set: { model.edit(EditorChange.Category(id: $0)) })) {
                        ForEach(Self.defaults, id: \.self) { Text(categoryName($0, view)).tag($0) }
                        ForEach(Array(view.ownCategories.enumerated()), id: \.offset) { _, c in
                            Text(c.second as String? ?? "").tag(c.first as String? ?? "")
                        }
                    }
                }
                currencyFields(s)
            }
            .disabled(!editable)
            Group { if f.kind == .transfer { transferFields(s) } else { expenseFields(s) } }.disabled(!editable)
            EntryReceipts(model: model, view: view, state: s, ui: $receiptsUI)
            if let notice = s.notice { Section { Text(say(notice: notice)).foregroundStyle(Role.danger) } }
            if let r = s.refusal, r.at == nil { Section { problem(r.problem) } }
            if let id = entryId {
                Section { EntryHistory(view: view, entryId: id) }
                if editable {
                    Section {
                        Button(say(.entryDelete), role: .destructive) { model.askDelete() }
                            .disabled(s.saving)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .modifier(ReceiptPresenters(model: model, view: view, ui: $receiptsUI))
        .navigationTitle(s.isNew ? say(.potAdd) : editable ? say(.edEditTitle) : say(.edViewTitle))
        // The ask is ended by its buttons only: a binding that cancels on dismissal runs before the button's action.
        .alert(say(.entryDeleteConfirm), isPresented: Binding(get: { model.editorState?.asking == true }, set: { _ in })) {
            Button(say(.entryDelete), role: .destructive) {
                Task { if await model.deleteEntry() { dismiss() } }
            }
            Button(say(.entryCancel), role: .cancel) { model.cancelAsk() }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(editable ? say(.signinCancel) : say(.receiptsClose)) { dismiss() } }
            if editable {
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.entrySave)) {
                        Task {
                            guard let saved = await model.saveEntry(), saved.done else { return }
                            onSaved?()
                            // a receipt the space's storage had no room for was left out; the entry is saved, the editor closes
                            if let left = saved.notice { model.notice = AppModel.Notice(text: say(notice: left)) }
                            dismiss()
                        }
                    }
                    .disabled(s.saving)
                }
            }
        }
    }

    @ViewBuilder private func problem(_ p: String?) -> some View {
        if let p { Text(problemText(p)).font(.caption).foregroundStyle(Role.danger) }
    }

    @ViewBuilder private func currencyFields(_ s: EntryEditor.State) -> some View {
        let f = s.form
        if let base = view.currency {
            Picker(say(.edCurrency), selection: Binding(get: { f.currency }, set: { model.edit(EditorChange.Currency(code: $0)) })) {
                ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
            }
            if f.currency != base {
                TextField(say(.edRate, f.currency, base), text: bind({ model.editorState?.form.rate ?? "" }) { EditorChange.RateTyped(text: $0) })
                #if os(iOS)
                    .keyboardType(.decimalPad)
                #endif
                if let p = shown(s, "rate") { problem(p) } else {
                    switch s.rate?.state {
                    case .filling: Text(say(.edRateLoading)).font(.caption).foregroundStyle(Role.muted)
                    case .filled: Text(say(.edRateFilled, localDay(s.rate?.day ?? ""))).font(.caption).foregroundStyle(Role.muted)
                    case .missing: Text(say(.edRateMissing, f.currency)).font(.caption).foregroundStyle(Role.muted)
                    default: Text(say(.edRateHint)).font(.caption).foregroundStyle(Role.muted)
                    }
                }
            }
        }
    }

    @ViewBuilder private func transferFields(_ s: EntryEditor.State) -> some View {
        let f = s.form
        Section {
            TextField(say(.entryAmount), text: bind({ model.editorState?.form.amount ?? "" }) { EditorChange.Amount(text: $0) })
            #if os(iOS)
                .keyboardType(.decimalPad)
            #endif
            problem(shown(s, "amount"))
            Picker(say(.edFrom), selection: Binding(get: { f.from }, set: { model.edit(EditorChange.From(person: $0)) })) { ForEach(s.people, id: \.id) { Text($0.name).tag($0.id) } }
            Picker(say(.edTo), selection: Binding(get: { f.to }, set: { model.edit(EditorChange.To(person: $0)) })) { ForEach(s.people, id: \.id) { Text($0.name).tag($0.id) } }
            problem(shown(s, "transfer"))
        }
    }

    @ViewBuilder private func expenseFields(_ s: EntryEditor.State) -> some View {
        let f = s.form
        let editable = s.editable
        let people = s.people
        Section {
            ForEach(f.payers.indices, id: \.self) { i in
                HStack {
                    Picker(say(.edPerson), selection: Binding(get: { f.payers[i].participant }, set: { model.edit(EditorChange.Payer(index: Int32(i), person: $0)) })) {
                        ForEach(people, id: \.id) { Text($0.name).tag($0.id) }
                    }
                    .labelsHidden()
                    TextField(say(.entryAmount), text: bind({ model.editorState?.form.payers[safe: i]?.amount ?? "" }) { EditorChange.Paid(index: Int32(i), text: $0) })
                        .multilineTextAlignment(.trailing)
                    #if os(iOS)
                        .keyboardType(.decimalPad)
                    #endif
                    if f.canRemovePayer && editable {
                        Button { model.edit(EditorChange.RemovePayer(index: Int32(i))) } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(say(.peopleRemove, people.first { $0.id == f.payers[i].participant }?.name ?? ""))
                    }
                }
            }
            if editable, f.nextPayer(people: people.map(\.id)) != nil {
                Button(say(.edAddPayer)) { model.edit(EditorChange.AddPayer.shared) }
            }
            problem(shown(s, "payers"))
        } header: {
            Text(f.kind == .refund ? say(.edReceivedBy) : say(.entryPaidBy))
        } footer: {
            Text(say(.entryAmountTotal, money(f.payersTotal, f.currency, f.minorUnits))).fontWeight(.semibold)
        }
        ForEach(f.items.indices, id: \.self) { i in
            Section {
                HStack {
                    TextField(say(.edItem), text: bind({ model.editorState?.form.items[safe: i]?.label ?? "" }) { EditorChange.ItemLabel(index: Int32(i), text: $0) })
                    TextField(say(.entryAmount), text: bind({ model.editorState?.form.items[safe: i]?.amount ?? "" }) { EditorChange.ItemAmount(index: Int32(i), text: $0) })
                        .multilineTextAlignment(.trailing)
                    #if os(iOS)
                        .keyboardType(.decimalPad)
                    #endif
                }
                SplitEditor(model: model, split: f.items[i].split, item: i, people: people, part: f.itemAmount(item: f.items[i]), currency: f.currency, minorUnits: f.minorUnits)
                problem(shown(s, "item", i))
                if editable { Button(say(.edRemoveItem), role: .destructive) { model.edit(EditorChange.RemoveItem(index: Int32(i))) } }
            } header: { Text(say(.edItem)) }
        }
        Section {
            if editable { Button(say(.edAddItem)) { model.edit(EditorChange.AddItem.shared) } }
            problem(shown(s, "items"))
        }
        if f.showsRest {
            Section {
                SplitEditor(model: model, split: f.rest, item: nil, people: people, part: f.restLeft, currency: f.currency, minorUnits: f.minorUnits)
                problem(shown(s, "rest"))
            } header: { Text(f.items.isEmpty ? say(.edWhole) : say(.edRest)) }
        }
        if !s.preview.isEmpty {
            Section {
                ForEach(s.preview, id: \.id) { r in
                    HStack {
                        Text(r.name)
                        Spacer()
                        if r.given != 0 {
                            Text(verbatim: "\(f.kind == .refund ? say(.edReceivedColumn) : say(.edPaidColumn)) \(money(r.given, view.currency, view.minorUnits))")
                                .foregroundStyle(Role.muted)
                        }
                        Text(money(r.share, view.currency, view.minorUnits)).fontWeight(.semibold)
                    }
                }
            } header: { Text(say(.edSharesIn, view.currency ?? "")) }
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

/// A split ([item]'s, or the rest's for nil): one line while it is everyone equally, else its mode and one row per person
/// (`EntrySplit.vue`). Every change goes to core's editor.
struct SplitEditor: View {
    @Bindable var model: AppModel
    let split: EntryForm.FormSplit
    let item: Int?
    let people: [PotView.Person]
    let part: Int64
    let currency: String
    let minorUnits: Int32
    /// False for a reader (the editor disables its fields): no Adjust, no "equally among all".
    @Environment(\.isEnabled) private var editable

    var body: some View {
        if !split.expanded {
            HStack {
                Text(say(notice: EntryForm.companion.collapsedWords(split: split, people: people)))
                Spacer()
                if editable { Button(say(.edAdjust)) { model.edit(EditorChange.Expand(item: at(item))) }.buttonStyle(.borderless) }
            }
        } else {
            Picker(say(.edSplitTitle), selection: Binding(get: { split.mode }, set: { model.edit(EditorChange.SplitMode(item: at(item), mode: $0)) })) {
                Text(say(.edByShares)).tag(EntryForm.Mode.shares)
                Text(say(.edByPercent)).tag(EntryForm.Mode.percent)
                Text(say(.entryAmount)).tag(EntryForm.Mode.amounts)
            }
            .pickerStyle(.segmented)
            ForEach(people, id: \.id) { p in
                HStack {
                    Toggle(p.name, isOn: Binding(
                        get: { split.rows.contains { $0.participant == p.id } },
                        set: { model.edit(EditorChange.Toggle(item: at(item), person: p.id, on: $0)) }
                    ))
                    if split.rows.contains(where: { $0.participant == p.id }) {
                        TextField("", text: Binding(get: { value(p.id) }, set: { model.edit(EditorChange.Share(item: at(item), person: p.id, text: $0)) }))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 90)
                        #if os(iOS)
                            .keyboardType(split.mode == .shares ? .numberPad : .decimalPad)
                        #endif
                        if split.mode == .percent { Text(verbatim: "%") }
                    }
                }
            }
            if let hint = EntryForm.companion.splitHint(split: split, part: part, currency: currency, minorUnits: minorUnits, separator: decimalSeparator) {
                Text(say(notice: hint)).font(.caption)
            }
            if editable {
                Button(say(.edEveryoneEqually)) { model.edit(EditorChange.Equally(item: at(item))) }
            }
        }
    }

    /// What is typed for `person` in the split's mode, as the editor holds it now (never a value this view kept).
    private func value(_ person: String) -> String {
        let form = model.editorState?.form
        let s = item.map { form?.items[safe: $0]?.split } ?? form?.rest
        guard let row = s?.rows.first(where: { $0.participant == person }) else { return "" }
        switch s?.mode {
        case .percent: return row.percent
        case .amounts: return row.amount
        default: return row.shares
        }
    }
}
