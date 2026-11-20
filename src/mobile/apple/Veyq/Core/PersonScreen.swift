import SwiftUI
import VeyqCore

/// A person's profile (ADR 0144 decision 11; the web's `PersonProfile.vue`), core's frame — their picture at its largest,
/// their name, `~username` under a real name, a former member said so — and then the section of the application the
/// profile lists (the registry's `personSection`: Memories' "Memories with {name}", `PersonMemoriesPage`), which draws the
/// page with the frame. Drawn where the shell's place is (core's `Shell.person`, over what was on screen: back returns
/// there), its title the person's name; no bar of its own.
struct PersonScreen: View {
    @Bindable var model: AppModel
    let userId: String
    /// *Edit profile* on one's own page (ADR 0148 decision 2): the same sheet as Settings' *Your profile*.
    @State private var editing = false

    private var mine: Bool { model.myUserId == userId }

    private var profile: PersonProfile? { model.profile(userId) }

    var body: some View {
        Group {
            if let profile {
                // the safety number below the name (ADR 0148 decision 3): one's own to show, another's to compare
                let framed = PersonFrame(
                    frame: AnyView(frame(profile)),
                    safety: profile.former ? nil : AnyView(SafetyNumberSection(model: model, userId: userId, name: Self.name(profile), mine: mine))
                )
                if let section = profile.sections.lazy.compactMap({ Registry.application($0)?.personSection }).first {
                    section(profile, model, framed)
                } else {
                    List {
                        framed.frame
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                        if let safety = framed.safety { safety }
                    }
                }
            }
        }
        .navigationTitle(profile.map(Self.name) ?? "")
        .toolbar {
            if mine {
                ToolbarItem(placement: .automatic) {
                    Button(say(.profileEdit)) { editing = true }
                }
            }
        }
        .sheet(isPresented: $editing) { ProfileSheet(model: model) }
    }

    /// The name a profile carries: the directory's, else the former or the unknown member's words.
    static func name(_ profile: PersonProfile) -> String {
        profile.name ?? say(profile.former ? .memoryParticipantsFormer : .peopleUnknownMember)
    }

    /// Core's frame: the picture at its largest, the name, `~username` under a real name, a former member's hint.
    private func frame(_ profile: PersonProfile) -> some View {
        VStack(spacing: 6) {
            PersonAvatar(name: Self.name(profile), userId: profile.former ? nil : profile.userId, size: 112)
            Text(Self.name(profile)).font(.title2.weight(.bold)).multilineTextAlignment(.center).padding(.top, 6)
            if let handle = profile.handle { Text(handle).font(.subheadline).foregroundStyle(Role.muted) }
            if profile.former { Text(say(.peopleFormerMemberHint)).font(.subheadline).foregroundStyle(Role.muted) }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }
}
