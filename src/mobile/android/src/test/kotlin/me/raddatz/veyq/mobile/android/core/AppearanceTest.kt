package me.raddatz.veyq.mobile.android.core

import android.app.UiModeManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import me.raddatz.veyq.mobile.session.LanguageChoice
import me.raddatz.veyq.mobile.session.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUIModeManager
import org.robolectric.annotation.Config

/**
 * This install's appearance and language through Android's own per-app settings (ADR 0162): a chosen appearance is kept
 * and handed to the system's app night mode, put back at the start where the system lost it; a language is the system's
 * per-app locales and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppearanceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val modes: ShadowUIModeManager get() = Shadow.extract(context.getSystemService(UiModeManager::class.java))

    @Test
    fun aChosenAppearanceIsKeptAndIsTheSystemsAppNightMode() {
        assertEquals("nothing chosen yet", ThemeChoice.SYSTEM, Appearance.theme(context))
        Appearance.setTheme(context, ThemeChoice.DARK)
        assertEquals(ThemeChoice.DARK, Appearance.theme(context))
        assertEquals(UiModeManager.MODE_NIGHT_YES, modes.applicationNightMode)
        Appearance.setTheme(context, ThemeChoice.LIGHT)
        assertEquals(UiModeManager.MODE_NIGHT_NO, modes.applicationNightMode)
        // the system's own follows the system: AUTO, as AppCompat's FOLLOW_SYSTEM maps it
        Appearance.setTheme(context, ThemeChoice.SYSTEM)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, modes.applicationNightMode)
    }

    @Test
    fun theKeptAppearanceIsHandedToTheSystemAgainAtTheStart() {
        Appearance.setTheme(context, ThemeChoice.DARK)
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
        Appearance.restore(context)
        assertEquals(UiModeManager.MODE_NIGHT_YES, modes.applicationNightMode)
    }

    @Test
    fun aChosenLanguageIsTheSystemsPerAppLocale() {
        assertEquals(LanguageChoice.SYSTEM, Appearance.language(context))
        Appearance.setLanguage(context, LanguageChoice.DE)
        assertEquals(LanguageChoice.DE, Appearance.language(context))
        assertEquals("de", Appearance.locales(LanguageChoice.DE).toLanguageTags())
        Appearance.setLanguage(context, LanguageChoice.SYSTEM)
        assertEquals(LanguageChoice.SYSTEM, Appearance.language(context))
        assertEquals("", Appearance.locales(LanguageChoice.SYSTEM).toLanguageTags())
    }
}
