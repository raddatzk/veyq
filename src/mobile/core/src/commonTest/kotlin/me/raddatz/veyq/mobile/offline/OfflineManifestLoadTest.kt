package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * ADR 0140 decision 2's bound: the whole manifest is read into memory when a store opens, so its load at 100,000
 * entries must stay within a second on the slowest target (the Windows VM) — over that, a database replaces it. The
 * time is printed, never asserted (a timing is a measurement, not a check); the count is.
 */
class OfflineManifestLoadTest {
    @Test
    fun the_manifest_of_100000_entries_opens_whole() = runTest {
        val p = testPrimitives
        val root = freshTestDirectory()
        try {
            val stores = OfflineStores(root, MemoryDeviceStore(), p)
            val masterKey = p.randomBytes(32)
            val account = "veyq.example.org#" + uuid()
            val server = "https://veyq.example.org"
            val tenant = uuid()
            val scopes = List(40) { uuid() }
            val entries = List(N) { i ->
                val listing = i % 10 == 0
                KeptEntry(
                    EntryKey(server, tenant, scopes[i % scopes.size], if (listing) "listNodeMedia" else "blob", uuid()),
                    blob = !listing,
                    size = 262_172L * (1 + i % 7),
                    lastView = 1_763_000_000_000L + i,
                    pins = if (i % 50 == 0) setOf(uuid()) else emptySet(),
                    revision = if (listing) "\"" + hex(p.randomBytes(8)) + "\"" else null,
                    asOf = if (listing) 1_763_000_000_000L else null,
                )
            }
            stores.open(account, masterKey).apply { putManifest(entries) }.close()
            val runs = List(RUNS) {
                val mark = TimeSource.Monotonic.markNow()
                val store = stores.open(account, masterKey)
                val took = mark.elapsedNow()
                assertEquals(N, store.entries().size, "the manifest did not open whole")
                store.close()
                took.inWholeMilliseconds
            }
            val disk = everyFile(root).values.sumOf { it.size.toLong() }
            println("veyq-measure: offline manifest load, $N entries, ${disk / 1024} KiB in 256 shards: ${runs.joinToString(" ")} ms (median ${runs.sorted()[RUNS / 2]} ms)")
        } finally {
            removeAll(root)
        }
    }

    private fun uuid(): String = hex(testPrimitives.randomBytes(16)).let {
        "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}"
    }

    private companion object {
        const val N = 100_000
        const val RUNS = 5
    }
}
