import SwiftUI
import VeyqCore

/// The editor's form as SwiftUI binds it; core's `EntryForm` decides every rule (`kotlin` hands it over).
struct FormState {
    struct Payer: Hashable { var participant: String; var amount: String }
    struct Row: Hashable { var participant: String; var shares = "1"; var percent = ""; var amount = "" }
    struct SplitState: Hashable { var mode: EntryForm.Mode; var rows: [Row]; var expanded: Bool }
    struct ItemState: Hashable { var label: String; var amount: String; var split: SplitState }

    var kind: EntryForm.Kind
    var title: String
    var date: Date
    var category: String
    var currency: String
    var minorUnits: Int32
    var rate: String
    var amount: String
    var payers: [Payer]
    var items: [ItemState]
    var rest: SplitState
    var from: String
    var to: String
    var receipts: [String]
    var refunds: String?

    private static let day: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func dayString(_ d: Date) -> String { day.string(from: d) }

    init(_ f: EntryForm) {
        kind = f.kind
        title = f.title
        date = Self.day.date(from: f.date) ?? Date()
        category = f.category
        currency = f.currency
        minorUnits = f.minorUnits
        rate = f.rate
        amount = f.amount
        payers = f.payers.map { Payer(participant: $0.participant, amount: $0.amount) }
        items = f.items.map { ItemState(label: $0.label, amount: $0.amount, split: Self.split($0.split)) }
        rest = Self.split(f.rest)
        from = f.from
        to = f.to
        receipts = f.receipts
        refunds = f.refunds
    }

    static func split(_ s: EntryForm.FormSplit) -> SplitState {
        SplitState(mode: s.mode, rows: s.rows.map { Row(participant: $0.participant, shares: $0.shares, percent: $0.percent, amount: $0.amount) }, expanded: s.expanded)
    }

    static func kotlin(_ s: SplitState) -> EntryForm.FormSplit {
        EntryForm.FormSplit(mode: s.mode, rows: s.rows.map { EntryForm.SplitRow(participant: $0.participant, shares: $0.shares, percent: $0.percent, amount: $0.amount) }, expanded: s.expanded)
    }

    var kotlin: EntryForm {
        EntryForm(
            kind: kind, title: title, date: Self.dayString(date), category: category, currency: currency, minorUnits: minorUnits,
            rate: rate, amount: amount,
            payers: payers.map { EntryForm.Payer(participant: $0.participant, amount: $0.amount) },
            items: items.map { EntryForm.FormItem(label: $0.label, amount: $0.amount, split: Self.kotlin($0.split)) },
            rest: Self.kotlin(rest), from: from, to: to, receipts: receipts, refunds: refunds
        )
    }
}

/// The reader's decimal separator as a UTF-16 unit (a Kotlin `Char`), as amounts are typed over.
var decimalSeparator: unichar { (Locale.current.decimalSeparator ?? ".").utf16.first ?? 46 }

/// A problem of the form or the write path in words (the web's `expenses.entry.problem.*`).
func problemText(_ problem: String) -> String { say(Notices.shared.entryProblem(problem: problem)) }

/// The entry editor (the web's `EntryEditor.vue` and `EntrySplit.vue`): an expense, a refund or a transfer; payers with
/// their amounts (an expense's amount is their sum), items with exact amounts and their own splits, the rest by its split
/// (by shares, percent or amount, "everyone equally" as one line), another currency with its typed rate, and the shares
/// in the base currency as the save seals them. Every rule is core's `EntryForm`; a refusal is shown at its field.
struct EntryEditor: View {
    @Bindable var model: AppModel
    let view: PotView
    /// The entry being edited and the revision it is based on; nil adds a new one.
    let entryId: String?
    let basedOn: Int64?
    let people: [PotView.Person]
    @State var form: FormState
    /// The entry does not add up (core's `Ledger.Status.INVALID`): said above it, as the web's editor says it.
    var invalid = false
    /// Receipts it starts with: what another app shared to this Pot (ADR 0126).
    var initialPending: [PotSession.PendingReceipt] = []
    /// Runs once the entry was saved (a share's new expense hands back that it landed).
    var onSaved: (() -> Void)? = nil
    @State private var tried = false
    /// What is said under a filled or missing rate.
    enum RateNote: Equatable { case loading, filled(String), missing(String) }
    @State private var rateNote: RateNote?
    @State private var pending: [PotSession.PendingReceipt] = []
    @State private var receiptsUI = ReceiptsUI()
    @Environment(\.dismiss) private var dismiss

