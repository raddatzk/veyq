package me.raddatz.veyq.mobile.expenses

/**
 * The currencies a Pot or an entry may be kept in and their minor-unit digits (the euro 2, the yen 0, the dinar 3) — one
 * table for every client (ADR 0161; the web's too, through `@veyq/core-js/expenses`, ADR 0155): a platform's own table
 * differs where ISO 4217 and CLDR do (Android's `java.util.Currency` gives the Iraqi dinar 3 digits, `Intl` 0), and a
 * browser's moves with its ICU, so no client asks its own. The digits a Pot or an entry was written with are sealed in it:
 * a later table never moves a stored amount.
 *
 * Measured from `Intl`: `Intl.supportedValuesOf('currency')`, and for each
 * `new Intl.NumberFormat('en', {style: 'currency', currency}).resolvedOptions().maximumFractionDigits` — 2026-11-10 under
 * ICU 77.1, then 2026-11-21 under ICU 78.3 (Node 22.23, the line CI runs; COP, HUF, IDR and PKR lost their digits, RSD
 * gained two). A change is deliberate: it rewrites `src/mobile/words/money-vectors.json` (`ShownVectorsTest`'s write mode),
 * which records each currency's digits.
 */
object Currencies {
    private const val KNOWN = "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BHD BIF BMD BND BOB BRL BSD BTN BWP " +
        "BYN BZD CAD CDF CHF CLP CNY COP CRC CUC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GNF " +
        "GTQ GYD HKD HNL HRK HTG HUF IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD " +
        "LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN " +
        "PYG QAR RON RSD RUB RWF SAR SBD SCR SDG SEK SGD SHP SLE SLL SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD " +
        "TWD TZS UAH UGX USD UYU UZS VES VND VUV WST XAF XCD XCG XDR XOF XPF XSU YER ZAR ZMW ZWG ZWL"

    /** Every code whose digits are not 2. */
    private const val NOT_TWO = "AFN0 ALL0 BHD3 BIF0 CLP0 COP0 DJF0 GNF0 HUF0 IDR0 IQD0 IRR0 ISK0 JOD3 JPY0 KMF0 KPW0 KRW0 " +
        "KWD3 LAK0 LBP0 LYD3 MGA0 MMK0 OMR3 PKR0 PYG0 RWF0 SLL0 SOS0 SYP0 TND3 UGX0 VND0 VUV0 XAF0 XOF0 XPF0 YER0"

    private val digits: Map<String, Int> = NOT_TWO.split(' ').associate { it.take(3) to it.drop(3).toInt() }

    /** The codes offered, sorted. */
    val known: List<String> = KNOWN.split(' ')

    /** The minor-unit digits of [code], within the format's 0..4; a code the table does not know has 2. */
    fun minorUnits(code: String): Int = (digits[code] ?: 2).coerceIn(0, 4)
}
