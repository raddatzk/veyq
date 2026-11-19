package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The core's reader against the cases the web's reader answers to (`contracts/export/import-cases/cases.v1.json`, ADR 0152
 * decision 4; the web's `importCases.test.ts`): for each, whether an upload offers it as an import ([ImportSource.isDownload],
 * [ImportSource.holdsManifest]), and the refusal at its stage — `open` by [SubtreeImport.open], a payload's checksum by its
 * check ([SubtreeImport.Payloads]). The plan's verdicts and the import's are `SubtreeImportTest`'s.
 */
class ImportCasesTest {
    private val cases = ImportFixtures.cases()

    private suspend fun screened(c: ImportCase): Boolean = when (val input = c.input) {
        is ImportInput.Zip -> ImportSource.isDownload(input.file)
        is ImportInput.Folder -> ImportSource.holdsManifest(input.files.map { it.first })
    }

    @Test
    fun theFileHoldsEveryStageAndBothInputs() {
        assertTrue(cases.size >= 50, "${cases.size} cases")
        assertEquals(setOf("open", "plan", "import"), cases.mapNotNull { it.at }.toSet())
        assertTrue(cases.any { it.input is ImportInput.Folder } && cases.any { it.input is ImportInput.Zip })
    }

    @Test
    fun everyCaseIsOfferedAsTheWebOffersIt() = runTest {
        val wrong = cases.filter { screened(it) != it.screen }.map { it.id }
        assertEquals(emptyList(), wrong, "screened otherwise than the cases say")
    }

    @Test
    fun everyRefusalAtOpenIsTheWebs() = runTest {
        for (c in cases.filter { it.at == "open" }) {
            val e = try {
                SubtreeImport.open(c.input)
                fail("${c.id}: opened, but the cases refuse it with ${c.refused}")
            } catch (e: ImportRefused) {
                e
            }
            assertEquals(c.refused, e.refusal.wire, "${c.id}: ${e.message}")
        }
    }

    @Test
    fun everyOtherCaseOpens() = runTest {
        for (c in cases.filter { it.at != "open" }) {
            try {
                SubtreeImport.open(c.input)
            } catch (e: ImportRefused) {
                fail("${c.id}: refused at open with ${e.refusal.wire} (${e.message})")
            }
        }
    }

    @Test
    fun aPayloadWhoseBytesAreNotItsOwnIsRefusedWhenItIsChecked() = runTest {
        val checksums = cases.filter { it.at == "import" && it.refused == "checksum" }
        assertTrue(checksums.size >= 3, "a SHA-256 in a ZIP, in a folder, and a CRC-32 alone")
        for (c in checksums) {
            val opened = SubtreeImport.open(c.input)
            val payloads = SubtreeImport.Payloads(opened)
            val e = try {
                fun all(n: Manifest.Node): List<Manifest.Payload> = n.payloads + n.children.flatMap(::all)
                all(opened.manifest.root).forEach { payloads.take(it) }
                fail("${c.id}: every payload passed its check")
            } catch (e: ImportRefused) {
                e
            }
            assertEquals("checksum", e.refusal.wire, "${c.id}: ${e.message}")
        }
    }

    @Test
    fun aSoundPayloadIsHandedOnWithTheDigestOfItsOwnBytes() = runTest {
        val sound = cases.first { it.id == "sound" }
        val opened = SubtreeImport.open(sound.input)
        val a = opened.manifest.root.children.first().payloads.single()
        val checked = SubtreeImport.Payloads(opened).take(a)
        val bytes = checked.read(0, a.size.toInt())
        assertEquals("the bytes of a.txt", bytes.decodeToString())
        assertEquals(a.sha256, SubtreeImport.hex(checked.sha256))
        assertEquals(SubtreeImport.hex(Sha256.of(bytes)), SubtreeImport.hex(checked.sha256))
    }
}
