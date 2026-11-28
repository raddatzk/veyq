package me.raddatz.veyq.mobile.session

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.navigation.Plan
import me.raddatz.veyq.mobile.words.Msg

/**
 * The appearance this install is drawn in, the Settings section *Appearance and language*'s first choice (the web's
 * `ThemePreference`, `shared/state/useThemeState.ts`, in the order its `AppearancePanel.vue` offers it): the system's light
 * or dark, or one of them whatever the system says. A plan (ADR 0154 decision 2): every platform offers every entry, in
 * this order, in its own control (ADR 0162).
 *
 * [id] is what an install keeps — the web's stored value (`veyq:theme`), so the two read alike —, kept by the platform
 * beside the app's own files and never sent anywhere: it is this device's, as the web's is its browser's (ADR 0022 §5).
 */
@Plan
@Serializable
enum class ThemeChoice(val id: String, val word: Msg) {
    SYSTEM("system", Msg.APPEARANCE_THEME_SYSTEM),
    LIGHT("light", Msg.APPEARANCE_THEME_LIGHT),
    DARK("dark", Msg.APPEARANCE_THEME_DARK),
    ;

    companion object {
        /** The choice kept as [id]; [SYSTEM] for nothing kept or a value no entry has (the web's `readStoredThemePreference`). */
        fun of(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * The language this install's words are shown in, the section's second choice (the web's `LocalePreference`,
 * `i18n/index.ts`' `LOCALE_PREFERENCES`): the system's, or one the catalog is written in (`src/mobile/words/words.json`),
 * each named in its own tongue ([word], as the web's `LOCALE_LABELS`). A plan, as [ThemeChoice].
 *
 * [id] is the web's stored value (`veyq:locale`) and, for a language, its BCP-47 tag. Where the platform keeps an app's
 * language itself (Android's per-app locales, Apple's per-app language) the platform's setting is the choice and nothing
 * else is kept; a desktop keeps [id] as [ThemeChoice]'s (ADR 0162).
 */
@Plan
@Serializable
enum class LanguageChoice(val id: String, val word: Msg) {
    SYSTEM("system", Msg.APPEARANCE_LANGUAGE_SYSTEM),
    EN("en", Msg.APPEARANCE_LANGUAGE_EN),
    DE("de", Msg.APPEARANCE_LANGUAGE_DE),
    ;

    /** The language's tag, null for [SYSTEM] (the system's languages decide). */
    val tag: String? get() = if (this == SYSTEM) null else id

    companion object {
        /** The choice kept as [id]; [SYSTEM] for nothing kept or a value no entry has (the web's `storedPreference`). */
        fun of(id: String?): LanguageChoice = entries.firstOrNull { it.id == id } ?: SYSTEM

        /**
         * The choice a platform's own per-app language setting stands for: its first language tag ([tags], as the platform
         * orders them; empty where none is set) when a catalog is written in it, else [SYSTEM] — a per-app language the
         * catalog has no words in shows the system's fallback, which is what [SYSTEM] says.
         */
        fun ofTags(tags: List<String>): LanguageChoice {
            val first = tags.firstOrNull()?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return SYSTEM
            return entries.firstOrNull { it != SYSTEM && it.id == first } ?: SYSTEM
        }
    }
}
