package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.depot.DepotImport
import me.raddatz.veyq.mobile.expenses.PotImport
import me.raddatz.veyq.mobile.memories.MemoryImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The import's dry run and walk (ADR 0152 decisions 2 and 3; the web's `planImport`, `replaySubtrees`): the cases' plans and
 * import-stage refusals, the web's download planned whole and walked in its order — every kind to its application's hook —,
 * a name freed past the space's siblings and the server's own answer, a payload's checksum rolling back what was made,
 * a cancel too, "Import the rest", and a quota refused before any write.
 */
class SubtreeImportTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val cases = ImportFixtures.cases()

    private suspend fun run(target: PlayedImport, input: ImportInput, parentId: String? = null) =
        SubtreeImport.run(SubtreeImport.open(input), "scope", parentId, target, target.played(), PlayedImport.tokenInput)

    @Test
    fun everyPlanOfTheCasesIsTheWebs() = runTest {
        for (c in cases.filter { it.plan != null }) {
            val plan = SubtreeImport.plan(SubtreeImport.open(c.input).manifest, PlayedImport.real())
            val want = c.plan!!
            assertEquals(want["nodes"]!!.jsonPrimitive.int, plan.nodes, c.id)
            assertEquals(want["bytes"]!!.jsonPrimitive.content.toLong(), plan.bytes, c.id)
            val skipped = (want["skipped"] as JsonArray).map { it.jsonObject }.map {
                listOf(it["name"]!!.jsonPrimitive.content, it["application"]!!.jsonPrimitive.content, it["reason"]!!.jsonPrimitive.content, it["nodes"]!!.jsonPrimitive.content)
            }
            assertEquals(skipped, plan.skipped.map { listOf(it.name, it.application, it.reason, it.nodes.toString()) }, c.id)
        }
    }

    @Test
    fun everyRefusalOfAKindsOwnCheckIsTheWebs() = runTest {
        for (c in cases.filter { it.at == "plan" }) {
            val e = assertFailsWith<ImportRefused>(c.id) { SubtreeImport.plan(SubtreeImport.open(c.input).manifest, PlayedImport.real()) }
            assertEquals(c.refused, e.refusal.wire, "${c.id}: ${e.message}")
        }
    }

    @Test
    fun everyRefusalAtImportIsTheWebs_andNothingIsLeft() = runTest {
        for (c in cases.filter { it.at == "import" }) {
            val target = PlayedImport()
            val e = try {
                run(target, c.input)
                fail("${c.id}: imported")
            } catch (e: ImportRefused) {
                e
            } catch (e: SubtreeImport.Failed) {
                assertTrue(e.rolledBack, c.id)
                assertEquals(listOf(target.made.first().id), target.trashed, "${c.id}: the top-level node made is trashed")
                e.cause as? ImportRefused ?: fail("${c.id}: failed with ${e.cause}")
            }
            assertEquals(c.refused, e.refusal.wire, "${c.id}: ${e.message}")
        }
    }

    @Test
    fun theWebsDownloadIsPlannedWhole_everyKindByItsApplication() = runTest {
        val opened = SubtreeImport.open(ImportInput.Zip(CountingSource("Trip.zip", ImportFixtures.webDownload())))
        val plan = SubtreeImport.plan(opened.manifest, PlayedImport.real())
        assertEquals(emptyList(), plan.skipped)
        assertEquals(7, plan.nodes)
        // each kind reaches its own application's import (lever: route a MEMORY to Depot's file hook in AppKinds.imports)
        val kinds = PlayedImport.real()
        assertEquals(mapOf("FILE" to "depot", "DEPOT_DROPBOX" to "depot", "EXPENSES_POT" to "expenses", "MEMORY" to "memories", "CHAPTER" to "memories"),
            listOf("FILE", "DEPOT_DROPBOX", "EXPENSES_POT", "MEMORY", "CHAPTER").associateWith { kinds.hook(it)!!.application })
        assertTrue(kinds.hook("DEPOT_DROPBOX")!!.asFolder)
        assertNull(kinds.hook("SHARE_BOOKMARK"), "a kept link has no import (ADR 0120 decision 8)")
        fun content(n: Manifest.Node, kind: String): JsonObject? = if (n.kind == kind) n.content else n.children.firstNotNullOfOrNull { content(it, kind) }
        assertNull(PotImport.validate(content(opened.manifest.root, "EXPENSES_POT")!!))
        assertNull(MemoryImport.validate(content(opened.manifest.root, "MEMORY")!!))
        assertNull(MemoryImport.validate(content(opened.manifest.root, "CHAPTER")!!))
        assertNull(DepotImport.validateFile(content(opened.manifest.root, "FILE")!!))
    }

    @Test
    fun theWebsDownloadIsWalkedInItsOrder_eachHookTakingItsCheckedPayloads() = runTest {
        val target = PlayedImport()
        val progress = ArrayList<SubtreeImport.Progress>()
        val result = SubtreeImport.run(
            SubtreeImport.open(ImportInput.Zip(CountingSource("Trip.zip", ImportFixtures.webDownload()))), "scope", "level", target, target.played(),
            PlayedImport.tokenInput,
        ) { progress += it }
        assertEquals(
            listOf(
                listOf("FOLDER", "Trip", "level"), listOf("FILE", "notes.txt", "new1"), listOf("FOLDER", "Inbox", "new1"), listOf("FOLDER", "Anna", "new3"),
                listOf("EXPENSES_POT", "Budget", "new1"), listOf("MEMORY", "Lisbon", "new1"), listOf("CHAPTER", "Day one", "new6"),
            ),
            target.made.map { listOf(it.kind, it.name, it.parent.toString()) },
        )
        assertEquals(listOf("Packing list: passport, charger, sunscreen.\n"), target.made[1].payloads)
        assertEquals(2, target.made[4].payloads.size, "the Pot's receipt and its table")
        assertEquals(1, target.made[5].payloads.size, "the Memory's photo")
        assertEquals("new1", result.nodeId)
        assertEquals(7, result.nodes)
        assertEquals(SubtreeImport.Progress(7, 7, progress.last().bytesTotal, progress.last().bytesTotal), progress.last())
        assertEquals(emptyList(), target.trashed)
    }

    @Test
    fun aNameIsFreedPastTheSiblings_andPastTheServersOwnAnswer() = runTest {
        val sound = cases.first { it.id == "sound" }
        val target = PlayedImport(existing = mapOf("level" to listOf("FOLDER" to "home", "MEMORY" to "Home (2)")), serverTaken = mutableSetOf("Home (2)"))
        val result = run(target, sound.input, "level")
        // "Home" folds as a sibling folder's "home"; a Memory's "Home (2)" is not a folder's name; the server holds "Home (2)"
        assertEquals("Home (3)", result.name)
        assertEquals("Home (3)", target.made.first().name)
        assertEquals("a.txt", target.made[1].name, "what is under it keeps its names")
    }

    @Test
    fun aChecksumThatFailsRollsBackEveryTopLevelNodeMade() = runTest {
        // lever: drop the trash in SubtreeImport.run's failure path
        val bad = cases.first { it.id == "sha256_mismatch" }
        val target = PlayedImport()
        val e = assertFailsWith<SubtreeImport.Failed> { run(target, bad.input) }
        assertEquals("checksum", (e.cause as ImportRefused).refusal.wire)
        assertEquals(listOf("Home"), target.made.map { it.name }, "the folder was made before the file's payload refused")
        assertEquals(listOf("new1"), target.trashed)
        assertTrue(e.rolledBack)
        assertEquals(1, e.done)
        assertEquals(2, e.total)
    }

    @Test
    fun aCancelRollsBackToo() = runTest {
        val sound = cases.first { it.id == "sound" }
        val target = PlayedImport()
        val entered = CompletableDeferred<Unit>()
        val kinds = SubtreeImport.Kinds { kind ->
            target.played().hook(kind)?.let { h ->
                if (kind != "FILE") h else NodeImport.Hook(h.application, h.schema, h.validate) { _ ->
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await() // never answers
                    fail("unreachable")
                }
            }
        }
        val job = async { SubtreeImport.run(SubtreeImport.open(sound.input), "scope", null, target, kinds, PlayedImport.tokenInput) }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("new1"), target.trashed)
    }

    @Test
    fun importTheRestCreatesExactlyTheRestAndNamesWhatItLeftOut() = runTest {
        val left = cases.first { it.id == "left_out" }
        val target = PlayedImport()
        val result = run(target, left.input)
        assertEquals(listOf("Home", "keep.txt"), target.made.map { it.name })
        assertEquals(listOf("Budget", "newer.txt", "Taxes", "Mixed"), result.skipped.map { it.name })
    }

    @Test
    fun noRoomRefusesBeforeAnyWrite() = runTest {
        val sound = cases.first { it.id == "sound" }
        val target = PlayedImport(room = false)
        val e = assertFailsWith<SubtreeImport.NoRoom> { run(target, sound.input) }
        assertEquals(36L, e.bytes)
        assertEquals(36L, target.askedRoom)
        assertEquals(emptyList(), target.made.map { it.name })
        // a server that does not answer never blocks
        val unknown = PlayedImport(room = null)
        assertNotNull(run(unknown, ImportFixtures.cases().first { it.id == "sound" }.input))
    }

    @Test
    fun aMemorysRestoredFactsAreSealedAtBirth() {
        val tier = JsonObject(mapOf("v" to JsonPrimitive(1), "capturedAt" to JsonPrimitive(1000), "capturedAtSource" to JsonPrimitive("exif")))
        val medium = JsonObject(mapOf(
            "capturedAt" to JsonPrimitive("2025-09-14T08:00:00.000Z"), "capturedAtCorrected" to JsonPrimitive(true),
            "location" to JsonObject(mapOf("place" to JsonPrimitive(" Lisbon "))),
        ))
        val out = MemoryImport.restored(tier, medium, "image/jpeg", "IMG.jpg")!!
        assertEquals(listOf("v", "capturedAt", "capturedAtOverride", "capturedAtSource", "placeName"), out.keys.toList())
        assertEquals(1_757_836_800_000L, out["capturedAtOverride"]!!.jsonPrimitive.content.toLong())
        assertEquals("Lisbon", out["placeName"]!!.jsonPrimitive.content)
        // a track has no place; a time not corrected is the bytes' own
        val gpx = MemoryImport.restored(tier, JsonObject(mapOf("location" to JsonObject(mapOf("place" to JsonPrimitive("x"))), "track" to JsonObject(mapOf("name" to JsonPrimitive("Tour"))))), "application/gpx+xml", "a.gpx")!!
        assertEquals(listOf("v", "capturedAt", "capturedAtSource", "trackName"), gpx.keys.toList())
        assertSame(tier, MemoryImport.restored(tier, JsonObject(emptyMap()), "image/jpeg", "IMG.jpg"))
    }
}
