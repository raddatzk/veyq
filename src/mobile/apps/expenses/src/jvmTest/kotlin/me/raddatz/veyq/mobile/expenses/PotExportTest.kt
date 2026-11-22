package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The CSV beside a download, to the byte the web's `entriesCsv` writes, and an entry as the content carries it. */
class PotExportTest {
    private val ana = "11111111-1111-4111-8111-111111111111"
    private val ben = "22222222-2222-4222-8222-222222222222"

    @Test
    fun decimals() {
        assertEquals("12.50", PotExport.decimalOf("1250", 2))
        assertEquals("0.05", PotExport.decimalOf("5", 2))
        assertEquals("-0.05", PotExport.decimalOf("-5", 2))
        assertEquals("7", PotExport.decimalOf("7", 0))
        assertEquals("0.001", PotExport.decimalOf("1", 3))
    }

    @Test
    fun theCsvIsTheWebs() {
        val dinner = Expense(
            "Dinner, \"the\" good one", "food", "2026-11-08", "USD", 2, "0.9", 1250, emptyList(),
            listOf(Contribution(ana, 1000), Contribution(ben, 250)), Allocation(emptyList(), Split.Equal(listOf(ana, ben))),
        )
        val pay = Transfer(null, "2026-11-09", "EUR", 2, "1", 500, emptyList(), ben, ana)
        val csv = PotExport.entriesCsv(listOf(dinner to Big.of(1125), pay to Big.of(500)), mapOf(ana to "Ana", ben to "Ben"), 2)
        assertEquals(
            "date,kind,title,category,amount,currency,rate,base amount,paid by,from,to\r\n" +
                "2026-11-08,expense,\"Dinner, \"\"the\"\" good one\",food,12.50,USD,0.9,11.25,Ana; Ben,,\r\n" +
                "2026-11-09,transfer,,,5.00,EUR,1,5.00,,Ben,Ana\r\n",
            csv,
        )
    }

    @Test
    fun anEntryCarriesNoVersionAndNoHeads() {
        val json = PotExport.entryJson(Transfer("Back", "2026-11-09", "EUR", 2, "1", 500, listOf("33333333-3333-4333-8333-333333333333"), ben, ana))
        assertFalse("v" in json || "heads" in json)
        assertEquals("transfer", json["kind"].toString().trim('"'))
        // what remains reads as a revision once an import gives it its version and heads again
        RevisionFormat.read(kotlinx.serialization.json.JsonObject(json + mapOf(
            "v" to kotlinx.serialization.json.JsonPrimitive(RevisionFormat.VERSION),
            "heads" to kotlinx.serialization.json.JsonArray(emptyList()),
        )))
    }
}
