package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.export.NodeExport
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The download's cross-surface proof (ADR 0149 decision 7): the core writes [MemoryExportTest.fixture]'s Memory — two
 * photos, a chapter with one, the date, the place, a participant by name, a cover and an image banner — as a download, at a
 * fixed time, into `src/web/src/features/memories/fixtures/app-memory-download.zip`, which the web's `exportImport.test.ts`
 * imports through its own `importSubtree`. This test fails when the committed file differs from what the core writes now:
 * a deliberate change to the archive rewrites it with `VEYQ_WRITE_MEMORY_FIXTURE=1 ./gradlew :core:jvmTest --tests
 * '*MemoryDownloadFixtureTest*' --rerun`, and the web's case then says whether the web still reads it.
 */
class MemoryDownloadFixtureTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    @Test
    fun theCoresDownloadOfAMemoryIsTheCommittedFixtureTheWebImports() = runTest {
        val f = MemoryExportTest.fixture()
        f.t.remember()
        val account = f.t.resume()
        val tenant = account.open(account.tenants.first())
        val walked = assertNotNull(NodeExport.walk(tenant, f.s.id, f.memory.id, AppKinds.exports("deleted")))
        val out = ByteArrayOutputStream()
        NodeExport.write(walked.root, "app-fixture", FIXED_MS) { out.write(it) }
        val file = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/memories/fixtures/app-memory-download.zip")
        if (System.getenv("VEYQ_WRITE_MEMORY_FIXTURE") == "1") {
            file.parentFile.mkdirs()
            file.writeBytes(out.toByteArray())
        }
        assertTrue(file.isFile, "no fixture at $file: write it with VEYQ_WRITE_MEMORY_FIXTURE=1")
        assertTrue(file.readBytes().contentEquals(out.toByteArray()), "the core's download of the fixture Memory changed: rewrite the fixture and run the web's import of it")
    }

    private companion object {
        /** 2025-11-19T12:00:00Z: the archive's entries and its `exportedAt`. */
        const val FIXED_MS = 1_763_553_600_000L
    }
}
