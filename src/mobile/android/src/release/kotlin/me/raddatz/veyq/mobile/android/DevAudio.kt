package me.raddatz.veyq.mobile.android

import android.content.Context
import android.content.Intent

/** A release build has no format-matrix harness: a track plays only from a Memory's music. */
object DevAudio {
    fun requested(@Suppress("UNUSED_PARAMETER") intent: Intent?): Boolean = false

    fun run(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
}
