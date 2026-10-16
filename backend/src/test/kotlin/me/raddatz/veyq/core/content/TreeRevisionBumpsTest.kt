package me.raddatz.veyq.core.content

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
 * Every write a level view shows moves the scope's tree revision (ADR 0081), and a write that forgets is a change the
 * live view never learns of — a defect no request fails on and no page shows, until a user wonders why a peer's
 * upload needs a reload. The per-path tests (`TreeRevisionCoreSliceTest`, `DepotTreeRevisionSliceTest`) pin the sites
 * that exist; this pins the **set**: reading the sources, it fails on the day someone adds a writer of node or
 * attachment rows that is not classified here, or deletes a bump from one that is.
 *
 * Three rules, each over the main sources of every backend project:
 *  1. each [BUMPING] function (a store's public write) contains a `bumpTree(` call;
 *  2. a non-private function of such a file that writes node or attachment rows itself is one of them — a new public
 *     mutator is red until it is listed (and so bumps), a private helper is covered by the listed function calling it;
 *  3. no other file writes those rows, but the [EXEMPT] ones, each with the reason it moves no level view's revision
 *     itself (an eraser: the scope is gone, or the caller — the purge — bumps).
 *
 * A source scan, not a runtime proof: it cannot see that the bump is on the right branch — the per-path tests do.
 */
class TreeRevisionBumpsTest {

    private val root: Path = Paths.get("").toAbsolutePath().normalize() // backend/, the Gradle root every backend test runs in

    /** Rows of the tree a level view shows: nodes and the attachments beside them. */
    private val writes = Regex(
        """NodeEntity\(\)|NodeEntity\.(update|delete|deleteById)\(|NodeAttachmentEntity\(\)|NodeAttachmentEntity\.(update|delete|deleteById)\(|\bn\.deletedAt = """,
    )

    /** file (relative to `backend/`) → the functions that write, each of which must bump. */
    private val bumping = mapOf(
        "core/src/main/kotlin/me/raddatz/veyq/core/content/NodesStore.kt" to
            setOf("create", "createIdentityFolder", "applyUpdate", "deleteSubtree", "restoreSubtree"),
        "core/src/main/kotlin/me/raddatz/veyq/core/content/NodeSubtreePurge.kt" to setOf("erase"),
        "core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentStore.kt" to setOf("attach", "detach"),
        "core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt" to setOf("create"),
    )

    /** Writers that move no revision on their own, and why. */
    private val exempt = mapOf(
        "core/src/main/kotlin/me/raddatz/veyq/core/node/NodeScopeEraser.kt" to "erases the scope's rows with the scope (and its revision row)",
        "core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentScopeEraser.kt" to "erases the scope's attachments with the scope",
        "core/src/main/kotlin/me/raddatz/veyq/core/blob/AttachmentNodeSubtreeEraser.kt" to "runs inside NodeSubtreePurge.erase, which bumps",
    )

    @Test
    fun everyListedWriterBumps() {
        for ((file, names) in bumping) {
            val functions = functionsOf(root.resolve(file))
            for (name in names) {
                val body = functions[name] ?: error("$file has no function `$name` — renamed? update this list")
                assertTrue(body.contains("bumpTree("), "$file: `$name` writes node or attachment rows and does not call bumpTree (ADR 0081)")
            }
        }
    }

    @Test
    fun noUnlistedPublicFunctionOfAWriterFileWritesRows() {
        for ((file, names) in bumping) {
            val unlisted = functionsOf(root.resolve(file), publicOnly = true)
                .filter { (name, body) -> name !in names && writes.containsMatchIn(body) && !body.contains("bumpTree(") }
                .keys
            assertEquals(emptySet<String>(), unlisted, "$file: these write node or attachment rows and are not in the bumping list")
        }
    }

    @Test
    fun noOtherFileWritesNodeOrAttachmentRows() {
        val strangers = mainSources()
            .filter { rel -> rel !in bumping && rel !in exempt }
            .filter { rel -> writes.containsMatchIn(root.resolve(rel).readText()) }
        assertEquals(emptyList<String>(), strangers, "these write node or attachment rows and are neither bumping nor exempt (with a reason) — ADR 0081")
    }

    /** The rules read the sources they claim to: every listed file exists and the scan sees the known writers. */
    @Test
    fun theScanSeesWhatItClaimsToGuard() {
        val sources = mainSources()
        for (file in bumping.keys + exempt.keys) assertTrue(file in sources, "$file is not a main source any more — update this test")
        for (file in bumping.keys) assertTrue(writes.containsMatchIn(root.resolve(file).readText()), "$file matches no write marker — has the marker list gone stale?")
    }

    private fun mainSources(): List<String> {
        val roots = listOf(root.resolve("core/src/main/kotlin")) + (Files.list(root.resolve("apps")).use { dirs ->
            dirs.map { it.resolve("src/main/kotlin") }.filter { Files.isDirectory(it) }.toList()
        })
        return roots.flatMap { r ->
            Files.walk(r).use { s -> s.filter { it.extension == "kt" }.map { root.relativize(it).invariantSeparatorsPathString }.toList() }
        }
    }

    /**
     * Name → source of each member function (4-space indentation: a class member) of a file. A function's body runs to
     * the next member line; overloads would merge under one name, which is stricter, never looser.
     */
    private fun functionsOf(file: Path, publicOnly: Boolean = false): Map<String, String> {
        val lines = file.readText().lines()
        val out = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in lines) {
            val member = Regex("""^    ((?:@\w+\s+)*)((?:(?:private|internal|protected|override|inline)\s+)*)fun\s+(?:<[^>]+>\s+)?(\w+)""").find(line)
            when {
                member != null -> {
                    val isPrivate = member.groupValues[2].contains("private")
                    current = if (publicOnly && isPrivate) null else out.getOrPut(member.groupValues[3]) { StringBuilder() }
                    current?.appendLine(line)
                }
                line.startsWith("    ") && !line.startsWith("     ") && line.isNotBlank() && !line.startsWith("    }") && !line.startsWith("    )") && !line.startsWith("    //") ->
                    current = null // the next member (a KDoc opener, an annotation, a property): not this body
                else -> current?.appendLine(line)
            }
        }
        return out.mapValues { it.value.toString() }
    }
}
