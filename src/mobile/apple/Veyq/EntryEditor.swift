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
func problemText(_ problem: String) -> LocalizedStringKey {
    switch problem {
    case "title": "Give the entry a title."
    case "date": "Pick a date."
    case "amount": "Type an amount greater than zero, with no more decimals than the currency has."
    case "rate": "Type the rate as a number greater than zero."
    case "payers": "Say who paid, and how much each."
    case "split": "Say who shares the rest."
    case "split-shares": "Shares are whole numbers from 1."
    case "split-percents": "Type each percent above zero, with at most two decimals."
    case "split-amounts": "Type each amount above zero, with no more decimals than the currency has."
    case "items": "Give every item a name, an amount and its people."
    case "transfer": "Pick two different people."
    case "payers-unbalanced": "What the payers paid does not add up to the amount."
    case "receivers-unbalanced": "What was received does not add up to the amount."
    case "items-above-total": "The items add up to more than the amount."
    case "rest-unallocated": "The rest is shared by no one."
    case "percent-unbalanced": "The percents of a split do not add up to 100 %."
    case "amounts-unbalanced": "The amounts of a split do not add up to what it splits."
    case "named-twice": "Someone is named twice."
    default: "The entry names someone who is not in this pot."
    }
}

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
    @State private var tried = false
    @Environment(\.dismiss) private var dismiss

    private static let currencies: [String] = Array(Set(Locale.commonISOCurrencyCodes.filter { $0.count == 3 && $0 == $0.uppercased() })).sorted()
    private static let defaults = ["food", "groceries", "accommodation", "transport", "leisure", "household", "other"]

    private static func minorUnits(_ code: String) -> Int32 {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = code
        return Int32(min(4, max(0, f.maximumFractionDigits)))
    }

    private var ids: [String] { people.map(\.id) }
    private var refusal: EntryForm.Refusal? { view.check(form: form.kotlin) }

    private func shown(_ at: String, _ item: Int? = nil) -> String? {
        guard tried, let r = refusal, r.at == at, r.item?.intValue == item else { return nil }
        return r.problem
    }

    private func name(_ id: String) -> String { people.first { $0.id == id }?.name ?? "" }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Kind", selection: Binding(get: { form.kind }, set: { k in form = FormState(form.kotlin.withKind(kind: k, separator: decimalSeparator))  })) {
                    Text("Expense").tag(EntryForm.Kind.expense)
                    Text("Refund").tag(EntryForm.Kind.refund)
                    Text("Transfer").tag(EntryForm.Kind.transfer)
                }
                .pickerStyle(.segmented)
                Section {
                    TextField(form.kind == .transfer ? "Note (optional)" : "Title", text: $form.title)
                    problem(shown("title"))
                    DatePicker("Date", selection: $form.date, displayedComponents: .date)
                    if form.kind != .transfer {
                        Picker("Category", selection: $form.category) {
                            ForEach(Self.defaults, id: \.self) { Text(categoryName($0, view)).tag($0) }
                            ForEach(Array(view.ownCategories.enumerated()), id: \.offset) { _, c in
                                Text(c.second as String? ?? "").tag(c.first as String? ?? "")
                            }
                        }
                    }
                    currencyFields
                }
                if form.kind == .transfer { transferFields } else { expenseFields }
                if let message = model.pot?.message { Section { Text(Self.explain(message)).foregroundStyle(Role.danger) } }
                if tried, let r = refusal, r.at == nil { Section { problem(r.problem) } }
                if let id = entryId, let based = basedOn {
                    Section { EntryHistory(view: view, entryId: id) }
                    Section {
                        Button("Delete", role: .destructive) {
                            Task { if await model.potWrite({ try await $0.deleteEntry(entryId: id, basedOn: based) }) { dismiss() } }
                        }
                        .disabled(model.pot?.saving == true)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(entryId == nil ? "Add entry" : "Edit entry")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.clearPotMessage(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        tried = true
                        guard refusal == nil else { return }
                        let f = form.kotlin
                        let id = entryId
                        let based = basedOn.map { KotlinLong(value: $0) }
                        Task { if await model.potWrite({ try await $0.saveEntry(entryId: id, form: f, basedOn: based) }) { dismiss() } }
                    }
                    .disabled(model.pot?.saving == true)
                }
            }
        }
        .tint(Role.accent)
    }

    @ViewBuilder private func problem(_ p: String?) -> some View {
        if let p { Text(problemText(p)).font(.caption).foregroundStyle(Role.danger) }
    }

    @ViewBuilder private var currencyFields: some View {
        if let base = view.currency {
            Picker("Currency", selection: Binding(get: { form.currency }, set: { code in
                form = FormState(form.kotlin.withCurrency(
                    currency: code, minorUnits: Self.minorUnits(code), base: Ledger.BaseCurrency(currency: base, minorUnits: view.minorUnits)
                ))
            })) {
                ForEach(Self.currencies, id: \.self) { Text($0).tag($0) }
            }
            if form.currency != base {
                TextField("Rate: 1 \(form.currency) in \(base)", text: $form.rate)
                #if os(iOS)
                    .keyboardType(.decimalPad)
                #endif
                if let p = shown("rate") { problem(p) } else {
                    Text("The entry keeps this rate; a later rate never changes it.").font(.caption).foregroundStyle(Role.muted)
                }
            }
        }
    }

    @ViewBuilder private var transferFields: some View {
        Section {
            TextField("Amount", text: $form.amount)
            #if os(iOS)
                .keyboardType(.decimalPad)
            #endif
            problem(shown("amount"))
            Picker("From", selection: $form.from) { ForEach(people, id: \.id) { Text($0.name).tag($0.id) } }
            Picker("To", selection: $form.to) { ForEach(people, id: \.id) { Text($0.name).tag($0.id) } }
            problem(shown("transfer"))
        }
    }

    @ViewBuilder private var expenseFields: some View {
        Section {
            ForEach(form.payers.indices, id: \.self) { i in
                HStack {
                    Picker("Person", selection: $form.payers[i].participant) { ForEach(people, id: \.id) { Text($0.name).tag($0.id) } }
                        .labelsHidden()
                    TextField("Amount", text: $form.payers[i].amount)
                        .multilineTextAlignment(.trailing)
                    #if os(iOS)
                        .keyboardType(.decimalPad)
                    #endif
                    if form.payers.count > 1 {
                        Button { form.payers.remove(at: i) } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Remove \(name(form.payers[i].participant))")
                    }
                }
            }
            if let unused = people.first(where: { p in !form.payers.contains { $0.participant == p.id } }) {
                Button("Add a payer") { form.payers.append(.init(participant: unused.id, amount: "")) }
            }
            problem(shown("payers"))
        } header: {
            Text(form.kind == .refund ? "Received by" : "Paid by")
        } footer: {
            Text("Amount: \(money(form.kotlin.payersTotal, form.currency, form.minorUnits))").fontWeight(.semibold)
        }
        ForEach(form.items.indices, id: \.self) { i in
            Section {
                HStack {
                    TextField("Item", text: $form.items[i].label)
                    TextField("Amount", text: $form.items[i].amount)
                        .multilineTextAlignment(.trailing)
                    #if os(iOS)
                        .keyboardType(.decimalPad)
                    #endif
                }
                SplitEditor(split: $form.items[i].split, people: people, part: form.kotlin.itemAmount(item: form.kotlin.items[i]), currency: form.currency, minorUnits: form.minorUnits)
                problem(shown("item", i))
                Button("Remove item", role: .destructive) { form.items.remove(at: i) }
            } header: { Text("Item") }
        }
        Section {
            Button("Add item") { form.items.append(FormState.split(EntryForm.companion.emptyItem(people: ids).split).asItem) }
            problem(shown("items"))
        }
        let restLeft = form.kotlin.restLeft
        if form.items.isEmpty || restLeft > 0 {
            Section {
                SplitEditor(split: $form.rest, people: people, part: restLeft, currency: form.currency, minorUnits: form.minorUnits)
                problem(shown("rest"))
            } header: { Text(form.items.isEmpty ? "The whole amount" : "The rest") }
        }
        let rows = view.preview(form: form.kotlin)
        if !rows.isEmpty {
            Section {
                ForEach(rows, id: \.id) { r in
                    HStack {
                        Text(r.name)
                        Spacer()
                        if r.given != 0 {
                            Text("\(form.kind == .refund ? String(localized: "received") : String(localized: "paid")) \(money(r.given, view.currency, view.minorUnits))")
                                .foregroundStyle(Role.muted)
                        }
                        Text(money(r.share, view.currency, view.minorUnits)).fontWeight(.semibold)
                    }
                }
            } header: { Text("Shares in \(view.currency ?? "")") }
        }
    }

    static func explain(_ outcome: String) -> LocalizedStringKey {
        switch outcome {
        case "STALE": "Someone else changed this entry. Here is their version; save again to put yours on top."
        case "BLOCKED": "Blocked by the operator: this pot or entry takes no changes. Nothing was saved."
        case "BUSY": "The pot’s key is being renewed. Try again in a moment."
        default: "The change was not saved. Please try again."
        }
    }
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

    var body: some View {
        if !split.expanded {
            HStack {
                if split.rows.count == 1 {
                    Text("Only \(people.first { $0.id == split.rows[0].participant }?.name ?? "")")
                } else {
                    Text("All \(split.rows.count) people, equally")
                }
                Spacer()
                Button("Adjust") { split.expanded = true }.buttonStyle(.borderless)
            }
        } else {
            Picker("Split", selection: $split.mode) {
                Text("Shares").tag(EntryForm.Mode.shares)
                Text("Percent").tag(EntryForm.Mode.percent)
                Text("Amount").tag(EntryForm.Mode.amounts)
            }
            .pickerStyle(.segmented)
            ForEach(people, id: \.id) { p in
                HStack {
                    Toggle(p.name, isOn: Binding(
                        get: { split.rows.contains { $0.participant == p.id } },
                        set: { on in
                            if on {
                                let ids = people.map(\.id)
                                split.rows = ids.compactMap { id in split.rows.first { $0.participant == id } ?? (id == p.id ? .init(participant: id) : nil) }
                            } else {
                                split.rows.removeAll { $0.participant == p.id }
                            }
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
            if split.mode == .percent, EntryForm.companion.percentTotal(split: k) != 10_000 {
                Text("The percents add up to \(Amounts.shared.text(amount: EntryForm.companion.percentTotal(split: k), minorUnits: 2, separator: decimalSeparator)) %, not 100 %.")
                    .font(.caption)
            }
            if split.mode == .amounts, part > 0, EntryForm.companion.amountsTotal(split: k, minorUnits: minorUnits) != part {
                Text("The amounts add up to \(money(EntryForm.companion.amountsTotal(split: k, minorUnits: minorUnits), currency, minorUnits)), not \(money(part, currency, minorUnits)).")
                    .font(.caption)
            }
            Button("Split equally among all") {
                split = FormState.split(EntryForm.companion.everyoneEqually(split: k, everyone: people.map(\.id)))
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
