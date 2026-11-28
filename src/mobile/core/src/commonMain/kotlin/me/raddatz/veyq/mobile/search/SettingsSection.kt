package me.raddatz.veyq.mobile.search

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.words.Msg

/**
 * The account Settings page's sections as the apps offer them (ADR 0146 decision 9; ADR 0125 decision 9: the search offers
 * exactly the sections the page offers now), a plan every platform draws as its page, whole and in this order (ADR 0154
 * decision 2). Each platform's settings page anchors its sections by these [id]s, and a settings result opens the page
 * scrolled to its section; the search offers what [offered] offers — one list, one set of gates. [word] is the section's
 * title (its heading on the page), [keywords] the words it is also found by (comma-separated in the catalog, never shown).
 *
 * **The order is the web's** (`features/settings/sections.ts`' personal items; owner 2026-11-20 on
 * cr-apps-structure-from-core-plans' Q3): appearance, maps, sessions, password, add a device, security, profile, erase —
 * `SettingsOrderIsTheWebsTest` reads it there — with the app-only sections beside their kin: offline storage after maps,
 * this device and saved keys after sessions, the account and its address (the apps' own) where the web's password is;
 * then the tenant's sections the apps offer, in the web's tenant order — the members, Who's who, then the terms; Sign out
 * ends the list, as it ends the apps' page. The web's sections the apps do not offer (moderation, the tenant's others)
 * arrive with those sections.
 */
