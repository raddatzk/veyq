package me.raddatz.veyq.mobile.search

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.words.Msg

/**
 * The account Settings page's sections as the apps offer them (ADR 0146 decision 9; ADR 0125 decision 9: the search offers
 * exactly the sections the page offers now). Each platform's settings page anchors its sections by these [id]s, and a
 * settings result opens the page scrolled to its section; the search offers what [offered] offers — one list, one set of
 * gates. [word] is the section's title (its heading on the page), [keywords] the words it is also found by
 * (comma-separated in the catalog, never shown). The web's sections the apps do not offer (profile, password, security,
 * moderation, erase, the tenant's) arrive with those sections.
 */
@Serializable
enum class SettingsSection(val id: String, val word: Msg, val keywords: Msg) {
    ACCOUNT("account", Msg.ACCOUNT_SETTINGS_ACCOUNT, Msg.SEARCH_WORDS_ACCOUNT),
    DEVICE("device", Msg.ACCOUNT_SETTINGS_THIS_DEVICE, Msg.SEARCH_WORDS_DEVICE),
    SESSIONS("sessions", Msg.ACCOUNT_SETTINGS_SESSIONS, Msg.SEARCH_WORDS_SESSIONS),
    SAVED_KEYS("saved-keys", Msg.ACCOUNT_SETTINGS_SAVED_KEYS, Msg.SEARCH_WORDS_SAVED_KEYS),
    ADD_DEVICE("add-device", Msg.ACCOUNT_SETTINGS_ADD_DEVICE, Msg.SEARCH_WORDS_ADD_DEVICE),
    STORAGE("storage", Msg.ACCOUNT_SETTINGS_STORAGE, Msg.SEARCH_WORDS_STORAGE),
    MAPS("maps", Msg.SETTINGS_MAPS, Msg.SEARCH_WORDS_MAPS),
    APPEARANCE("appearance", Msg.ACCOUNT_SETTINGS_APPEARANCE, Msg.SEARCH_WORDS_APPEARANCE),
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
                else -> true
            }
        }

        /** The section of [id], or null. */
        fun of(id: String): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}
