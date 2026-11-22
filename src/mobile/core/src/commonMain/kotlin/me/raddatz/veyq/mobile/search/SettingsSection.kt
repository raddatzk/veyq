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
 * Sign out ends the list, as it ends the apps' page. The web's sections the apps do not offer (trust reset, moderation,
 * the tenant's) arrive with those sections.
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
    PROFILE("profile", Msg.PROFILE_TITLE, Msg.SEARCH_WORDS_PROFILE),
    ERASE("erase", Msg.ERASURE_TITLE, Msg.SEARCH_WORDS_ERASE),
    /** Signing out on this device (the apps' page ends with it; the web's is its account menu's). */
    SIGN_OUT("sign-out", Msg.DRAWER_SIGN_OUT, Msg.SEARCH_WORDS_SIGN_OUT),
    ;

    /**
     * The gates the page itself reads (`AccountSettings`): [addDevice] while the instance links devices
     * (`core.device-linking`), [storage] where the account keeps an offline store on this device or says why it keeps
     * none, [maps] where the tenant's map services were built, [appearance] on a platform whose page offers it (the phones).
     */
    data class Gates(val addDevice: Boolean, val storage: Boolean, val maps: Boolean, val appearance: Boolean)

    companion object {
        /** The sections the page offers under [gates], in its order. */
        fun offered(gates: Gates): List<SettingsSection> = entries.filter {
            when (it) {
                ADD_DEVICE -> gates.addDevice
                STORAGE -> gates.storage
                MAPS -> gates.maps
                APPEARANCE -> gates.appearance
                SESSIONS, DEVICE, SAVED_KEYS, ACCOUNT, EMAIL, PASSWORD, SECURITY, PROFILE, ERASE, SIGN_OUT -> true
            }
        }

        /** The section of [id], or null. */
        fun of(id: String): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}
