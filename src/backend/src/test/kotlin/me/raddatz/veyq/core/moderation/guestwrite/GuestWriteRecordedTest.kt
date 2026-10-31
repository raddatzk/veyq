package me.raddatz.veyq.core.moderation.guestwrite

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
 * Every guest write records (ADR 0093 § 10, en.moderation.guest-write-record): the per-path slices
 * (`GuestWriteRecordCoreSliceTest`, `GuestWriteRecordMemoriesSliceTest`, `GuestWriteRecordDepotSliceTest`) pin the
 * writes that exist; this pins the **set**, in the shape of `TreeRevisionBumpsTest`. Reading the main sources of every
 * backend project, each resource operation (`override fun` of a class in a `…Resource.kt` file) that takes a guest's
 * write credential — a contributor token in a header or a body (`contributorToken`), or the link's write proof
 * (`xShareWrite`) — either resolves the client once for its write (`client.seen()`, handed down to the write that
 * records it in its own transaction) or is in [exempt] with the reason it enters no editor. A new guest write is red
 * until it is classified.
 *
 * A source scan, not a runtime proof: it sees that the resource resolves the client and hands it on, never that the
 * write records it on the right branch — the slices do.
 */
class GuestWriteRecordedTest {

    private val root: Path = Paths.get("").toAbsolutePath().normalize() // src/backend/, the Gradle root every backend test runs in

    /** A guest's write credential, as a resource names it: the contributor token (header or body) or the write proof. */
    private val credential = Regex("""\b(xContributorToken|contributorToken|xShareWrite)\b""")

    private val recording = "client.seen()"

    /** Operation → why it takes a guest's credential and records nothing. */
    private val exempt = mapOf(
        "startShareBlobUploads" to "opens upload sessions; no object exists yet, and the commit (completeShareMedia, completeShareFile) records",
        "presignShareBlobUploadWindows" to "signs the next window of PUTs; writes no row",
        "abandonShareNode" to "erases the guest's own empty placeholder: it enters no editor, and the node's records go with it",
        "deleteShareMedia" to "purges the guest's own medium: it enters no editor, and the medium's record goes with it",
        "reportInShare" to "a report (ADR 0093 § 1): it edits nothing, and a report keeps its own rows",
        "getShareGuestNotice" to "a read (ADR 0093 §§ 7, 12): a guest uploader's notice of a removal order or a moderator's statement; it writes nothing",
    )

    /** The writes known to record today — the scan must see each, or it is reading the wrong sources. */
    private val known = setOf(
        "createShareIdentity", "createShareNode", "updateShareNode", "completeShareMedia", "ensureShareDropFolder", "completeShareFile",
    )

    @Test
    fun everyResourceOperationTakingAGuestCredentialRecordsOrIsExempt() {
        val unclassified = guestOperations().filter { (name, body) -> name !in exempt && !body.contains(recording) }.keys
        assertEquals(emptySet<String>(), unclassified, "these take a guest's write credential and neither resolve the client for the record nor are exempt (with a reason)")
    }

    @Test
    fun anExemptOperationRecordsNothingAndStillTakesACredential() {
        val operations = guestOperations()
        for (name in exempt.keys) {
            val body = operations[name] ?: error("exempt `$name` takes no guest credential any more (or is gone) — update this list")
            assertTrue(!body.contains(recording), "`$name` is exempt but resolves the client — classify it as recording")
        }
    }

    @Test
    fun theScanSeesTheKnownWrites() {
        val operations = guestOperations()
        for (name in known) {
            val body = operations[name] ?: error("`$name` is not seen as a guest operation — has the credential marker gone stale?")
            assertTrue(body.contains(recording), "`$name` must resolve the client for its record")
        }
    }

    /** Name → source of each resource operation whose signature or body names a guest credential. */
    private fun guestOperations(): Map<String, String> =
        mainSources().filter { it.fileName.toString().endsWith("Resource.kt") }
            .flatMap { overridesOf(it).entries }
            .filter { (_, body) -> credential.containsMatchIn(body) }
            .associate { it.key to it.value }

    private fun mainSources(): List<Path> {
        val roots = listOf(root.resolve("core/src/main/kotlin")) + (Files.list(root.resolve("apps")).use { dirs ->
            dirs.map { it.resolve("src/main/kotlin") }.filter { Files.isDirectory(it) }.toList()
        })
        return roots.flatMap { r -> Files.walk(r).use { s -> s.filter { it.extension == "kt" }.toList() } }
            .sortedBy { root.relativize(it).invariantSeparatorsPathString }
    }

    /**
     * Name → source of each `override fun` member (4-space indentation) of a file, its signature lines included; a body
     * runs to the next member line, as `TreeRevisionBumpsTest` reads them.
     */
    private fun overridesOf(file: Path): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in file.readText().lines()) {
            val member = Regex("""^    (?:@\w+\s+)*((?:(?:private|internal|protected|override|inline)\s+)*)fun\s+(\w+)""").find(line)
            when {
                member != null -> {
                    current = if ("override" in member.groupValues[1]) out.getOrPut(member.groupValues[2]) { StringBuilder() } else null
                    current?.appendLine(line)
                }
                line.startsWith("    ") && !line.startsWith("     ") && line.isNotBlank() && !line.startsWith("    }") && !line.startsWith("    )") && !line.startsWith("    //") ->
                    current = null
                else -> current?.appendLine(line)
            }
        }
        return out.mapValues { it.value.toString() }
    }
}
