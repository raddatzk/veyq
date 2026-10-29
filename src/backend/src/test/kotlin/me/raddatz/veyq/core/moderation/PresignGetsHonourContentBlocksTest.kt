package me.raddatz.veyq.core.moderation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every byte path honours a content block** (uc.moderation.take-a-measure, the Definition of done of
 * cr-moderation-review-and-measures): every byte the server serves is a presigned GET, and every presigned GET is made by
 * `BlobStore.presignGets` — so the invariant rests on its callers. The per-path tests (`ModerationMemoriesSliceTest`,
 * `ModerationDepotSliceTest`) pin the answers of the callers that exist; this pins the **set**, in the shape of
 * `TreeRevisionBumpsTest`: reading the main sources of every backend project, it fails on the day someone adds a caller of
 * `presignGets(` that neither asks the block before it ([CHECKED]) nor is exempt with its reason ([EXEMPT]). The block is
 * asked as `blobBlocked(` for one id or `blockedBlobs(` for a batch (inbox #537: a batch asks once, never once per id).
 *
 * A source scan, not a runtime proof: it cannot see that the check is on the right branch — the slice tests do. Its bite:
 * add an unchecked caller (or drop the `blobBlocked(` from a checked one) → red.
 */
class PresignGetsHonourContentBlocksTest {

    private val root: Path = Paths.get("").toAbsolutePath().normalize() // src/backend/

    private val call = Regex("""\bpresignGets\(""")
    private val blockCheck = Regex("""\b(?:blobBlocked|blockedBlobs)\(""")

    /** file → the functions that presign a GET, each of which asks `blobBlocked(` or `blockedBlobs(` before it. */
    private val checked = mapOf(
        "core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingHandler.kt" to setOf("resolveBlob"),
        "core/src/main/kotlin/me/raddatz/veyq/core/blob/BlobUploadHandler.kt" to setOf("download"),
    )

    /** Files that call `presignGets(` and ask no block, each with why. */
    private val exempt = mapOf(
        "core/src/main/kotlin/me/raddatz/veyq/core/blobstore/BlobStore.kt" to "the definition itself",
        "core/src/main/kotlin/me/raddatz/veyq/core/moderation/review/CaseReviewResource.kt" to
            "the moderator's review reads the pinned held objects: a block never hides evidence from the moderator",
    )

    @Test
    fun everyCheckedCallerAsksTheBlockBeforeItPresigns() {
        for ((file, names) in checked) {
            val functions = functionsOf(root.resolve(file))
            for (name in names) {
                val body = functions[name] ?: error("$file has no function `$name` — renamed? update this list")
                val presign = call.find(body)?.range?.first ?: error("$file: `$name` presigns nothing — update this list")
                val check = blockCheck.find(body)?.range?.first ?: -1
                assertTrue(check in 0 until presign, "$file: `$name` presigns a GET without asking ModerationBlocks.blobBlocked/blockedBlobs first")
            }
        }
    }

    @Test
    fun noOtherFilePresignsAGet() {
        val strangers = mainSources()
            .filter { it !in checked && it !in exempt }
            .filter { call.containsMatchIn(root.resolve(it).readText()) }
        assertEquals(emptyList<String>(), strangers, "these call presignGets and neither ask the content block nor are exempt with a reason")
    }

    @Test
    fun noUnlistedFunctionOfACheckedFilePresigns() {
        for ((file, names) in checked) {
            val unlisted = functionsOf(root.resolve(file)).filter { (name, body) -> name !in names && call.containsMatchIn(body) }.keys
            assertEquals(emptySet<String>(), unlisted, "$file: these presign a GET and are not in the checked list")
        }
    }

    /** The scan reads what it claims to: every listed file exists and calls presignGets. */
    @Test
    fun theScanSeesWhatItClaimsToGuard() {
        val sources = mainSources()
        for (file in checked.keys + exempt.keys) {
            assertTrue(file in sources, "$file is not a main source any more — update this test")
            assertTrue(call.containsMatchIn(root.resolve(file).readText()), "$file calls presignGets no more — update this test")
        }
    }

    private fun mainSources(): List<String> {
        val roots = listOf(root.resolve("core/src/main/kotlin")) + (Files.list(root.resolve("apps")).use { dirs ->
            dirs.map { it.resolve("src/main/kotlin") }.filter { Files.isDirectory(it) }.toList()
        })
        return roots.flatMap { r ->
            Files.walk(r).use { s -> s.filter { it.extension == "kt" }.map { root.relativize(it).invariantSeparatorsPathString }.toList() }
        }
    }

    /** Name → source of each member function (4-space indentation), as `TreeRevisionBumpsTest` reads them. */
    private fun functionsOf(file: Path): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in file.readText().lines()) {
            val member = Regex("""^    ((?:@\w+\s+)*)((?:(?:private|internal|protected|override|inline)\s+)*)fun\s+(?:<[^>]+>\s+)?(\w+)""").find(line)
            when {
                member != null -> current = out.getOrPut(member.groupValues[3]) { StringBuilder() }.also { it.appendLine(line) }
                line.startsWith("    ") && !line.startsWith("     ") && line.isNotBlank() && !line.startsWith("    }") && !line.startsWith("    )") && !line.startsWith("    //") ->
                    current = null
                else -> current?.appendLine(line)
            }
        }
        return out.mapValues { it.value.toString() }
    }
}
