package me.raddatz.veyq.mobile.words

import me.raddatz.veyq.mobile.session.LocalDays

/**
 * What an app shows as text where a value is written rather than worded (ADR 0154 decision 5): a size, a day, a moment,
 * a Memory's day and range, an amount of money, a list, a track's length and a line's pieces — each the string the web
 * writes, in the app's [language] (`en`, `de`; any other is written as English, the apps' catalog has only these two),
 * so no platform formats one of its own. Held to `src/mobile/words/format-vectors.json`, which the web's own formatters
 * write (`ShownVectorsTest`); the web stays their source until a move of ADR 0155 brings them into the core.
 *
 * A [Notice] keeps its words for the platform's catalog; [args] writes its values first, so a platform fills a sentence
 * with strings only.
 */
object Shown {
    /** What a line's pieces are joined with (the web's ` · `): [joined]. */
    const val SEPARATOR = " · "

    private const val NBSP = ' '
    private const val DAY_MS = 86_400_000L
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTHS_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val MONTHS_DE = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
    private val UNITS = listOf("B", "kB", "MB", "GB", "TB")

    private fun german(language: String) = language.lowercase().startsWith("de")

    /**
     * A byte count as the web's `formatBytes` writes it: decimal steps (kB, MB, GB, TB, the way a file picker counts), one
     * fraction digit from a kilobyte on, rounded half away from zero on the value's shortest decimal form, as `Intl` does.
     */
    fun size(bytes: Long, language: String): String {
        var n = maxOf(0L, bytes).toDouble()
        var u = 0
        while (n >= 1000.0 && u < UNITS.size - 1) {
            n /= 1000.0
            u += 1
        }
        val tenths = if (u == 0) n.toLong() * 10 else tenths(n)
        val whole = grouped((tenths / 10).toString(), language)
        val fraction = tenths % 10
        return (if (u == 0 || fraction == 0L) whole else "$whole${if (german(language)) ',' else '.'}$fraction") + " " + UNITS[u]
    }

    /**
     * [n] (≥ 0) in tenths, rounded as `Intl` rounds a double: half away from zero, a tie being a double whose shortest
     * decimal form ends in a 5 at the hundredths (exactly the double nearest that decimal).
     */
    private fun tenths(n: Double): Long {
        var k = (n * 10).toLong()
        while (decimal(k + 1) <= n) k += 1
        while (k > 0 && decimal(k) > n) k -= 1
        val tie = "${k / 10}.${k % 10}5".toDouble()
        return if (n >= tie) k + 1 else k
    }

    private fun decimal(tenths: Long): Double = "${tenths / 10}.${tenths % 10}".toDouble()

