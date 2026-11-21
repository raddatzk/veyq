package me.raddatz.veyq.mobile.android.core

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.NoticeKind

/**
 * A notice on Material's snackbar, living as the core's [NoticeKind] says (ADR 0154 decision 4, the web's `useToast`):
 * the one place the app meets a snackbar's lifetime. Material's own durations are not the web's (its Short and Long,
 * lengthened by the accessibility settings), so every notice is shown without one of its own and closed here:
 * a [NoticeKind.SAID] one [NoticeKind.lifeMs] after it appears (not while it waits behind another), a [NoticeKind.HELD]
 * one carries a close and stays until closed, an undo stays until the core ends its window ([ended]).
 */
private class NoticeVisuals(override val message: String, override val actionLabel: String?, override val withDismissAction: Boolean) : SnackbarVisuals {
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}

/**
 * Shows [text] living as [kind], with [action] where one is offered; [ended] returns when the core ends the offer (an
 * undo's window), which takes the snackbar with it. [SnackbarResult.Dismissed] when it closed itself or was closed.
 */
internal suspend fun SnackbarHostState.show(
    text: String, kind: NoticeKind, action: String? = null, ended: (suspend () -> Unit)? = null,
): SnackbarResult = coroutineScope {
    val visuals = NoticeVisuals(text, action, withDismissAction = kind != NoticeKind.SAID)
    val shown = async { showSnackbar(visuals) }
    val life = kind.lifeMs
    val ends = launch {
        when {
            ended != null -> ended()
            life != null -> {
                snapshotFlow { currentSnackbarData?.visuals }.first { it === visuals }
                delay(life)
            }
            else -> awaitCancellation()
        }
        shown.cancel()
    }
    runCatching { shown.await() }.getOrDefault(SnackbarResult.Dismissed).also { ends.cancel() }
}

/** Core's [notice] in the person's language, living as its kind says. */
internal suspend fun SnackbarHostState.show(context: Context, notice: Notice): SnackbarResult = show(context.text(notice), notice.kind)