    /// The currencies offered, by code: core's one table (the web's `Intl`).
    private static var currencies: [String] { Currencies.shared.known }
    private static var defaults: [String] { RevisionFormat.shared.DEFAULT_CATEGORIES }

    /// The minor-unit digits of `code` (the euro 2, the yen 0, the dinar 3): core's one table.
    private static func minorUnits(_ code: String) -> Int32 { Currencies.shared.minorUnits(code: code) }

    private var ids: [String] { people.map(\.id) }
    /// A reader sees the same entry read only: rights remove functions, never a reader's own screen (inbox #401).
    private var editable: Bool { view.canWrite }
    private var refusal: EntryForm.Refusal? { view.check(form: form.kotlin) }

    private func shown(_ at: String, _ item: Int? = nil) -> String? {
        guard tried, let r = refusal, r.at == at, r.item?.intValue == item else { return nil }
        return r.problem
    }

    private func name(_ id: String) -> String { people.first { $0.id == id }?.name ?? "" }

    var body: some View {
        NavigationStack {
            Form {
                if invalid { Section { Text(say(.edInvalid)).foregroundStyle(Role.danger) } }
                Picker(say(.entryKind), selection: Binding(get: { form.kind }, set: { k in form = FormState(form.kotlin.withKind(kind: k, separator: decimalSeparator))  })) {
                    Text(say(.edExpense)).tag(EntryForm.Kind.expense)
                    Text(say(.edRefund)).tag(EntryForm.Kind.refund)
                    Text(say(.potTransfer)).tag(EntryForm.Kind.transfer)
                }
                .pickerStyle(.segmented)
                .disabled(!editable)
                Section {
                    TextField(form.kind == .transfer ? say(.edNote) : say(.entryTitle), text: $form.title)
                    problem(shown("title"))
                    DatePicker(say(.edDate), selection: $form.date, displayedComponents: .date)
                    if form.kind != .transfer {
                        Picker(say(.entryCategory), selection: $form.category) {
                            ForEach(Self.defaults, id: \.self) { Text(categoryName($0, view)).tag($0) }
                            ForEach(Array(view.ownCategories.enumerated()), id: \.offset) { _, c in
                                Text(c.second as String? ?? "").tag(c.first as String? ?? "")
                            }
                        }
                    }
                    currencyFields
                }
                .disabled(!editable)
                Group { if form.kind == .transfer { transferFields } else { expenseFields } }.disabled(!editable)
                EntryReceipts(view: view, kept: $form.receipts, pending: $pending, ui: $receiptsUI, editable: editable)
                if let message = model.pot?.message { Section { Text(Self.explain(message)).foregroundStyle(Role.danger) } }
                if tried, let r = refusal, r.at == nil { Section { problem(r.problem) } }
                if let id = entryId, let based = basedOn {
                    Section { EntryHistory(view: view, entryId: id) }
                    if editable {
                    Section {
                        Button(say(.peopleDeleteAccept), role: .destructive) {
                            Task { if await model.potWrite({ try await $0.deleteEntry(entryId: id, basedOn: based) }) { dismiss() } }
                        }
                        .disabled(model.pot?.saving == true)
                    }
                    }
                }
            }
            .formStyle(.grouped)
            .modifier(ReceiptPresenters(model: model, view: view, kept: $form.receipts, pending: $pending, ui: $receiptsUI))
            .navigationTitle(entryId == nil ? say(.potAdd) : editable ? say(.edEditTitle) : say(.edViewTitle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(editable ? say(.signinCancel) : say(.receiptsClose)) { model.clearPotMessage(); dismiss() } }
                if editable {
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.entrySave)) {
                        tried = true
                        guard refusal == nil else { return }
                        let f = form.kotlin
                        let id = entryId
                        let based = basedOn.map { KotlinLong(value: $0) }
                        let picked = pending
                        Task {
                            if await model.potWrite({ try await $0.saveEntry(entryId: id, form: f, basedOn: based, pending: picked) }) {
                                onSaved?()
                                // a receipt the space's storage had no room for was left out; the entry is saved, the editor closes
                                if let left = Notices.shared.receiptsSkipped(names: model.skippedReceipts) {
                                    model.notice = AppModel.Notice(text: say(notice: left))
                                }
                                dismiss()
                            }
                        }
                    }
                    .disabled(model.pot?.saving == true)
                }
                }
            }
        }
        .tint(Role.accent)
        .onAppear { if pending.isEmpty && !initialPending.isEmpty { pending = initialPending } }
        // Another currency with an empty rate: the day's table fills it (one request a device and day, at most).
        .task(id: form.currency) {
            let code = form.currency
            guard ExchangeRates.shared.wanted(ratesOffered: view.ratesOffered, base: view.currency, currency: code, rate: form.rate) else {
                rateNote = nil
                return
            }
            rateNote = .loading
            let filled = await model.rateFor(code)
            guard form.currency == code else { return }
            let sep = (Locale.current.decimalSeparator ?? ".").utf16.first ?? 46
            let fill = ExchangeRates.shared.fill(answer: filled, rate: form.rate, separator: sep)
            if let rate = fill.rate { form.rate = rate }
            rateNote = fill.filledOn.map { .filled($0) } ?? (fill.missing ? .missing(code) : nil)
        }
    }

    @ViewBuilder private func problem(_ p: String?) -> some View {
        if let p { Text(problemText(p)).font(.caption).foregroundStyle(Role.danger) }
    }

    @ViewBuilder private var currencyFields: some View {
        if let base = view.currency {
            Picker(say(.edCurrency), selection: Binding(get: { form.currency }, set: { code in
                form = FormState(form.kotlin.withCurrency(
                    currency: code, minorUnits: Self.minorUnits(code), base: Ledger.BaseCurrency(currency: base, minorUnits: view.minorUnits)
                ))
            })) {
                ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
            }
            if form.currency != base {
                TextField(say(.edRate, form.currency, base), text: $form.rate)
                #if os(iOS)
                    .keyboardType(.decimalPad)
                #endif
                if let p = shown("rate") { problem(p) } else {
                    switch rateNote {
                    case .loading: Text(say(.edRateLoading)).font(.caption).foregroundStyle(Role.muted)
                    case .filled(let day): Text(say(.edRateFilled, localDay(day))).font(.caption).foregroundStyle(Role.muted)
                    case .missing(let code): Text(say(.edRateMissing, code)).font(.caption).foregroundStyle(Role.muted)
                    case nil: Text(say(.edRateHint)).font(.caption).foregroundStyle(Role.muted)
                    }
                }
            }
        }
    }

    @ViewBuilder private var transferFields: some View {
        Section {
            TextField(say(.entryAmount), text: $form.amount)
            #if os(iOS)
                .keyboardType(.decimalPad)
            #endif
            problem(shown("amount"))
            Picker(say(.edFrom), selection: $form.from) { ForEach(people, id: \.id) { Text($0.name).tag($0.id) } }
            Picker(say(.edTo), selection: $form.to) { ForEach(people, id: \.id) { Text($0.name).tag($0.id) } }
            problem(shown("transfer"))
        }
    }

    @ViewBuilder private var expenseFields: some View {
        Section {
            ForEach(form.payers.indices, id: \.self) { i in
                HStack {
                    Picker(say(.edPerson), selection: $form.payers[i].participant) { ForEach(people, id: \.id) { Text($0.name).tag($0.id) } }
                        .labelsHidden()
                    TextField(say(.entryAmount), text: $form.payers[i].amount)
                        .multilineTextAlignment(.trailing)
                    #if os(iOS)
                        .keyboardType(.decimalPad)
                    #endif
                    if form.kotlin.canRemovePayer && editable {
                        Button { form.payers.remove(at: i) } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(say(.peopleRemove, name(form.payers[i].participant)))
                    }
                }
            }
            if editable, let unused = form.kotlin.nextPayer(people: people.map(\.id)) {
                Button(say(.edAddPayer)) { form.payers.append(.init(participant: unused, amount: "")) }
            }
            problem(shown("payers"))
        } header: {
            Text(form.kind == .refund ? say(.edReceivedBy) : say(.entryPaidBy))
        } footer: {
            Text(say(.entryAmountTotal, money(form.kotlin.payersTotal, form.currency, form.minorUnits))).fontWeight(.semibold)
        }
        ForEach(form.items.indices, id: \.self) { i in
            Section {
                HStack {
                    TextField(say(.edItem), text: $form.items[i].label)
                    TextField(say(.entryAmount), text: $form.items[i].amount)
                        .multilineTextAlignment(.trailing)
                    #if os(iOS)
                        .keyboardType(.decimalPad)
                    #endif
                }
                SplitEditor(split: $form.items[i].split, people: people, part: form.kotlin.itemAmount(item: form.kotlin.items[i]), currency: form.currency, minorUnits: form.minorUnits)
                problem(shown("item", i))
                if editable { Button(say(.edRemoveItem), role: .destructive) { form.items.remove(at: i) } }
            } header: { Text(say(.edItem)) }
        }
        Section {
            if editable { Button(say(.edAddItem)) { form.items.append(FormState.split(EntryForm.companion.emptyItem(people: ids).split).asItem) } }
            problem(shown("items"))
        }
        let restLeft = form.kotlin.restLeft
        if form.kotlin.showsRest {
            Section {
                SplitEditor(split: $form.rest, people: people, part: restLeft, currency: form.currency, minorUnits: form.minorUnits)
                problem(shown("rest"))
            } header: { Text(form.items.isEmpty ? say(.edWhole) : say(.edRest)) }
        }
        let rows = view.preview(form: form.kotlin)
        if !rows.isEmpty {
            Section {
                ForEach(rows, id: \.id) { r in
                    HStack {
                        Text(r.name)
                        Spacer()
                        if r.given != 0 {
                            Text(verbatim: "\(form.kind == .refund ? say(.edReceivedColumn) : say(.edPaidColumn)) \(money(r.given, view.currency, view.minorUnits))")
                                .foregroundStyle(Role.muted)
                        }
                        Text(money(r.share, view.currency, view.minorUnits)).fontWeight(.semibold)
                    }
                }
            } header: { Text(say(.edSharesIn, view.currency ?? "")) }
        }
    }

    static func explain(_ outcome: String) -> String { say(Notices.shared.entryRefused(outcome: outcome)) }
}