@me.raddatz.veyq.mobile.navigation.Plan
@Serializable
enum class SettingsSection(val id: String, val word: Msg, val keywords: Msg) {
    APPEARANCE("appearance", Msg.ACCOUNT_SETTINGS_APPEARANCE, Msg.SEARCH_WORDS_APPEARANCE),
    MAPS("maps", Msg.SETTINGS_MAPS, Msg.SEARCH_WORDS_MAPS),
    STORAGE("device-storage", Msg.ACCOUNT_SETTINGS_STORAGE, Msg.SEARCH_WORDS_STORAGE),
    SESSIONS("sessions", Msg.ACCOUNT_SETTINGS_SESSIONS, Msg.SEARCH_WORDS_SESSIONS),
    DEVICE("device", Msg.ACCOUNT_SETTINGS_THIS_DEVICE, Msg.SEARCH_WORDS_DEVICE),
    SAVED_KEYS("saved-keys", Msg.ACCOUNT_SETTINGS_SAVED_KEYS, Msg.SEARCH_WORDS_SAVED_KEYS),
    ACCOUNT("account", Msg.ACCOUNT_SETTINGS_ACCOUNT, Msg.SEARCH_WORDS_ACCOUNT),
    EMAIL("email", Msg.ACCOUNT_SETTINGS_EMAIL, Msg.SEARCH_WORDS_EMAIL),
    PASSWORD("password", Msg.PASSWORD_CHANGE_TITLE, Msg.SEARCH_WORDS_PASSWORD),
    ADD_DEVICE("add-device", Msg.ACCOUNT_SETTINGS_ADD_DEVICE, Msg.SEARCH_WORDS_ADD_DEVICE),
    SECURITY("security", Msg.ROTATION_TITLE, Msg.SEARCH_WORDS_SECURITY),
    /**
     * *Start a list over* (ADR 0028 decision 7; the web's `trust-reset`, after Security): offered only while one of the
     * account's lists is refused on this device ([Gates.trustReset], [me.raddatz.veyq.mobile.session.TrustReset]).
     */
    TRUST_RESET("trust-reset", Msg.TRUST_RESET_TITLE, Msg.SEARCH_WORDS_TRUST_RESET),
    PROFILE("profile", Msg.PROFILE_TITLE, Msg.SEARCH_WORDS_PROFILE),
    ERASE("erase", Msg.ERASURE_TITLE, Msg.SEARCH_WORDS_ERASE),
    /**
     * The open tenant's members (uc.spaces.remove-tenant-member; the web's tenant section `tenant-members`, every
     * member's): the roster with each role, an owner's acts on its rows ([me.raddatz.veyq.mobile.session.TenantMembers]).
     * The web's tenant category leads with it, before Who's who.
     */
    MEMBERS("tenant-members", Msg.MEMBERS_TITLE, Msg.SEARCH_WORDS_MEMBERS),
    /**
     * Who's who in the open tenant (uc.spaces.enforce-verified-identities main flow 5; the web's tenant section
     * `tenant-people`, every member's): every other member's state there, a row opening their profile
     * ([me.raddatz.veyq.mobile.session.PersonTrust.whosWho]). After the personal sections, before the terms, as the web's
     * tenant category orders them.
     */
    WHOS_WHO("tenant-people", Msg.WHOS_WHO_TITLE, Msg.SEARCH_WORDS_WHOS_WHO),
    /**
     * The open tenant's terms, read-only (uc.account.accept-terms; the web's tenant section `terms`, member-readable): the
     * space's own text and the operator section after it ([me.raddatz.veyq.mobile.session.MemberTerms.page]). The web lists
     * it under the tenant's sections, after the personal ones; the apps' page is over one tenant, so it comes after them
     * here, before Sign out.
     */
    TERMS("terms", Msg.TERMS_SETTINGS_TITLE, Msg.SEARCH_WORDS_TERMS),
    /**
     * The caller's groups in the open tenant (en.spaces.group-scope; the web's `groups` category, one section per group
     * there): the list, *Create group* where `core.group-space` is on, and each group's page — its members, an owner's
     * rename, add, roles, removal and deletion ([me.raddatz.veyq.mobile.session.TenantGroups]). One section on the apps'
     * page over one tenant, after the tenant's, as the web's category follows the tenant's.
     */
    GROUPS("groups", Msg.GROUPS_TITLE, Msg.SEARCH_WORDS_GROUPS),
    /**
     * The caller's group invitations (uc.spaces.consent-group-add; the web's `/invitations` page, reached from its
     * sidebar): each proposal's name opened with the caller's identity, *Accept* or *Decline*. A section here: the apps'
     * shell has no place of the web's sidebar badge.
     */
    GROUP_INVITATIONS("group-invitations", Msg.INVITATIONS_TITLE, Msg.SEARCH_WORDS_GROUP_INVITATIONS),
    /**
     * *About veyq* (ADR 0124 decisions 5 and 7): the project's two links the web's footer carries — *Why is veyq secure?*
     * and *Donate* — each opening the project's website in the system's browser, outside the app (`project_*_url`). On every
     * app; the stores' apps ask for a donation here only (no banner).
     */
    ABOUT("about", Msg.ABOUT_TITLE, Msg.SEARCH_WORDS_ABOUT),
    /** Signing out on this device (the apps' page ends with it; the web's is its account menu's). */
    SIGN_OUT("sign-out", Msg.DRAWER_SIGN_OUT, Msg.SEARCH_WORDS_SIGN_OUT),
    ;

    /**
     * The gates the page itself reads (`AccountSettings`): [addDevice] while the instance links devices
     * (`core.device-linking`), [storage] where the account keeps an offline store on this device or says why it keeps
     * none, [maps] where the tenant's map services were built, [trustReset] while one of the account's lists is refused on this device.
     */
    data class Gates(val addDevice: Boolean, val storage: Boolean, val maps: Boolean, val trustReset: Boolean = false)

    companion object {
        /** The sections the page offers under [gates], in its order. */
        fun offered(gates: Gates): List<SettingsSection> = entries.filter {
            when (it) {
                ADD_DEVICE -> gates.addDevice
                STORAGE -> gates.storage
                MAPS -> gates.maps
                TRUST_RESET -> gates.trustReset
                APPEARANCE, SESSIONS, DEVICE, SAVED_KEYS, ACCOUNT, EMAIL, PASSWORD, SECURITY, PROFILE, ERASE, MEMBERS, WHOS_WHO, TERMS, GROUPS, GROUP_INVITATIONS, ABOUT, SIGN_OUT -> true
            }
        }

        /** The section of [id], or null. */
        fun of(id: String): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}
