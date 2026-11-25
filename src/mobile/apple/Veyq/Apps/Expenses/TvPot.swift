import SwiftUI
import VeyqCore

/// Expenses on a TV (ADR 0159 decision 3): a Pot read-only — its entries beside its balances, what to settle and its
/// categories, as the member sees them. Nothing is recorded, edited, settled or downloaded: on a TV the Pot's door is
/// view-only (`PotDoor.viewOnly`, the core's), so its view offers no entry, no record and no settings; nothing here takes
/// any away. An entry opens its history, the one thing a reader opens.
@MainActor
enum ExpensesTv {
    static func register(_ registry: TvRegistry.Type) {
        registry.kind(TvKindPage { ref, model in AnyView(TvPotPage(model: model, ref: ref)) }, PotSession.companion.KIND)
    }
}

/// The Pot's model while it shows (core's `PotModel`), its state on the main actor, its live poll.
@MainActor
@Observable
private final class TvPotHeld {
    let pots: PotModel
    private(set) var state: PotModel.State?
    @ObservationIgnored private var watch: PotModel.Watch?
    @ObservationIgnored private var live: PotModel.Watch?
    @ObservationIgnored private let ref: ObjectRef

    init(tenant: TenantSession, ref: ObjectRef) {
        self.ref = ref
        pots = PotModel(tenant: tenant, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        watch = pots.watch { @Sendable [weak self] s in
            nonisolated(unsafe) let s = s
            Task { @MainActor [weak self] in self?.state = s }
        }
        let pots = pots
        Task { @MainActor [weak self] in
            try? await pots.open(ref: ref)
            self?.live = pots.startLive()
        }
    }

    func close() {
        live?.stop()
        watch?.stop()
        pots.close(ref: ref)
    }
}

private struct TvPotPage: View {
    @Bindable var model: TvModel
    let ref: ObjectRef
    @State private var held: TvPotHeld?
    @State private var history: PotView.EntryRow?

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            HStack(alignment: .firstTextBaseline, spacing: 20) {
                Text(ref.name).font(.title2)
                if let cur = held?.state?.view?.currency { Text(say(.potInCurrency, cur)).foregroundStyle(.secondary) }
                Spacer()
                // core's header plan of the node: on a TV the report alone, where the server takes one
                TvNodeHeader(model: model, node: ref.at)
            }
            .focusSection()
            if let state = held?.state {
                if let failure = state.failure { Text(say(failure)).foregroundStyle(Role.danger) }
                if let view = state.view { pot(view) } else if !state.failed { ProgressView() }
            } else {
                ProgressView()
            }
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear {
            guard held == nil, let t = model.tenantSession else { return }
            held = TvPotHeld(tenant: t, ref: ref)
        }
        .onDisappear {
            held?.close()
            held = nil
        }
        .sheet(item: Binding(get: { history.map(TvEntryItem.init) }, set: { if $0 == nil { history = nil } })) { item in
            if let view = held?.state?.view { TvEntryHistory(view: view, entry: item.entry) }
        }
    }

    @ViewBuilder private func pot(_ view: PotView) -> some View {
        HStack(alignment: .top, spacing: 60) {
            // the entries
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    Text(say(.tabExpensesEntries)).font(.headline).foregroundStyle(.secondary)
                    ForEach(view.missing, id: \.self) { m in Text(say(piece: view.missingName(m: m))).foregroundStyle(Role.danger) }
                    if view.entries.isEmpty { Text(say(view.canWrite ? .potNoEntries : .potNoEntriesReadonly)).foregroundStyle(.secondary) }
                    ForEach(view.entries, id: \.id) { e in
                        Button { model.input(); history = e } label: { entryRow(view, e) }
                    }
                }
                .padding(.vertical, 20)
            }
            .focusSection()
            .frame(maxWidth: .infinity)
            // the overview
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    Text(say(.potBalances)).font(.headline).foregroundStyle(.secondary)
                    ForEach(view.balances, id: \.id) { p in
                        Button { model.input() } label: { balanceRow(view, p) }
                    }
                    if let unsent = view.balancesUnsent { Text(say(notice: unsent)).foregroundStyle(.secondary) }
                    if !view.settleUp.isEmpty {
                        Text(say(.potSettleUp)).font(.headline).foregroundStyle(.secondary).padding(.top, 20)
                        ForEach(Array(view.settleUp.enumerated()), id: \.offset) { _, t in
                            Button { model.input() } label: {
                                HStack {
                                    Text(say(notice: view.pays(t: t)))
                                    Spacer()
                                    Text(money(t.amount, view.currency, view.minorUnits)).fontWeight(.semibold)
                                }
                            }
                        }
                    }
                    if !view.byCategory.isEmpty {
                        Text(say(.potByCategory)).font(.headline).foregroundStyle(.secondary).padding(.top, 20)
                        ForEach(view.byCategory, id: \.id) { c in
                            Button { model.input() } label: {
                                HStack { Text(say(piece: view.categoryLabel(id: c.id))); Spacer(); Text(money(c.amount, view.currency, view.minorUnits)) }
                            }
                        }
                        HStack {
                            Text(say(.potTotal)).fontWeight(.semibold)
                            Spacer()
                            Text(money(view.total, view.currency, view.minorUnits)).fontWeight(.semibold)
                        }
                        .padding(.horizontal, 30)
                    }
                }
                .padding(.vertical, 20)
            }
            .focusSection()
            .frame(maxWidth: .infinity)
        }
    }

    private func entryRow(_ view: PotView, _ e: PotView.EntryRow) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text(e.title ?? say(.potTransfer)).fontWeight(.semibold)
                Text(pieceLine(view.line(e: e))).font(.caption).foregroundStyle(.secondary)
                if let badge = view.badge(e: e) { Text(say(notice: badge.notice)).font(.caption2).foregroundStyle(badge.danger ? Role.danger : .secondary) }
                if let unsent = view.unsentMark(row: e) { Text(say(notice: unsent)).font(.caption2).foregroundStyle(.secondary) }
            }
            Spacer()
            if let amount = e.amount { Text(money(amount.int64Value, view.currency, view.minorUnits)).fontWeight(.semibold) }
        }
    }

    private func balanceRow(_ view: PotView, _ p: PotView.PersonRow) -> some View {
        let b = view.balance(p: p)
        return HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(say(piece: PotView.companion.person(name: p.name))).fontWeight(.semibold)
                Text(pieceLine(view.balanceFacts(p: p))).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text(say(notice: b.notice)).foregroundStyle(b.tone == .owes ? Role.danger : b.tone == .owed ? Role.primary : Role.muted)
        }
    }
}

private struct TvEntryItem: Identifiable {
    let entry: PotView.EntryRow
    var id: String { entry.id }
}

/// An entry's history (the reader's one open): each revision's line, as the phone's history shows it.
private struct TvEntryHistory: View {
    let view: PotView
    let entry: PotView.EntryRow

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(entry.title ?? say(.potTransfer)).font(.title2)
                ForEach(Array(view.history(entryId: entry.id).enumerated()), id: \.offset) { _, r in
                    Button {} label: { Text(pieceLine(view.historyLine(r: r))) }
                }
            }
            .padding(60)
        }
    }
}
