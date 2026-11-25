import SwiftUI
import VeyqCore

/// A TV's own settings (ADR 0159 decision 4, ADR 0160 decision 7), not the account page: *Lock*, *Add someone*, the
/// account's name on the picker, *Change PIN*, *Remove from this TV*. A PIN's change asks the current PIN, then the new
/// one twice; a wrong current PIN says the tries left, the tenth removes the account here (the TV locks).
struct TvSettings: View {
    @Bindable var model: TvModel
    @State private var name = ""
    @State private var changing: Step?
    @State private var said: String?
    @State private var asksRemove = false

    private enum Step { case old, new(String), again(String, String) }

    var body: some View {
        if let step = changing {
            pin(step)
        } else {
            list
        }
    }

    private var list: some View {
        List {
            Section {
                Button { model.input(); model.lock() } label: { Label(say(.tvLock), systemImage: "lock") }
                Button { model.input(); model.addServer() } label: { Label(say(.tvPickerAdd), systemImage: "person.badge.plus") }
            }
            if let label = model.pickerLabel {
                Section(say(.tvPickerName)) {
                    TextField(label, text: $name)
                        .autocorrectionDisabled()
                    Button(say(.tvPickerNameSave)) {
                        model.input()
                        model.renameOnPicker(name.trimmingCharacters(in: .whitespaces))
                        name = ""
                    }
                }
                Section {
                    Button { model.input(); said = nil; changing = .old } label: { Label(say(.tvChangePin), systemImage: "number") }
                }
            }
            Section {
                Button(role: .destructive) { asksRemove = true } label: { Label(say(.tvRemoveHere), systemImage: "person.crop.circle.badge.minus") }
            }
            if let said { Section { Text(said) } }
        }
        .frame(maxWidth: 1200)
        .confirmationDialog(say(.tvRemoveHere), isPresented: $asksRemove) {
            Button(say(.tvRemoveHere), role: .destructive) { model.removeHere() }
            Button(say(.signinCancel), role: .cancel) {}
        }
    }

    @ViewBuilder private func pin(_ step: Step) -> some View {
        Group {
            switch step {
            case .old:
                TvPinPad(title: say(.tvPinOldTitle), hint: nil, error: said, working: model.trustWorking) { pin in
                    said = nil
                    changing = .new(pin)
                }
                .id("old")
            case let .new(old):
                TvPinPad(title: say(.tvPinSetTitle), hint: say(.tvPinSetHint), error: nil, working: model.trustWorking) { pin in
                    changing = .again(old, pin)
                }
                .id("new")
            case let .again(old, new):
                TvPinPad(title: say(.tvPinConfirmTitle), hint: nil, error: nil, working: model.trustWorking) { pin in
                    guard pin == new else { said = say(.tvPinMismatch); changing = .new(old); return }
                    model.changePin(old: old, new: new) { change in answered(change) }
                }
                .id("again")
            }
        }
        .onExitCommand { changing = nil }
    }

    private func answered(_ change: VeyqAccount.PinChange?) {
        guard let change else { said = say(.trustFailed); changing = nil; return }
        switch change.outcome {
        case .changed:
            said = say(.tvPinChanged)
            changing = nil
        case .wrong:
            said = change.attemptsLeft.map { say(.tvPinWrong, $0) } ?? say(.tvPinWrong, 0)
            changing = .old
        case .gone:
            // the account left this TV: the model locked it
            changing = nil
        default:
            said = say(.trustFailed)
            changing = nil
        }
    }
}
