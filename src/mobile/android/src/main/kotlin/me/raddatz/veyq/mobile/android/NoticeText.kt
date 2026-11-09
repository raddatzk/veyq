package me.raddatz.veyq.mobile.android

import android.content.Context
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Notice

/** A notice the core raised, in the person's language: its words inside it rendered too, a size as Android writes it. */
internal fun Context.text(n: Notice): String = words(
    n.msg,
    *n.args.map {
        when (it) {
            is Notice -> text(it)
            is Bytes -> android.text.format.Formatter.formatShortFileSize(this, it.count)
            else -> it
        }
    }.toTypedArray(),
)
