package me.raddatz.veyq.mobile.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every live case of an application's module or of `:composition` installs its applications itself (inbox #1191), so it
 * runs alone — a single class or method picked by `--tests` — as it runs in its suite: before that, `GuestGapsLiveTest`,
 * `ReceivedLinkLiveTest`, `GuestRemainderLiveTest`, `LiveQueuedTest` and `ScopeRelockLiveTest` failed alone with
 * `AppKinds read before the applications were installed`, green only after another class of the suite had installed them.
 * A live case is a test source naming `VEYQ_LIVE`, `LiveRun` or `Throwaway`; its install is `AppComposition.install()`
 * (`:composition`, every application) or `TestApplications.install(<its part>)` (its own module).
 *
 * Its bite: the install taken out of any one live case names that file below.
 */
class LiveCasesRunAloneTest {
    @Test
    fun everyApplicationsLiveCaseInstallsItsApplications() {
        val mobile = File(System.getProperty("veyq.repoRoot"), "src/mobile")
        val roots = listOf(File(mobile, "composition/src/jvmTest")) + File(mobile, "apps").listFiles().orEmpty().map { File(it, "src/jvmTest") }
        val live = Regex("""\bVEYQ_LIVE\b|\bLiveRun\b|\bThrowaway\b""")
        val install = Regex("""\bAppComposition\.install\(\)|\bTestApplications\.install\(""")
        val cases = roots.filter { it.isDirectory }.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" && live.containsMatchIn(it.readText()) } }
        assertTrue(cases.size > 20, "no live case found under $mobile: the scan reads nothing")
        val offenders = cases.filterNot { install.containsMatchIn(it.readText()) }.map { it.relativeTo(mobile).path }.sorted()
        assertEquals(emptyList(), offenders, "a live case that does not install its applications fails alone: install them in it")
    }
}
