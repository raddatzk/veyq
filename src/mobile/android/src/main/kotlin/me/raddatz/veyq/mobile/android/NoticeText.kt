package me.raddatz.veyq.mobile.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Notice

/** A notice the core raised, in the person's language: its words inside it rendered too, a size as Android writes it. */
internal fun Context.text(n: Notice): String = words(
    n.msg,
    *n.args.map {
        when (it) {
            is Notice -> text(it)
            is Bytes -> android.text.format.Formatter.formatShortFileSize(this, it.count)
            is Joined -> it.parts.joinToString(it.separator) { p -> if (p is Notice) text(p) else p.toString() }
            else -> it
        }
    }.toTypedArray(),
)

/** [msg] in the person's language, in a composable (read again when the configuration, its language among it, changes). */
@Composable
internal fun say(msg: Msg, vararg args: Any): String {
    LocalConfiguration.current
    return LocalContext.current.words(msg, *args)
}

/** A notice in a composable (read again when the configuration, its language among it, changes). */
@Composable
internal fun say(notice: Notice): String {
    LocalConfiguration.current
    return LocalContext.current.text(notice)
}
