package me.raddatz.veyq.mobile.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.memories.models.ScopeMediaPage
import me.raddatz.veyq.mobile.api.memories.models.ScopeMedium
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.memories.MemoriesSearch
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * ADR 0146's bounds, measured (§ Consequences; opt-in, `VEYQ_MEASURE=1`: a timing waits for a quiet machine and is no check
 * of every run). At the volume harness's shape — 7,000 nodes over three spaces (one of 5,000), 10,000 photos in one Memory,
 * 445 members — over real seals (each Memory's meta, each medium's listed tier, blob key and blob meta opened by the
 * core's own openers; the network stubbed by pages made here):
 * - **B2, the build:** the first focus until every space's nodes and photos are documents — bound 2.6 s;
 * - **B1, a keystroke:** "K", "Ke", "Kev", a year chip and Backspace, each answered into a `SearchShown` — bound 100 ms;
 * - **B3, the index's memory:** the used heap the documents add (the JVM only), recorded, not gated.
 * Each line is printed as `veyq-measure:` with the target; the bounds are asserted only in a measuring run.
 */
class SearchVolumeMeasure {
    private val suite = V1(testPrimitives)
    private val key = testPrimitives.randomBytes(32)

    private fun uuid(i: Int, space: Int = 0) = "00000000-0000-4000-8" + space.toString().padStart(3, '0') + "-" + i.toString().padStart(12, '0')

    private val words = listOf("Kevelaer", "Rome", "Summer", "Beach", "Trip", "Family", "Garden", "Winter", "Birthday", "Kiel", "Paris", "Hike")

    /** One space's tree: folders of Memories, every tenth Memory dated, placed and with a participant in its sealed meta. */
    private fun tree(scope: String, space: Int, count: Int): List<OpenNode> {
        val out = ArrayList<OpenNode>(count)
        var folder: String? = null
        for (i in 0 until count) {
            val id = uuid(i, space)
            if (i % 50 == 0) {
                folder = id
                out += OpenNode(scope, id, null, "FOLDER", "${words[i / 50 % words.size]} folder $i", key)
                continue
            }
            val meta = if (i % 10 == 1) NodeMetaWrite.seal(suite, scope, id, key, buildJsonObject {
                put("v", 1)
                putJsonObject("date") { put("mode", "single"); put("from", "20${10 + i % 15}-0${1 + i % 9}-15"); put("to", "20${10 + i % 15}-0${1 + i % 9}-15") }
                putJsonObject("location") { put("name", "${words[(i + 3) % words.size]}stadt"); put("nameSource", "manual"); put("derivedMode", "fixed") }
                putJsonObject("participants") { put("userIds", JsonArray(listOf(JsonPrimitive("user-${i % 445}")))) }
            }) else null
            out += OpenNode(scope, id, folder, "MEMORY", "${words[i % words.size]} ${2000 + i % 30} $i", key, wrappedMeta = meta)
        }
        return out
    }

    /** The 10,000 photos of one Memory as ten listing pages of 1,000, sealed as the web uploads them. */
    private fun pages(scope: String, memory: String): List<ScopeMediaPage> = (0 until 10).map { p ->
        ScopeMediaPage(
            media = (0 until 1000).map { j ->
                val i = p * 1000 + j
                val mediaId = uuid(i, 900)
                val blobId = uuid(i, 901)
                val kBlob = testPrimitives.randomBytes(32)
                ScopeMedium(
                    blockedByOperator = false, nodeId = memory, mediaId = mediaId, blobId = blobId, chunkCount = 1, byteSize = 1000,
                    wrappedBlobKey = b64(suite.seal(Context("blob-key", "scopeId" to scope, "ownerId" to mediaId, "blobId" to blobId, "role" to "ORIGINAL"), key, kBlob)),
                    metaRevision = 1, wrappedBlobMeta = b64(BlobMeta.seal(suite, kBlob, "image/jpeg", "IMG_${i.toString().padStart(5, '0')}.jpg", null)),
                    wrappedMeta = b64(suite.sealField(
                        Context("media-meta", "scopeId" to scope, "mediaId" to mediaId), key,
                        """{"v":1,"capturedAt":${1_500_000_000_000L + i * 3_600_000L},"placeName":"${words[i % words.size]}"}""".encodeToByteArray(),
                    )),
                )
            },
            more = p < 9, nodes = emptyList(),
        )
    }

    @Test
    fun theBuildAndAKeystrokeHoldTheirBoundsAtVolume() = runBlocking {
        if (testEnv("VEYQ_MEASURE") != "1") return@runBlocking println("veyq-measure: skipped (VEYQ_MEASURE is not 1)")
        val scopes = listOf("00000000-0000-4000-8000-00000000a001", "00000000-0000-4000-8000-00000000a002", "00000000-0000-4000-8000-00000000a003")
        val trees = mapOf(scopes[0] to tree(scopes[0], 1, 5000), scopes[1] to tree(scopes[1], 2, 1000), scopes[2] to tree(scopes[2], 3, 1000))
        val memory = trees.getValue(scopes[0]).first { it.kind == "MEMORY" }
        val pages = pages(scopes[0], memory.id)
        val people = (0 until 445).map { Person("user-$it", "user$it", "${words[it % words.size]} Person $it") }
        val door = object : SearchModel.Door {
            private var held: Pair<Any, Any>? = null
            override suspend fun spaces() = scopes.mapIndexed { i, s -> SpaceRef(s, if (i == 0) "TENANT" else "GROUP", "Space $i") }
            @Suppress("UNCHECKED_CAST")
            override suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V =
                held?.takeIf { it.first === trees }?.second as V? ?: derive(trees).also { held = trees to it }
            override suspend fun treeMoved(scopeId: String) = false
            override fun notRead() = emptySet<String>()
            override fun openMeta(node: OpenNode): JsonObject? =
                runCatching { NodeMetaWrite.openForWrite(suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull()
            override fun online() = true
            override fun people() = people
            override suspend fun settings() = SettingsSection.entries.toList()
            override fun where() = Results.SearchContext(scopes[1], null)
            override fun applicationsOff() = emptySet<String>()
            override suspend fun scopeDocuments(
                app: ApplicationSearch, scopeId: String, tag: String?, previous: Map<String, ScopeDocument>, nodes: Map<String, OpenNode>,
                unnamed: (Boolean) -> String,
            ): ScopeDocumentsAnswer? {
                if (tag != null) return null
                if (scopeId != scopes[0]) return ScopeDocumentsAnswer("t", emptyList())
                return ScopeDocumentsAnswer("t", pages.flatMap { MemoriesSearch.pageDocuments(suite, scopeId, it, nodes, previous, unnamed) })
            }
            override fun openNode(scopeId: String, nodeId: String) {}
            override fun openPerson(userId: String) {}
        }
        val heapBefore = usedHeapAfterGc()
        val model = SearchModel(door, AppKinds.search, { it.key }, OpenRequests(), SearchContributions(), { 1_763_208_000_000L })
        try {
            val clock = TimeSource.Monotonic
            val started = clock.markNow()
            model.focus()
            model.settled()
            val build = started.elapsedNow()
            val heapAfter = usedHeapAfterGc()
            suspend fun timed(label: String, act: () -> Unit): Duration {
                val mark = clock.markNow()
                act()
                model.settled()
                val d = mark.elapsedNow()
                println("veyq-measure: B1 $label ${d.inWholeMilliseconds} ms (total ${model.current.total})")
                return d
            }
            val keys = listOf(
                timed("\"K\"") { model.text("K") },
                timed("\"Ke\"") { model.text("Ke") },
                timed("\"Kev\"") { model.text("Kev") },
                timed("\"2017\" typed") { model.text("2017") },
                timed("year chip chosen") { model.choose(model.current.suggestions.indexOfFirst { it.key == "chip:year:2017" }) },
                timed("Backspace (the chip removed)") { model.removeLastChip() },
            )
            println("veyq-measure: B2 build ${build.inWholeMilliseconds} ms (7,000 nodes over 3 spaces, 10,000 photos, 445 members)")
            println("veyq-measure: B3 index ${if (heapBefore != null && heapAfter != null) "${(heapAfter - heapBefore) / 1024 / 1024} MB used heap (JVM)" else "not measured on this target"}")
            assertTrue(build.inWholeMilliseconds <= 2_600, "B2: the build took ${build.inWholeMilliseconds} ms")
            assertTrue(keys.all { it.inWholeMilliseconds <= 100 }, "B1: a keystroke took ${keys.maxOf { it.inWholeMilliseconds }} ms")
        } finally {
            model.dispose()
        }
    }
}
