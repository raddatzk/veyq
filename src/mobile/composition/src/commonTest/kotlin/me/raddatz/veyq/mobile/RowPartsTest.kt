package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.composition.AppComposition
import me.raddatz.veyq.mobile.navigation.ApplicationId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the list rows say is each application's registered part (`ApplicationKinds.rowParts`, inbox #1048): the build's
 * parts are the ones the rows said before the move out of the composition — Depot's files and drop boxes, Expenses' Pots,
 * nothing of Memories' (its rows' line is its UI's `rowLines`). A part an application forgot to register is red here
 * before a row goes quiet on a device.
 */
class RowPartsTest {
    init { AppComposition.install() }

    @Test
    fun every_application_registers_the_row_parts_it_says() {
        val byApp = AppComposition.applications.associate { a -> a.application to a.rowParts.map { it.key } }
        assertEquals(
            mapOf(
                ApplicationId.DEPOT to listOf("depot.files", "depot.boxes"),
                ApplicationId.EXPENSES to listOf("expenses.pots"),
                ApplicationId.MEMORIES to emptyList(),
            ),
            byApp,
        )
    }
}
