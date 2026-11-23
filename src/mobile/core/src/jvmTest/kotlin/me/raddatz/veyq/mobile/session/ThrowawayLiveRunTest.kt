package me.raddatz.veyq.mobile.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A throwaway's live case takes its devices off the throwaway: every test source that signs in as a throwaway
 * ([Throwaway.accounts]) builds its [LiveRun]s over the throwaway's file (`LiveRun(who, t.file)`), never the bare
 * `LiveRun()`, whose [LiveRun.removeAll] signs in as the seeded principal of `.run/.env` with the throwaway's device
 * stores. Found 2026-11-23 in four cases (`ScopeRelockLiveTest`, `MemoryRekeyLiveTest`, `MemoryExportLiveTest`,
 * `ImportLiveTest`): each run signed the seeded principal in once per device, against the throwaway-only rule (ADR 0148
 * decision 12), left the throwaway's own devices registered, and spent the instance's per-IP sign-in budget twice.
 *
 * Its bite: `LiveRun()` put back into any one of those cases names that file below.
 */
class ThrowawayLiveRunTest {
    @Test
    fun aThrowawaysCaseNeverTakesItsDevicesOffTheSeededPrincipal() {
        val mobile = File(System.getProperty("veyq.repoRoot"), "src/mobile")
        val sources = mobile.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && "Test${File.separator}kotlin" in it.path && it.name != "ThrowawayLiveRunTest.kt" }
            .toList()
        assertTrue(sources.any { "Throwaway.accounts(" in it.readText() }, "no throwaway case found under $mobile: the scan reads nothing")
        val bare = Regex("""\bLiveRun\(\s*\)""")
        val offenders = sources.filter { f -> f.readText().let { "Throwaway.accounts(" in it && bare.containsMatchIn(it) } }
            .map { it.relativeTo(mobile).path }.sorted()
        assertEquals(emptyList(), offenders, "a throwaway's case takes its devices off the seeded principal: use LiveRun(who, t.file)")
    }
}
