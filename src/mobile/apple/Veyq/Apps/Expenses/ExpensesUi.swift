import SwiftUI
import VeyqCore

/// Expenses' UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerExpenses`): a Pot's page — its
/// currency, cover and copy in core's header, its Entries, Overview and Settings (`PotScreen`), a link's Pot —, its own
/// list of Pots and New pot.
@MainActor
enum ExpensesUi {
    /// Expenses' Pot: its currency under its name, its cover and copy while Expenses is on, its ZIP.
    private static let pot = KindPage(header: { node, model in
        guard let state = model.pot, state.ref.nodeId == node.nodeId, let view = state.view else { return nil }
        let ref = state.ref
        return HeaderContribution(
            subtitle: view.subtitle.map { say(notice: $0) },
            // a cover and a copy write: core offers them where Expenses is on (the web's `canCopy`, `expensesRights`)
            facts: AppKinds.Facts(canWrite: view.canWrite, hasContent: true),
            download: { await model.downloadPot(ref, to: $0) }
        )
    }, body: { ref, key, model in AnyView(PotScreen(model: model, ref: ref, tab: key)) }, guest: { page, _, model in
        guard page is GuestPage.Pot else { return AnyView(EmptyView()) }
        return AnyView(GuestPotView(model: model, ref: .guest(page.node, .expenses)))
    })

    static func register(_ registry: Registry.Type) {
        registry.kind(pot, PotSession.companion.KIND)
        registry.application(ApplicationUi(
            id: .expenses,
            list: { model, _ in AnyView(ExpensesList(model: model)) },
            // New pot at the shell's place now (`NewPotSheet`), the web's `createDialog` of a Pot
            creates: [.pot: { model, _, _ in AnyView(NewPotSheet(model: model)) }]
        ))
    }
}

/// Expenses' own list (its Pots); its New is the drawer's (core's `LevelPlan`: an application's list offers none).
struct ExpensesList: View {
    @Bindable var model: AppModel

    var body: some View {
        ArrangedListView(model: model, list: model.applicationList(.expenses), onOpen: model.open, key: "app:\(ApplicationId.expenses)")
            .navigationTitle(Names.application(.expenses))
    }
}

/// A Pot of a link: the member's Pot page over the guest's Pot (the app model reads and writes through it while it shows),
/// its Entries, Overview and — where the core offers them (`PotView.settingsOffered`: a guest who writes, #1035) —
/// Settings as a segmented control of its own; the guest's screen has no bar.
private struct GuestPotView: View {
    @Bindable var model: AppModel
    let ref: ObjectRef
    @State private var chosen = "expenses.entries"

    /// The places offered now: Settings once the core offers them.
    private var places: [String] {
        let settings = model.pot.map { $0.ref == ref && $0.view?.settingsOffered == true } ?? false
        return ["expenses.entries", "expenses.overview"] + (settings ? ["expenses.settings"] : [])
    }

    private func word(_ key: String) -> String {
        switch key {
        case "expenses.overview": say(.tabExpensesOverview)
        case "expenses.settings": say(.tabExpensesSettings)
        default: say(.tabExpensesEntries)
        }
    }

    var body: some View {
        let place = places.contains(chosen) ? chosen : "expenses.entries"
        VStack(spacing: 0) {
            Picker(say(.tabExpensesEntries), selection: $chosen) {
                ForEach(places, id: \.self) { Text(word($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding()
            PotScreen(model: model, ref: ref, tab: place)
        }
    }
}
