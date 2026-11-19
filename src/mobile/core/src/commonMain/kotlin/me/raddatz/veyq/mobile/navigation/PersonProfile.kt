package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.session.Person

/**
 * A person's profile, core's frame (ADR 0144 decision 11; the web's `PersonProfile.vue`): who [userId] is as the tenant's
 * directory names them — the display name (real name, else username), the picture at its largest (the members'
 * pictures by user id, else the letter), `~username` under a real name — and then the [sections] of every application
 * that claims one ([ApplicationShape.personSection]), each the application's own model. Core names no application here.
 *
 * Opened for a member only ([Shell.openPerson]): from a current member in a Memory's participants or a photo's uploader;
 * a former member and a guest open nothing. One's own profile is the same frame. Its *Edit profile* (`ProfileModel`) and
 * the safety number below the name (`PersonTrust`) are models of their own that each platform draws beside this frame
 * (ADR 0148 decisions 2 and 3). Offline it names the person from the directory the session
 * read before (decision 14); nothing of it is written to disk.
 */
class PersonProfile(
    val userId: String,
    /** The member as the directory lists them; null for an id it does not list. */
    val person: Person?,
    /** Whether the directory was read: without it nobody is called a former member — the name is unknown. */
    private val named: Boolean,
    /** The applications whose sections follow the frame, in the registry's order. */
    val sections: List<ApplicationId>,
) {
    /** The title: the display name, or null — then the former-member words ([former]) or the unknown-member ones. */
    val name: String? get() = person?.displayName

    /** An id the read directory does not list: a former member (greyed; the sections still run, nothing links to them). */
    val former: Boolean get() = named && person == null

    /** `~username` under a real name; null where the title already is the username, or nobody is named. */
    val handle: String? get() = person?.takeIf { it.displayName != it.username }?.let { "~" + it.username }

    companion object {
        /** [userId]'s frame over the tenant's [people] (empty: not read) with the sections of the applications not [off]. */
        fun of(userId: String, people: Map<String, Person>, off: Set<String>): PersonProfile =
            PersonProfile(userId, people[userId], people.isNotEmpty(), personSections(off))
    }
}
