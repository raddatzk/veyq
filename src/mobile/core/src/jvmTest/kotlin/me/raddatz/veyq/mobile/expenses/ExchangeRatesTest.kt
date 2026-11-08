package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The day's exchange rates (the web's `exchangeRates.test.ts`): one request per device and day, carrying the date and
 * nothing else, never repeated for a day whose request failed, and the cross rate exact in fractions with the euro as 1.
 */
class ExchangeRatesTest {
    private val template = "https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}"
    private val day = "2026-10-25"

    /** The ECB's table as Frankfurter's ECB endpoint answers it (measured 2026-10-25 on the web): a Sunday asked, Friday's rows. */
    private val answer = """[
        {"date":"2026-10-23","base":"EUR","quote":"EUR","rate":1.0},
        {"date":"2026-10-23","base":"EUR","quote":"USD","rate":1.1725},
        {"date":"2026-10-23","base":"EUR","quote":"GBP","rate":0.8731},
        {"date":"2026-10-23","base":"EUR","quote":"JPY","rate":177.84},
        {"date":"2026-10-23","base":"EUR","quote":"CNY","rate":7.466}
    ]"""

    private val asked = mutableListOf<String>()
    private var reply: String? = answer
    private val get: suspend (String) -> String? = { url -> asked += url; reply }

    @Test
    fun oneRequestPerDeviceAndDayKeptAcrossARestart() = runBlocking {
        val store = MemoryDeviceStore()
        repeat(3) { assertEquals("1.1725", ExchangeRates.dayTable(template, day, store, get)?.get("USD")?.rate) }
        assertEquals(1, asked.size)
        // A restart reads the device's copy.
        assertEquals("177.84", ExchangeRates.dayTable(template, day, store, get)?.get("JPY")?.rate)
        assertEquals(1, asked.size)
        // Another day asks again, and the device keeps that day's alone.
        assertNotNull(ExchangeRates.dayTable(template, "2026-10-26", store, get))
        assertEquals(2, asked.size)
        assertNotNull(ExchangeRates.dayTable(template, "2026-10-26", store, get))
        assertEquals(2, asked.size)
    }

    @Test
    fun theRequestCarriesTheDateAndNothingElse() = runBlocking {
        ExchangeRates.dayTable(template, day, MemoryDeviceStore(), get)
        assertEquals(listOf("https://api.frankfurter.dev/v2/providers/ecb/rates?date=2026-10-25"), asked)
    }

    @Test
    fun aDayWhoseRequestFailedIsNotAskedAgain() = runBlocking {
        val store = MemoryDeviceStore()
        reply = null
        assertNull(ExchangeRates.dayTable(template, day, store, get))
        reply = answer
        assertNull(ExchangeRates.dayTable(template, day, store, get))
        assertEquals(1, asked.size)
        // Control: a new day asks.
        assertNotNull(ExchangeRates.dayTable(template, "2026-10-26", store, get))
        // An answer of another shape is no table, and counts as the day's attempt too.
        reply = """{"rates":{}}"""
        assertNull(ExchangeRates.dayTable(template, "2026-10-27", store, get))
    }

    @Test
    fun aTemplateWithoutTheDateAsksNothing() = runBlocking {
        assertNull(ExchangeRates.tableUrl("https://rates.example/latest", day))
        assertNull(ExchangeRates.dayTable("https://rates.example/latest", day, MemoryDeviceStore(), get))
        assertNull(ExchangeRates.tableUrl(template, "25.10.2026"))
        assertEquals(emptyList(), asked)
    }

    private val table = ExchangeRates.parseTable(Json.parseToJsonElement(answer))!!

    @Test
    fun theCrossRateIsExactWithTheEuroAsOne() {
        assertEquals(ExchangeRates.Filled("0.852878464819", "2026-10-23"), ExchangeRates.crossRate(table, "USD", "EUR"))
        assertEquals(ExchangeRates.Filled("1.1725", "2026-10-23"), ExchangeRates.crossRate(table, "EUR", "USD"))
        assertEquals(ExchangeRates.Filled("151.675906183369", "2026-10-23"), ExchangeRates.crossRate(table, "USD", "JPY"))
        assertEquals("1.342916046272", ExchangeRates.crossRate(table, "GBP", "USD")?.rate)
        // The euro is 1 whether or not the table names it.
        assertEquals("0.852878464819", ExchangeRates.crossRate(mapOf("USD" to ExchangeRates.Row("1.1725", "2026-10-23")), "USD", "EUR")?.rate)
        // Never through a float.
        assertEquals("0.333333333333", ExchangeRates.crossRate(mapOf("XXX" to ExchangeRates.Row("3", "2026-10-23")), "XXX", "EUR")?.rate)
        // A half rounds away from zero: 1/8 at 12 places is exact; 2/3 rounds up.
        assertEquals("0.666666666667", ExchangeRates.crossRate(mapOf("XXX" to ExchangeRates.Row("1.5", "2026-10-23")), "XXX", "EUR")?.rate)
        assertEquals("1", table["EUR"]?.rate)
    }

    @Test
    fun theRowsDateMayBeEarlierThanTheDayAsked() {
        val mixed = ExchangeRates.parseTable(Json.parseToJsonElement(answer.trimEnd(']') + """,{"date":"2026-10-22","base":"EUR","quote":"CHF","rate":0.9227}]"""))!!
        assertEquals("2026-10-22", ExchangeRates.crossRate(mixed, "CHF", "USD")?.date)
    }

    @Test
    fun noRowThatIsNoIsoCurrencyNoPlainDecimalOrAnotherBase() {
        val odd = ExchangeRates.parseTable(
            Json.parseToJsonElement(
                answer.trimEnd(']') + """,
                {"date":"2026-10-23","base":"EUR","quote":"BTC","rate":0.0000123},
                {"date":"2026-10-23","base":"EUR","quote":"XAU","rate":0.00031},
                {"date":"2026-10-23","base":"USD","quote":"CAD","rate":1.37},
                {"date":"2026-10-23","base":"EUR","quote":"SEK","rate":1e-7},
                {"date":"23.10.2026","base":"EUR","quote":"NOK","rate":11.5},
                {"date":"2026-10-23","base":"EUR","quote":"usd","rate":1.2}]""",
            ),
            setOf("EUR", "USD", "GBP", "JPY", "CNY", "CAD", "SEK", "NOK"),
        )!!
        assertEquals(listOf("CNY", "EUR", "GBP", "JPY", "USD"), odd.keys.sorted())
        assertNull(ExchangeRates.crossRate(odd, "CAD", "EUR"))
        assertNull(ExchangeRates.crossRate(odd, "BTC", "EUR"))
        assertEquals("7.466", odd["CNY"]!!.rate)
        assertNull(ExchangeRates.parseTable(Json.parseToJsonElement("""{"rates":{}}""")))
    }
}