private extension FormState.SplitState {
    var asItem: FormState.ItemState { .init(label: "", amount: "", split: self) }
}

/// A split: one line while it is everyone equally, else its mode and one row per person (`EntrySplit.vue`).
struct SplitEditor: View {
    @Binding var split: FormState.SplitState
    let people: [PotView.Person]
    let part: Int64
    let currency: String
    let minorUnits: Int32
    /// False for a reader (the editor disables its fields): no Adjust, no "equally among all".
    @Environment(\.isEnabled) private var editable

    var body: some View {
        if !split.expanded {
            HStack {
                Text(say(notice: EntryForm.companion.collapsedWords(split: FormState.kotlin(split), people: people)))
                Spacer()
                if editable { Button(say(.edAdjust)) { split.expanded = true }.buttonStyle(.borderless) }
            }
        } else {
            Picker(say(.edSplitTitle), selection: $split.mode) {
                Text(say(.edByShares)).tag(EntryForm.Mode.shares)
                Text(say(.edByPercent)).tag(EntryForm.Mode.percent)
                Text(say(.entryAmount)).tag(EntryForm.Mode.amounts)
            }
            .pickerStyle(.segmented)
            ForEach(people, id: \.id) { p in
                HStack {
                    Toggle(p.name, isOn: Binding(
                        get: { split.rows.contains { $0.participant == p.id } },
                        set: { on in
                            split = FormState.split(EntryForm.companion.toggled(split: FormState.kotlin(split), person: p.id, on: on, order: people.map(\.id)))
                        }
                    ))
                    if let i = split.rows.firstIndex(where: { $0.participant == p.id }) {
                        TextField("", text: value(i))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 90)
                        #if os(iOS)
                            .keyboardType(split.mode == .shares ? .numberPad : .decimalPad)
                        #endif
                        if split.mode == .percent { Text("%") }
                    }
                }
            }
            let k = FormState.kotlin(split)
            if let hint = EntryForm.companion.splitHint(split: k, part: part, currency: currency, minorUnits: minorUnits, separator: decimalSeparator) {
                Text(say(notice: hint)).font(.caption)
            }
            if editable {
                Button(say(.edEveryoneEqually)) {
                    split = FormState.split(EntryForm.companion.everyoneEqually(split: k, everyone: people.map(\.id)))
                }
            }
        }
    }

    private func value(_ i: Int) -> Binding<String> {
        switch split.mode {
        case .percent: $split.rows[i].percent
        case .amounts: $split.rows[i].amount
        default: $split.rows[i].shares
        }
    }
}
