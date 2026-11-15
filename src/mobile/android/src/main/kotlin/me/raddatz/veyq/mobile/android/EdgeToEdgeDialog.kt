package me.raddatz.veyq.mobile.android

import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * The window of the full-screen dialog this is composed in drawn over the whole screen, edge to edge like the activity:
 * a Compose `Dialog`'s window otherwise keeps out of the system bars and the display cutout — even with
 * `decorFitsSystemWindows = false` it keeps out of the cutout on its short edges —, so in landscape it sat shifted by the
 * cutout with the page showing beside it (#931, #936; `src/mobile/CLAUDE.md` § Traps). The dialog passes
 * `decorFitsSystemWindows = false` and keeps its content in `WindowInsets.safeDrawing`. The bars' icons are dark over a
 * light page ([darkIcons]), light over the lightbox's black.
 */
@Composable
internal fun EdgeToEdgeDialogWindow(darkIcons: Boolean = !isSystemInDarkTheme()) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
