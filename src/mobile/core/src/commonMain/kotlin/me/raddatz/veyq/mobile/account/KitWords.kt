package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.words.CoreText
import me.raddatz.veyq.mobile.words.Msg

/**
 * The words printed on an Emergency Kit ([KitWriter]), in one language: the web's `kit.pdf*` strings, from
 * `src/mobile/words/words.json`'s `kit_pdf_*` keys (`"core_text": true`), so the core writes them itself ([CoreText]).
 */
class KitWords private constructor(private val language: String) {
    private fun t(msg: Msg, vararg args: String) = CoreText.of(msg, language, *args)

    val title = t(Msg.KIT_PDF_TITLE)
    val heading = t(Msg.KIT_PDF_HEADING)
    val page = t(Msg.KIT_PDF_PAGE)
    val intro = t(Msg.KIT_PDF_INTRO)
    val confidential = t(Msg.KIT_PDF_CONFIDENTIAL)
    val confidentialBody = t(Msg.KIT_PDF_CONFIDENTIAL_BODY)
    val account = t(Msg.KIT_PDF_ACCOUNT)
    val email = t(Msg.KIT_PDF_EMAIL)
    val secretKey = t(Msg.KIT_PDF_SECRET_KEY)
    val where = t(Msg.KIT_PDF_WHERE)
    val addresses = t(Msg.KIT_PDF_ADDRESSES)
    val restore = t(Msg.KIT_PDF_RESTORE)
    val steps = listOf(t(Msg.KIT_PDF_STEP1), t(Msg.KIT_PDF_STEP2), t(Msg.KIT_PDF_STEP3))
    val keep = t(Msg.KIT_PDF_KEEP)
    val keepRules = listOf(t(Msg.KIT_PDF_KEEP1), t(Msg.KIT_PDF_KEEP2), t(Msg.KIT_PDF_KEEP3))
    val helpUrl = t(Msg.KIT_PDF_HELP_URL)

    /** The footer's help line: the help page's address without its `https://`, as the web prints it. */
    val help = t(Msg.KIT_PDF_HELP, helpUrl.removePrefix("https://"))

    /**
     * "Created on {date}" for the day [epochDay] (days since 1970-01-01), the date as the web's `Intl.DateTimeFormat`
     * with `dateStyle: 'long'` writes it in this language (`November 18, 2026`, `18. November 2026`). The day is the
     * caller's: the person's local date, so the kit's bytes are a function of what is passed.
     */
    fun created(epochDay: Long): String {
        val (y, m, d) = civil(epochDay)
        return t(Msg.KIT_PDF_CREATED, t(Msg.KIT_PDF_DATE, t(MONTHS[m - 1]), d.toString(), y.toString()))
    }

    companion object {
        /** The kit's words in [language], a language tag: `de` (and `de-*`) German, anything else English. */
        fun of(language: String): KitWords = KitWords(language)

        private val MONTHS = listOf(
            Msg.KIT_PDF_MONTH_1, Msg.KIT_PDF_MONTH_2, Msg.KIT_PDF_MONTH_3, Msg.KIT_PDF_MONTH_4, Msg.KIT_PDF_MONTH_5,
            Msg.KIT_PDF_MONTH_6, Msg.KIT_PDF_MONTH_7, Msg.KIT_PDF_MONTH_8, Msg.KIT_PDF_MONTH_9, Msg.KIT_PDF_MONTH_10,
            Msg.KIT_PDF_MONTH_11, Msg.KIT_PDF_MONTH_12,
        )

        /** The proleptic Gregorian (year, month, day) of [epochDay] (H. Hinnant's `civil_from_days`). */
        internal fun civil(epochDay: Long): Triple<Long, Int, Int> {
            val z = epochDay + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return Triple(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
        }
    }
}
