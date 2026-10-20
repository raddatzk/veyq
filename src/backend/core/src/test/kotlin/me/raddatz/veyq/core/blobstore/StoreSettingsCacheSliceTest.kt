package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [StoreSettings]' cache across a write (inbox #445). A reader that read the row just before a write committed must
 * not leave its old resolution cached after the write's [StoreSettings.invalidate]: the instance would keep signing
 * against the previous store until the next write or a restart.
 *
 * The interleaving is forced, not hoped for: the store's [StoreSettingStore.find] parks the reader between reading the
 * row and returning it, the write commits and invalidates while it is parked, and only then does the reader finish.
 * The [StoreSettings] here is built by hand around that store, so the wired bean and the JVM-shared database are
 * untouched.
 */
@QuarkusTest
class StoreSettingsCacheSliceTest {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var hint: BundledStoreHint

    @Inject
    lateinit var seal: ConfigSecretSeal

    @Inject
    lateinit var keys: ConfigSecretKeyGuard

    /** The setting "in the database", and a latch pair that parks the next [find] after it has read the row. */
    private class ParkingStore(em: EntityManager, @Volatile var row: StoreSettingRow) : StoreSettingStore(em) {
        val read = CountDownLatch(1)
        val proceed = CountDownLatch(1)

        @Volatile
        var parkNext = false

        override fun find(): StoreSettingRow {
            val seen = row
            if (parkNext) {
                parkNext = false
                read.countDown()
                check(proceed.await(10, TimeUnit.SECONDS)) { "the writer never released the reader" }
            }
            return seen
        }
    }

    private fun s3(endpoint: String) =
        StoreSettingRow(false, StoreType.S3, endpoint, null, "blobs", "us-east-1", "access", null, null, null)

    private fun endpointOf(resolution: StoreResolution): String =
        (resolution as StoreResolution.Ready).connection.endpoint

    @Test
    fun `a read that straddles a write's commit does not put the old resolution back`() {
        val store = ParkingStore(em, s3("http://old.invalid:9000"))
        val settings = StoreSettings(store, hint, seal, keys)
        val reader = Executors.newSingleThreadExecutor()
        try {
            store.parkNext = true
            val straddling = reader.submit<StoreResolution> { settings.current() }
            assertTrue(store.read.await(10, TimeUnit.SECONDS), "the reader never read the row")

            // The write: its row commits, then it invalidates — the order StoreHandler.set keeps.
            store.row = s3("http://new.invalid:9000")
            settings.invalidate()
            store.proceed.countDown()

            // The straddling read may answer the old store: it began before the commit.
            assertEquals("http://old.invalid:9000", endpointOf(straddling.get(10, TimeUnit.SECONDS)))
            // Every read after the write must see the new one.
            assertEquals("http://new.invalid:9000", endpointOf(settings.current()))
        } finally {
            store.proceed.countDown()
            reader.shutdownNow()
        }
    }

    @Test
    fun `a resolution stays cached until a write invalidates it`() {
        val store = ParkingStore(em, s3("http://first.invalid:9000"))
        val settings = StoreSettings(store, hint, seal, keys)

        val first = settings.current()
        store.row = s3("http://second.invalid:9000") // a change no one has invalidated yet
        assertTrue(first === settings.current(), "an unchanged generation must serve the cached object")

        settings.invalidate()
        assertEquals("http://second.invalid:9000", endpointOf(settings.current()))
    }
}
