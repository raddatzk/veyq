package me.raddatz.veyq.mobile.android.core

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import me.raddatz.veyq.mobile.session.LanguageChoice
import me.raddatz.veyq.mobile.session.ThemeChoice

/**
 * This install's appearance and language (ADR 0162), each through Android's own per-app setting:
 * - the appearance is `UiModeManager.setApplicationNightMode` (Android 12+, every version the app runs on), which the
 *   system keeps and applies to the app's configuration — Compose's `isSystemInDarkTheme`, the window's theme and the
 *   splash screen all read it, and the activity is recreated with it at once. The choice itself is kept beside it in
 *   `shared_prefs/veyq.appearance.xml` (`theme`, the core's [ThemeChoice.id]: the system has no getter for an app's night
 *   mode), and put back at every start, so the two never part.
 * - the language is `LocaleManager.setApplicationLocales` (Android 13+, the app's `locales_config`): the system keeps it,
 *   its own settings page for the app shows the same choice, and the activity is recreated in it at once. Nothing else
 *   is kept. Android 12 has no per-app language: [languageOffered] is false there and the row is not drawn.
 * Nothing of either leaves the device (`allowBackup="false"`).
 */
internal object Appearance {
    private const val PREFS = "veyq.appearance"
    private const val THEME = "theme"

    fun theme(context: Context): ThemeChoice =
        ThemeChoice.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(THEME, null))

    fun setTheme(context: Context, choice: ThemeChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(THEME, choice.id).apply()
        apply(context, choice)
    }

    /** The kept appearance handed to the system again (the app's start): a no-op where it already holds it. */
    fun restore(context: Context) = apply(context, theme(context))

    private fun apply(context: Context, choice: ThemeChoice) {
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode(choice))
    }

    /** The system's app night mode for [choice]: AUTO follows the system's own (as AppCompat's FOLLOW_SYSTEM maps it). */
    fun nightMode(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
    }

    val languageOffered: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun language(context: Context): LanguageChoice {
        if (!languageOffered) return LanguageChoice.SYSTEM
        val set = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return LanguageChoice.SYSTEM
        return LanguageChoice.ofTags((0 until set.size()).map { set[it].toLanguageTag() })
    }

    fun setLanguage(context: Context, choice: LanguageChoice) {
        if (!languageOffered) return
        context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales(choice)
    }

    /** The per-app locales for [choice]: none for the system's. */
    fun locales(choice: LanguageChoice): LocaleList = choice.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
}
