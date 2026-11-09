import SwiftUI

extension View {
    /// A view's one creating action — "Add entry", "New pot" — the way Apple's HIG has it: a "+" in the navigation bar's
    /// primary place, as Notes, Reminders and Mail put "new". iOS has no floating action button; the web's and Android's
    /// green button at the bottom is theirs. `scripts/validate_mobile_platform_guidelines.py` refuses a button floated over
    /// a view's bottom instead.
    ///
    /// The bar shows the symbol alone, so `label` names it for VoiceOver and, on the Mac, as its tooltip.
    /// - Parameter shown: whether the action is offered at all (a reader of a Pot adds nothing).
    func createAction(_ label: LocalizedStringKey, shown: Bool = true, action: @escaping () -> Void) -> some View {
        toolbar {
            if shown {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: action) { Image(systemName: "plus") }
                        .accessibilityLabel(Text(label))
                        .help(Text(label))
                }
            }
        }
    }

    /// The creating action where a level creates more than one kind (the web's "New" menu): the "+" opens a menu of
    /// `entries`, as Files' "+" does.
    func createMenu<Entries: View>(_ label: LocalizedStringKey, shown: Bool = true, @ViewBuilder entries: @escaping () -> Entries) -> some View {
        toolbar {
            if shown {
                ToolbarItem(placement: .primaryAction) {
                    Menu(content: entries) { Image(systemName: "plus") }
                        .accessibilityLabel(Text(label))
                        .help(Text(label))
                }
            }
        }
    }
}
