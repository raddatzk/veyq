import SwiftUI
import VeyqCore

/// A level's row while the level picks (core's `LevelSelection`, inbox #1049): the row as it is drawn, its check before it,
/// the whole row one toggle — as Files' Select.
struct SelectableRow<Label: View>: View {
    let selected: Bool
    let toggle: () -> Void
    @ViewBuilder let label: Label

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Role.accent : Role.muted)
                label
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The selection's bar (core's `LevelSelection.Picking`, the web's `LevelView` selection bar): the count, *All* and *None*,
/// every action of its plan in its order — each enabled where some selected row allows it, with what it cannot reach under
/// the bar — and *Cancel*. Each action opens core's own sheet over the selected rows it reaches; the delete asks its one
/// question first.
struct SelectionBar: View {
    @Bindable var model: AppModel
    let selection: LevelSelection.Picking
    /// A link guest's level (#1063): its selection's calls and its move sheet the guest level's (core's `GuestLevelMoves`,
    /// whose plan holds a move only); nil: the shell's level.
    var guest: GuestLevelMoves? = nil
    @State private var moving: MoveAsked?
    @State private var reporting: Asked?
    @State private var deleting: SelectionTrash?

    /// A report asked, with the first node it reaches (the sheet's own is the selection's).
    private struct Asked: Identifiable {
        let id = UUID()
        let ref: NodeAt
    }

    /// A move or copy asked, with the first node it reaches (the sheet's own is the selection's).
    private struct MoveAsked: Identifiable {
        let id = UUID()
        let ref: NodeAt
        let copying: Bool
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { controls }
                ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 12) { controls } }
            }
            ForEach(Array(selection.entries.enumerated()), id: \.offset) { _, a in
                if let cannot = a.cannot { Text(say(notice: cannot)).font(.caption).foregroundStyle(Role.muted) }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.bar)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(say(selection.label))
        .sheet(item: $moving) { m in
            NodeMoveSheet(model: model, ref: m.ref, copying: m.copying) { [model, guest] in guest.map { $0.selectionSheet() } ?? model.selectionMoveSheet(copying: m.copying) }
        }
        .sheet(item: $reporting) { asked in
            ReportSheet(model: model, ref: asked.ref) { [model] in model.selectionReportSheet() }
        }
        .alert(deleting.map { say(notice: $0.title) } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button(say(.entryDelete), role: .destructive) {
                if let d = deleting { Task { await model.trash(d) } }
            }
            Button(say(.entryCancel), role: .cancel) {}
        } message: {
            if let d = deleting { Text(say(notice: d.question)) }
        }
    }

    @ViewBuilder private var controls: some View {
        Text(say(notice: selection.count)).fontWeight(.semibold)
            .accessibilityAddTraits(.updatesFrequently)
        Button(say(selection.all)) { if let guest { guest.selectAll() } else { model.selectAll() } }
        Button(say(selection.none)) { if let guest { guest.selectNone() } else { model.selectNone() } }
        ForEach(Array(selection.entries.enumerated()), id: \.offset) { _, a in
            action(a)
        }
        Spacer(minLength: 0)
        Button(say(selection.cancel)) { if let guest { guest.selectCancel() } else { model.selectCancel() } }
    }

    /// One action of the bar: its words, enabled where some selected row allows it; what it opens is core's.
    @ViewBuilder private func action(_ a: LevelSelection.PickingBarAction) -> some View {
        switch a.action.entry {
        case .move:
            Button(say(a.word)) { ask(a.action, copying: false) }.disabled(!a.enabled)
        case .theCopy:
            Button(say(a.word)) { ask(a.action, copying: true) }.disabled(!a.enabled)
        case .report:
            Button(say(a.word)) { reporting = model.selectedFirst(a.action).map { Asked(ref: $0) } }.disabled(!a.enabled)
        case .delete_:
            Button(say(a.word), role: .destructive) { deleting = model.selectionTrash() }.disabled(!a.enabled)
        }
    }

    private func ask(_ action: SelectionAction, copying: Bool) {
        // a guest's sheet is the guest level's own (`make`), titled by it: no node of the shell's to name it by
        if guest != nil {
            moving = MoveAsked(ref: NodeAt(scopeId: "", nodeId: "", kind: "", name: ""), copying: copying)
            return
        }
        guard let first = model.selectedFirst(action) else { return }
        moving = MoveAsked(ref: first, copying: copying)
    }
}
