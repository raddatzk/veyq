package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * New Memory and New chapter (`MemoryNodeDialog.vue`): offered exactly where the kinds' placement allows them, a name
 * checked against the loaded tree before anything is sent (named by the sibling's kind, core's `collidingSibling`), and a
 * server's refusal said as the dialog says it.
 */
class MemoryCreateTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    @Test
    fun aMemoryIsOfferedAtASpacesTopAndInAFolderAChapterOnlyInAMemory() {
        assertEquals(listOf(MemoryCreate.MEMORY), MemoryCreate.offered(null))
        assertEquals(listOf(MemoryCreate.MEMORY), MemoryCreate.offered("FOLDER"))
        assertEquals(listOf(MemoryCreate.CHAPTER), MemoryCreate.offered("MEMORY"))
        for (parent in listOf("CHAPTER", "EXPENSES_POT", "FILE", "DEPOT_DROPBOX", "SHARE_BOOKMARK", "SOMETHING_NEW")) {
            assertEquals(emptyList(), MemoryCreate.offered(parent), "nothing of Memories' in a $parent")
        }
    }

    @Test
    fun aNameIsCheckedBeforeItIsSent() {
        val siblings = listOf(MemoryCreate.Sibling("MEMORY", "Ostsee 2026"), MemoryCreate.Sibling("FOLDER", "Trip"))
        assertNull(MemoryCreate.check("MEMORY", null, "  Alps  ", siblings))
        assertEquals(
            Notice(Msg.NODE_NAME_TAKEN_BY, Notice(Msg.MEMORY_A_MEMORY), "Ostsee 2026"),
            MemoryCreate.check("MEMORY", "FOLDER", " ostsee 2026 ", siblings),
            "a sibling folding to the same name is named with its kind",
        )
        assertNull(MemoryCreate.check("MEMORY", null, "Trip", siblings), "a folder's name folds apart from a Memory's")
        assertEquals(
            Notice(Msg.NODE_NAME_TAKEN_BY, Notice(Msg.MEMORY_A_CHAPTER), "Day one"),
            MemoryCreate.check("CHAPTER", "MEMORY", "Day One", listOf(MemoryCreate.Sibling("CHAPTER", "Day one"))),
        )
        assertEquals(Notice(Msg.FOLDER_NAME_EMPTY), MemoryCreate.check("MEMORY", null, "   ", siblings))
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), MemoryCreate.check("CHAPTER", null, "Day one", emptyList()), "no chapter at a top")
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), MemoryCreate.check("MEMORY", "MEMORY", "Inner", emptyList()), "no Memory in a Memory")
    }

    @Test
    fun aRefusalIsSaidAsTheDialogSaysIt() {
        assertEquals(Notice(Msg.MEMORY_SLUG_TAKEN), MemoryCreate.refused(NodeCreate.Reason.NAME_TAKEN))
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), MemoryCreate.refused(NodeCreate.Reason.NOT_ALLOWED))
        for (r in listOf(NodeCreate.Reason.BUSY, NodeCreate.Reason.BLOCKED, NodeCreate.Reason.REFUSED, null)) {
            assertEquals(Notice(Msg.NODE_SAVE_FAILED), MemoryCreate.refused(r), "$r")
        }
    }

    /** Bite (f): *I was there* sealed into a chapter, or in a personal scope, gives it participants it cannot have — red here. */
    @Test
    fun iWasThereIsOfferedForAMemoryInATenantOrGroupScopeAndSealsTheMakerAtBirth() {
        assertTrue(MemoryCreate.offersWasThere(MemoryCreate.MEMORY, "TENANT"))
        assertTrue(MemoryCreate.offersWasThere(MemoryCreate.MEMORY, "GROUP"))
        assertFalse(MemoryCreate.offersWasThere(MemoryCreate.MEMORY, "PERSONAL"), "a personal scope has no participants")
        assertFalse(MemoryCreate.offersWasThere(MemoryCreate.MEMORY, null), "a scope not open here")
        assertFalse(MemoryCreate.offersWasThere(MemoryCreate.CHAPTER, "TENANT"), "a chapter has none")
        assertEquals(
            Json.parseToJsonElement("""{"v":1,"participants":{"userIds":["u1"]}}""") as kotlinx.serialization.json.JsonObject,
            MemoryCreate.birthMeta(MemoryCreate.MEMORY, "GROUP", wasThere = true, userId = "u1"),
            "the maker, ids only, sealed into the first meta",
        )
        assertNull(MemoryCreate.birthMeta(MemoryCreate.MEMORY, "TENANT", wasThere = false, userId = "u1"), "unticked: no meta at all")
        assertNull(MemoryCreate.birthMeta(MemoryCreate.CHAPTER, "TENANT", wasThere = true, userId = "u1"), "never into a chapter")
        assertNull(MemoryCreate.birthMeta(MemoryCreate.MEMORY, "PERSONAL", wasThere = true, userId = "u1"), "never in a personal scope")
    }

    /**
     * uc.memory.place A1, as the web's `MemoryNodeDialog` builds it: *Determine location automatically* seals a pending
     * `auto` place at birth — `{name: '', nameSource: 'geocoded', derivedMode: 'auto'}`, before the participants, as the
     * web's `{v, ...{location, participants}}` — in a Memory only, in any scope; unticked, or for a chapter, nothing.
     */
    @Test
    fun theAutoPlaceIsArmedAtBirthAsTheWebArmsIt() {
        assertTrue(MemoryCreate.offersAutoPlace(MemoryCreate.MEMORY, mapsAvailable = true))
        assertFalse(MemoryCreate.offersAutoPlace(MemoryCreate.MEMORY, mapsAvailable = false), "no map services, no step")
        assertFalse(MemoryCreate.offersAutoPlace(MemoryCreate.CHAPTER, mapsAvailable = true), "a chapter has no place step")
        assertEquals(
            Json.parseToJsonElement("""{"v":1,"location":{"name":"","nameSource":"geocoded","derivedMode":"auto"},"participants":{"userIds":["u1"]}}""") as kotlinx.serialization.json.JsonObject,
            MemoryCreate.birthMeta(MemoryCreate.MEMORY, "GROUP", wasThere = true, userId = "u1", autoPlace = true),
        )
        assertEquals(
            Json.parseToJsonElement("""{"v":1,"location":{"name":"","nameSource":"geocoded","derivedMode":"auto"}}""") as kotlinx.serialization.json.JsonObject,
            MemoryCreate.birthMeta(MemoryCreate.MEMORY, "PERSONAL", wasThere = true, userId = "u1", autoPlace = true),
            "in a personal scope the place alone",
        )
        assertNull(MemoryCreate.birthMeta(MemoryCreate.CHAPTER, "TENANT", wasThere = false, userId = "u1", autoPlace = true), "never into a chapter")
        val armed = me.raddatz.veyq.mobile.memories.place.MemoryFacts.Facts.of(
            MemoryCreate.birthMeta(MemoryCreate.MEMORY, "PERSONAL", wasThere = false, userId = "u1", autoPlace = true)!!,
        ).location
        assertTrue(assertNotNull(armed).pending, "read back it is the pending auto place the settle materializes")
    }
}