    /** Digits grouped by threes with the language's separator (`1,234,567` / `1.234.567`). */
    private fun grouped(digits: String, language: String): String {
        val sep = if (german(language)) '.' else ','
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(sep)
            out.append(c)
        }
        return out.toString()
    }

    /** An ISO day (`2026-11-20`) as the web's `dateStyle: 'medium'` writes it ("Nov 20, 2026", "20.11.2026"); the text itself where it is no day. */
    fun day(iso: String, language: String): String {
        val (y, m, d) = isoDay(iso) ?: return iso
        return mediumDay(y, m, d, language)
    }

    /** A local calendar day ([LocalDays]' count since 1970-01-01) as [day] writes it. */
    fun day(epochDay: Long, language: String): String {
        val (y, m, d) = LocalDays.civil(epochDay)
        return mediumDay(y, m, d, language)
    }

    /** The local day an instant falls on, as [day] writes it (a link's end, a trash's dates). */
    fun dayOf(epochMs: Long, language: String): String = day(LocalDays.dayOf(epochMs), language)

    /** The local day a server instant (ISO 8601) falls on, as [day] writes it; its own first ten characters where it does not read. */
    fun dayOf(instant: String, language: String): String = LocalDays.dayOf(instant)?.let { day(it, language) } ?: instant.take(10)

    /** An instant in the device's zone as the web's `dateStyle: 'medium', timeStyle: 'short'` writes it ("Nov 20, 2026, 2:05 PM"). */
    fun moment(epochMs: Long, language: String): String = wallMoment(LocalDays.wallOf(epochMs), language)

    /** A server instant (ISO 8601) as [moment] writes it; the text itself where it does not read. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun moment(instant: String, language: String): String =
        runCatching { kotlin.time.Instant.parse(instant).toEpochMilliseconds() }.getOrNull()
            ?.let { moment(it, language) } ?: instant

    /** [wallMs] — a local wall clock counted as if it were UTC — as [moment] writes it: the vectors' form, in UTC. */
    internal fun wallMoment(wallMs: Long, language: String): String {
        val (y, m, d) = LocalDays.civil(wallMs.floorDiv(DAY_MS))
        val inDay = wallMs.mod(DAY_MS) / 60_000
        return "${mediumDay(y, m, d, language)}, ${clock((inDay / 60).toInt(), (inDay % 60).toInt(), language)}"
    }

    /** A time of day as [moment] writes its time (`timeStyle: 'short'`): "2:05 PM" / "14:05" — a picked time shown beside its day. */
    fun clock(hour: Int, minute: Int, language: String): String {
        val mm = minute.toString().padStart(2, '0')
        return if (german(language)) "${two(hour)}:$mm" else "${if (hour % 12 == 0) 12 else hour % 12}:$mm ${if (hour < 12) "AM" else "PM"}"
    }

    /**
     * A gallery filter's day chip (the web's `galleryFilter.ts` label, `day: 'numeric', month: 'long'`): "November 3" /
     * "3. November", with its year where the Memory spans more than one ([withYear]).
     */
    fun dayLabel(epochDay: Long, withYear: Boolean, language: String): String {
        val (y, m, d) = LocalDays.civil(epochDay)
        return dayLabel(y, m, d, withYear, language)
    }

    /** An ISO day as [dayLabel] writes it; the text itself where it is no day. */
    fun dayLabel(iso: String, withYear: Boolean, language: String): String {
        val (y, m, d) = isoDay(iso) ?: return iso
        return dayLabel(y, m, d, withYear, language)
    }

    private fun dayLabel(y: Int, m: Int, d: Int, withYear: Boolean, language: String): String =
        if (german(language)) "$d. ${MONTHS_DE[m - 1]}${if (withYear) " $y" else ""}"
        else "${MONTHS_EN[m - 1]} $d${if (withYear) ", $y" else ""}"

    /**
     * A track's length as the list says it (the web's `trackFacts.ts` `formatDistance`), [value] already rounded by the
     * core (`Gpx.roundedDistance`): whole metres ("850 m") or kilometres to at most one decimal ("12.3 km", "12 km").
     */
    fun distance(value: Double, kilometres: Boolean, language: String): String {
        if (!kilometres) return "${number(kotlin.math.round(value).toLong(), 0, language)} m"
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${if (tenths % 10 == 0L) number(tenths / 10, 0, language) else number(tenths, 1, language)} km"
    }

    private fun mediumDay(y: Int, m: Int, d: Int, language: String): String =
        if (german(language)) "${two(d)}.${two(m)}.$y" else "${MONTHS[m - 1]} $d, $y"

    /** A Memory's day (the web's `memoryDateRange.formatDay`): numeric, "03/12/2026" / "12.03.2026"; empty where it is no day. */
    fun memoryDay(iso: String, language: String): String {
        val (y, m, d) = isoDay(iso) ?: return ""
        return if (german(language)) "${two(d)}.${two(m)}.$y" else "${two(m)}/${two(d)}/$y"
    }

    /** A Memory's closed range (`formatClosedRange`): its one day where [to] is empty or [from], else "from – to"; empty without [from]. */
    fun dayRange(from: String, to: String?, language: String): String {
        if (from.isEmpty()) return ""
        val start = memoryDay(from, language)
        return if (to.isNullOrEmpty() || to == from) start else "$start – ${memoryDay(to, language)}"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun isoDay(iso: String): Triple<Int, Int, Int>? {
        val m = ISO_DAY.matchEntire(iso) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        return if (month in 1..12 && day in 1..31) Triple(y.toInt(), month, day) else null
    }

    private val ISO_DAY = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    /**
     * [amount] minor units of [currency] with [minorUnits] digits as the web's `formatMoney` writes it ("€1,234.56",
     * "1.234,56 €"); a null currency writes the number alone. The symbols are `Intl`'s for the language (the vectors'
     * table): a code without one is written as itself, set apart by a space in English.
     */
    fun money(amount: Long, currency: String?, minorUnits: Int, language: String): String {
        val number = number(amount, minorUnits, language)
        if (currency == null) return number
        val negative = number.startsWith('-')
        val digits = number.removePrefix("-")
        val sign = if (negative) "-" else ""
        if (german(language)) return "$sign$digits$NBSP${SYMBOLS_DE[currency] ?: currency}"
        val symbol = SYMBOLS_EN[currency] ?: currency
        // `Intl`'s currency spacing: a symbol that ends in a letter (or a full stop) is set apart from the digits
        val spaced = symbol.last().isLetter() || symbol.last() == '.'
        return "$sign$symbol${if (spaced) NBSP.toString() else ""}$digits"
    }

    /** [amount] as [money] writes it. */
    fun money(amount: Amount, language: String): String = money(amount.amount, amount.currency, amount.minorUnits, language)

    /** [amount] minor units with [minorUnits] digits, grouped, in the language's separators ("-1,234.56", "-1.234,56"). */
    fun number(amount: Long, minorUnits: Int, language: String): String {
        val negative = amount < 0
        val digits = (if (negative) amount.toString().removePrefix("-") else amount.toString()).padStart(minorUnits + 1, '0')
        val whole = grouped(if (minorUnits == 0) digits else digits.dropLast(minorUnits), language)
        val fraction = if (minorUnits == 0) "" else (if (german(language)) "," else ".") + digits.takeLast(minorUnits)
        return "${if (negative) "-" else ""}$whole$fraction"
    }

    /** Several names or nouns as one phrase (the web's `joinList`): "a, b, and c" / "a, b und c". */
    fun list(items: List<String>, language: String): String {
        val and = if (german(language)) "und" else "and"
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} $and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + (if (german(language)) " " else ", ") + "$and ${items.last()}"
        }
    }

    /** A track's length or a player's running clock (the web's `formatTrackLength`): `m:ss`, `h:mm:ss` from an hour on, to the nearest second. */
    fun length(ms: Long): String {
        val total = (maxOf(0L, ms) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = (total % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
    }

    /** A line's parts joined as every app and the web join them ([SEPARATOR]). */
    fun joined(parts: List<String>): String = parts.joinToString(SEPARATOR)

    /**
     * [notice] with every value written in [language] — a [Bytes] by [size], a [Moment] by [moment], an [Amount] by [money],
     * also inside a [Joined] or a word within it —, so what is left for a platform is its catalog's words and strings.
     */
    fun args(notice: Notice, language: String): Notice = Notice(notice.msg, notice.args.map { arg(it, language) }, notice.kind)

    private fun arg(a: Any, language: String): Any = when (a) {
        is Bytes -> size(a.count, language)
        is Moment -> moment(a.epochMs, language)
        is Amount -> money(a, language)
        is Notice -> args(a, language)
        is Joined -> Joined(a.parts.map { arg(it, language) }, a.separator)
        else -> a
    }

    /** [p] with its value written in [language]: a day, a size or an amount becomes [Piece.Plain]; a word keeps its words, its values written. */
    fun piece(p: Piece, language: String): Piece = when (p) {
        is Piece.Said -> Piece.Said(args(p.notice, language))
        is Piece.Plain -> p
        is Piece.Day -> Piece.Plain(day(p.iso, language))
        is Piece.Size -> Piece.Plain(size(p.bytes, language))
        is Piece.Money -> Piece.Plain(money(p.amount, p.currency, p.minorUnits, language))
    }

    /** `Intl`'s symbol of each currency whose English symbol is not its code (Node 23.11, ICU 77.1, CLDR 47; the vectors'). */
    private val SYMBOLS_EN = mapOf(
        "AUD" to "A\$", "BRL" to "R\$", "CAD" to "CA\$", "CNY" to "CN¥", "EUR" to "€", "GBP" to "£", "HKD" to "HK\$",
        "ILS" to "₪", "INR" to "₹", "JPY" to "¥", "KRW" to "₩", "MXN" to "MX\$", "NZD" to "NZ\$", "PHP" to "₱",
        "TWD" to "NT\$", "USD" to "\$", "VND" to "₫", "XAF" to "FCFA", "XCD" to "EC\$", "XCG" to "Cg.",
        "XOF" to "F CFA", "XPF" to "CFPF",
    )

    /** The same for German. */
    private val SYMBOLS_DE = mapOf(
        "AUD" to "AU\$", "BRL" to "R\$", "CAD" to "CA\$", "CNY" to "CN¥", "EUR" to "€", "GBP" to "£", "HKD" to "HK\$",
        "ILS" to "₪", "INR" to "₹", "JPY" to "¥", "KRW" to "₩", "MXN" to "MX\$", "NZD" to "NZ\$", "THB" to "฿",
        "TWD" to "NT\$", "USD" to "\$", "VND" to "₫", "XAF" to "FCFA", "XCD" to "EC\$", "XCG" to "Cg.",
        "XOF" to "F CFA", "XPF" to "CFPF",
    )
}
