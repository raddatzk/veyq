package me.raddatz.veyq.mobile.android.core

import android.content.Context
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.words.Msg

/** The reader's decimal separator, as amounts are typed over. */
internal fun separator(): Char = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

/** Minor units of [currency] as the person's locale writes money. */
fun money(minor: Long, currency: String?, minorUnits: Int): String {
    // D8: the core hands every amount with its currency; none is never the locale's, but a number alone (the desktops' rule)
    val f = if (currency == null) NumberFormat.getNumberInstance() else NumberFormat.getCurrencyInstance()
    currency?.let { runCatching { f.currency = Currency.getInstance(it) } }
    f.minimumFractionDigits = minorUnits
    f.maximumFractionDigits = minorUnits
    return f.format(BigDecimal.valueOf(minor, minorUnits))
}

/** An entry's `YYYY-MM-DD` as the person's locale writes a day; the text itself when it is no date. */
internal fun localDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)

/**
 * A Memory's date as a list row and its header say it (ADR 0143 D5): a single day, a range the device's way
 * (`DateUtils.formatDateRange`: "3–5 Jan 2020"), an ongoing one "Since …"; null where it has no day yet.
 */
internal fun Context.memoryDateText(d: MemoryDate): String? {
    val from = d.from ?: return null
    if (d.mode == MemoryDate.Mode.ONGOING) return words(Msg.MEMORY_DATE_SINCE, localDate(from))
    val to = d.to
    if (to == null || to == from) return localDate(from)
    return runCatching {
        // noon of each day in UTC: the range is days, never shifted by a zone
        fun ms(iso: String) = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L
        android.text.format.DateUtils.formatDateRange(
            this, ms(from), ms(to),
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_YEAR or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or android.text.format.DateUtils.FORMAT_UTC,
        )
    }.getOrElse { "${localDate(from)} – ${localDate(to)}" }
}
