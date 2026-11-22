package me.raddatz.veyq.mobile.android.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Shown

// Every size, day, moment, amount and joined line the app shows is the core's text (`words/Shown`, ADR 0154 decision 5):
// written in the app's language, the same as the web's and the other apps'. The app formats none of its own.

/** The reader's decimal separator, as amounts are typed over. */
internal fun separator(): Char = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

/** The app's language (the per-app one Android applies), the one the core's [Shown] writes a value in. */
internal val Context.language: String
    get() = resources.configuration.locales.get(0)?.language?.takeIf { it.isNotEmpty() } ?: java.util.Locale.getDefault().language

/** The app's language in a composable (read again when the configuration, its language among it, changes). */
@Composable
internal fun language(): String {
    LocalConfiguration.current
    return LocalContext.current.language
}

/** Minor units of [currency] as the web writes money (`Shown.money`); a null currency the number alone. */
internal fun Context.money(minor: Long, currency: String?, minorUnits: Int): String = Shown.money(minor, currency, minorUnits, language)

/** [money] in a composable. */
@Composable
internal fun money(minor: Long, currency: String?, minorUnits: Int): String = Shown.money(minor, currency, minorUnits, language())

/** An ISO day (`YYYY-MM-DD`) as the web writes a day (`Shown.day`); the text itself when it is no day. */
internal fun Context.localDate(iso: String): String = Shown.day(iso, language)

/** A size in bytes as the web writes it (`Shown.size`). */
internal fun Context.size(bytes: Long): String = Shown.size(bytes, language)

/** A moment (epoch milliseconds) in this device's zone as the web writes it (`Shown.moment`). */
internal fun Context.moment(epochMs: Long): String = Shown.moment(epochMs, language)

/** [moment] in a composable. */
@Composable
internal fun moment(epochMs: Long): String = Shown.moment(epochMs, language())

/** A line's parts as every app joins them (`Shown.joined`). */
internal fun List<String>.joined(): String = Shown.joined(this)
