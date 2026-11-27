import SwiftUI
import VeyqCore

/// Settings' *Who's who* (core's `SettingsSection.WHOS_WHO` over `PersonTrust.whosWho`, the web's `PeopleOverviewPanel`;
/// inbox #967): every other member of the open space with the one state their key has here — its badge, *New* beside it,
/// who verified them ("you" first), a new identity, a replaced key —, in core's order, as a grouped list. A row, and its
/// *Compare Safety Numbers* where this account has not verified them, opens their profile, where the compare lives; core
/// decides every word and whether the compare is offered. Pushed in iOS's Settings, a sheet over the Mac's.
struct WhosWhoView: View {
    let model: AppModel
    @State private var loading = true
    @State private var read: WhosWho?

    var body: some View {
        List {
            Section {
                if loading {
                    HStack(spacing: 12) { ProgressView(); Text(say(.whosWhoLoading)) }
                } else if let said = read?.said ?? (read == nil ? Msg.whosWhoLoadFailed : nil) {
                    Text(say(said)).accessibilityIdentifier("whos-who-said")
                    if read?.retry ?? true {
                        Button(say(.peopleRetry)) { Task { await load() } }.accessibilityIdentifier("whos-who-retry")
                    }
                } else if let read {
                    ForEach(read.people, id: \.userId) { row in WhosWhoRowView(row: row, open: { open(row.userId) }) }
                }
            } footer: {
                Text(say(.whosWhoSub))
            }
        }
        .environment(\.avatars, model.avatars)
        .navigationTitle(say(.whosWhoTitle))
        .accessibilityIdentifier("whos-who")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        read = await model.whosWho()
        loading = false
    }

    private func open(_ userId: String) { model.openPersonFromWhosWho(userId) }
}

/// One member's row: the avatar, the name and `@username`, the state's label, then what core says beside it.
private struct WhosWhoRowView: View {
    let row: WhosWho.Other
    let open: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PersonAvatar(name: row.name, userId: row.userId, size: 40)
            VStack(alignment: .leading, spacing: 4) {
                Button(action: open) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.name).foregroundStyle(.primary)
                        Text(row.subtitle).font(.subheadline).foregroundStyle(Role.muted)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(say(.whosWhoOpenProfile, row.name))
                HStack(spacing: 8) {
                    if let word = row.stateWord {
                        Label(say(word), systemImage: symbol)
                            .font(.footnote)
                            .foregroundStyle(row.state == .cantConfirm ? Role.danger : row.state == .verified ? Role.accent : Role.muted)
                            .accessibilityIdentifier("whos-who-state")
                    }
                    if row.isNew { Text(say(.safetyStateNew)).font(.footnote).foregroundStyle(Role.muted) }
                }
                if let note = row.note { Text(say(note)).font(.footnote).foregroundStyle(Role.muted) }
                if row.state == .verified {
                    let names = (row.verifiedByMe ? [say(.safetyVerifiedByYou)] : []) + row.verifiedBy
                    Text(say(.safetyVerifiedBy, names.joined(separator: ", "))).font(.footnote).foregroundStyle(Role.muted)
                }
                if let at = row.rotatedAt {
                    Text(say(.trustRotated, shownDayOf(at.int64Value))).font(.footnote).foregroundStyle(Role.muted)
                }
                if row.replaced { Text(say(.safetyReplaced)).font(.footnote).foregroundStyle(Role.muted) }
                if row.compare {
                    Button(say(.whosWhoCompare), action: open)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .accessibilityIdentifier("whos-who-compare")
                }
            }
        }
        .accessibilityIdentifier("whos-who-\(row.userId)")
    }

    private var symbol: String {
        row.state == .cantConfirm ? "exclamationmark.triangle" : row.state == .verified ? "checkmark.seal.fill" : "seal"
    }
}
