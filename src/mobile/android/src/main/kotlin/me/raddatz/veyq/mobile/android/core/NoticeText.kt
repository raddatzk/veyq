package me.raddatz.veyq.mobile.android.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import me.raddatz.veyq.mobile.words.Shown

/**
 * A notice the core raised, in the person's language: its values written by the core first (`Shown.args`: a size, a
 * moment, an amount), its words inside it rendered too.
 */
internal fun Context.text(n: Notice): String = written(Shown.args(n, language))

/** [n] with its values already written: its words and the strings and words inside it. */
private fun Context.written(n: Notice): String = words(
    n.msg,
    *n.args.map {
        when (it) {
            is Notice -> written(it)
            is Joined -> it.parts.joinToString(it.separator) { p -> if (p is Notice) written(p) else p.toString() }
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

/** One piece of a line (core's `Piece`), its value written by the core (`Shown.piece`). */
internal fun Context.piece(p: Piece): String = when (val w = Shown.piece(p, language)) {
    is Piece.Said -> text(w.notice)
    is Piece.Plain -> w.text
    else -> error("Shown.piece left $w unwritten")
}

/** A piece in a composable (read again when the configuration, its language among it, changes). */
@Composable
internal fun piece(p: Piece): String {
    LocalConfiguration.current
    return LocalContext.current.piece(p)
}
