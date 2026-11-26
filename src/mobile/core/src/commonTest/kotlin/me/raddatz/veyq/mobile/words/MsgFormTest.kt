package me.raddatz.veyq.mobile.words

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The words' form (inbox #1341, ADR 0161 § The words' form for JavaScript): `Msg` is a class of one getter per word, not an
 * enum, so the JavaScript link drops every word nothing reads; what the apps read of the enum it was stays — a word is equal
 * to itself read again, its name, the catalog's order, and on the wire the enum by its name the desktops' types are.
 */
class MsgFormTest {
    @Test
    fun aWordIsEqualToItselfReadAgainAndToNoOther() {
        assertEquals(Msg.TRASH_MOVED, Msg.TRASH_MOVED)
        assertEquals(Msg.TRASH_MOVED.hashCode(), Msg.TRASH_MOVED.hashCode())
        assertNotEquals(Msg.TRASH_MOVED, Msg.TRASH_UNDO)
        assertEquals(setOf(Msg.TRASH_MOVED), setOf(Msg.TRASH_MOVED, Msg.TRASH_MOVED))
        assertEquals(true, Msg.TRASH_MOVED in setOf(Msg.COPY_DONE, Msg.TRASH_MOVED))
    }

    @Test
    fun itsNameAndTextAreTheEnumsItWas() {
        assertEquals("trash_moved", Msg.TRASH_MOVED.key)
        assertEquals("TRASH_MOVED", Msg.TRASH_MOVED.name)
        assertEquals("TRASH_MOVED", Msg.TRASH_MOVED.toString())
        assertEquals(1, Msg.TRASH_MOVED.args)
        assertEquals(false, Msg.TRASH_MOVED.plural)
    }

    @Test
    fun theEntriesAreEveryWordOnceInTheCatalogsOrder() {
        val keys = Msg.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "a word twice")
        assertEquals("app_name", keys.first(), "the catalog's first word first")
        assertEquals(true, Msg.TRASH_MOVED in Msg.entries)
    }

    @Test
    fun onTheWireAWordIsTheEnumByItsName() {
        val d = MsgName.descriptor
        assertEquals(SerialKind.ENUM, d.kind)
        assertEquals("me.raddatz.veyq.mobile.words.Msg", d.serialName)
        assertEquals(Msg.entries.map { it.name }, (0 until d.elementsCount).map(d::getElementName))
        assertEquals(StructureKind.OBJECT, d.getElementDescriptor(0).kind)
        assertEquals("me.raddatz.veyq.mobile.words.Msg.APP_NAME", d.getElementDescriptor(0).serialName)
        assertEquals("\"TRASH_MOVED\"", Json.encodeToString(Msg.serializer(), Msg.TRASH_MOVED))
        assertEquals(Msg.TRASH_MOVED, Json.decodeFromString(Msg.serializer(), "\"TRASH_MOVED\""))
        assertFailsWith<SerializationException> { Json.decodeFromString(Msg.serializer(), "\"NO_SUCH_WORD\"") }
        // a field that says MsgKey writes the catalog's key instead
        assertEquals("\"trash_moved\"", Json.encodeToString(MsgKey, Msg.TRASH_MOVED))
        assertEquals(Msg.TRASH_MOVED, Json.decodeFromString(MsgKey, "\"trash_moved\""))
    }
}
