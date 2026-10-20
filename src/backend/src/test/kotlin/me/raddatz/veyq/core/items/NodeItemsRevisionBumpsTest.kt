package me.raddatz.veyq.core.items

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
 * Every write of an application's item rows moves the node's item-list revision (ADR 0033, inbox #410), and a write
 * that forgets is a change the live list never learns of: no request fails and no page shows it, until a user wonders
 * why a peer's upload needs a reload. The per-path slice tests (`MediaSliceTest`, `MediaTrashSliceTest`) pin the
 * sites that exist; this pins the **set** for Memories' media, reading the sources, on the day someone adds a writer
 * of media rows that is not classified here, or deletes a bump from one that is. The scan is Memories-shaped because
 * Memories is the primitive's only user; an application that adopts it adds its own writers to [bumping].
 *
 * Four rules, each over the main sources of every backend project:
 *  1. each [bumping] function (a store's public write) contains a call to core's `items.bump(`;
 *  2. a non-private function of such a file that writes media rows itself is one of them or one of the [exemptFunctions]
 *     (a purge of rows no listing shows), so a new public mutator is red until it is classified;
 *  3. no other file writes media rows but the [exempt] ones, each with the reason it moves no revision itself;
 *  4. **nothing but core's `items` package writes the counters**: a bump that bypasses [NodeItemsRevisions] skips its
 *     lock order and its atomic increment.
 *
 * A source scan, not a runtime proof: it cannot see that the bump is on the right branch — the slice tests do.
 */
class NodeItemsRevisionBumpsTest {

    private val root: Path = Paths.get("").toAbsolutePath().normalize() // src/backend/, the Gradle root every backend test runs in

    /** Rows of the gallery a list shows. */
    private val writes = Regex(
        """MediaEntity\(\)|MediaEntity\.(update|delete|deleteById)\(|\.deletedAt = |\.bucketId = """,
    )

    private val store = "apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaStore.kt"

    /** file (relative to `src/backend/`) -> the functions that write, each of which must bump. */
    private val bumping = mapOf(
        store to setOf("attach", "trashMedia", "restoreMedia", "setMeta", "deleteBucketReparent", "moveMedia"),
        "apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaShareGuestWrites.kt" to setOf("deleteOwn"),
    )

    /** Public functions of a bumping file that write media rows and bump nothing, and why. */
    private val exemptFunctions = mapOf(
        "purgeMediaRows" to "the FK-ordered body of the purges below and of a guest's delete (which bumps); a purge removes trashed or unlisted rows",
        "purgeMedia" to "erases an already-trashed medium: the trash itself moved the revision, and no listing shows it",
        "purgeTrashedBefore" to "the retention purge of trashed media, which no live listing shows (the trash moved the revision)",
    )

    /** Writers that move no revision on their own, and why. */
    private val exempt = mapOf(
        "apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaScopeEraser.kt" to "erases the scope's media with the scope (and its counters, in core's node eraser)",
    )

    /** The only files that may name the counters' entity: core's items package, and the scope eraser that deletes by node. */
    private val counterWriters = setOf(
        "core/src/main/kotlin/me/raddatz/veyq/core/items/NodeItemsRevisionEntity.kt",
        "core/src/main/kotlin/me/raddatz/veyq/core/items/NodeItemsRevisions.kt",
        "core/src/main/kotlin/me/raddatz/veyq/core/items/NodeItemsRevisionNodeSubtreeEraser.kt",
        "core/src/main/kotlin/me/raddatz/veyq/core/node/NodeScopeEraser.kt",
    )

    @Test
    fun everyListedWriterBumps() {
        for ((file, names) in bumping) {
            val functions = functionsOf(root.resolve(file))
            for (name in names) {
                val body = functions[name] ?: error("$file has no function `$name` - renamed? update this list")
                assertTrue(body.contains("items.bump("), "$file: `$name` writes item rows and does not call core's items.bump (ADR 0033)")
            }
        }
    }

    @Test
    fun noUnlistedPublicFunctionOfAWriterFileWritesRows() {
        for ((file, names) in bumping) {
            val unlisted = functionsOf(root.resolve(file), publicOnly = true)
                .filter { (name, body) -> name !in names && name !in exemptFunctions && writes.containsMatchIn(body) && !body.contains("items.bump(") }
                .keys
            assertEquals(emptySet<String>(), unlisted, "$file: these write media rows and are neither in the bumping list nor exempt")
        }
    }

    @Test
    fun noOtherFileWritesMediaRows() {
        val strangers = mainSources()
            .filter { rel -> rel !in bumping && rel !in exempt }
            .filter { rel -> rel.startsWith("apps/memories/") && writes.containsMatchIn(root.resolve(rel).readText()) }
        assertEquals(emptyList<String>(), strangers, "these write media rows and are neither bumping nor exempt (with a reason) - ADR 0033")
    }

    @Test
    fun onlyCoreWritesTheCounters() {
        val strangers = mainSources()
            .filter { rel -> rel !in counterWriters }
            .filter { rel -> root.resolve(rel).readText().contains("NodeItemsRevisionEntity") }
        assertEquals(emptyList<String>(), strangers, "these touch the counters' entity directly instead of NodeItemsRevisions")
    }

    /** The rules read the sources they claim to: every listed file exists and the scan sees the known writers. */
    @Test
    fun theScanSeesWhatItClaimsToGuard() {
        val sources = mainSources()
        for (file in bumping.keys + exempt.keys + counterWriters) assertTrue(file in sources, "$file is not a main source any more - update this test")
        assertTrue(writes.containsMatchIn(root.resolve(store).readText()), "$store matches no write marker - has the marker list gone stale?")
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
