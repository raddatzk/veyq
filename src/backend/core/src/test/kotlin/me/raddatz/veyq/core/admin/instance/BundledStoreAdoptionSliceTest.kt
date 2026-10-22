package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import me.raddatz.veyq.core.blobstore.StoreSettingRow
import me.raddatz.veyq.core.blobstore.StoreSettingStore
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **The server adopts the bundled store only where nothing is saved** (ADR 0089 decision 3, uc.admin.configure-object-store
 * A8): [StoreHandler.adoptBundled], the transaction the boot worker runs after it provisioned the hint's Garage
 * ([BundledStoreAdoption]; the worker skips `%test`, so it is driven here directly). Every "changes nothing" case is
 * read against its positive control, the same call on an empty instance, which adopts — so a refusal that came from a
 * missing hint, not from the setting the case saved, is not mistaken for the rule.
 *
 * `%test` passes no bundled-store hint; [StoreSettingFixtures.offerBundled] supplies one for the calling method.
 */
@QuarkusTest
class BundledStoreAdoptionSliceTest {

    @Inject
    lateinit var handler: StoreHandler

    @Inject
    lateinit var store: StoreSettingStore

    @BeforeEach
    fun clear() {
        ensureUser(OWNER_ID, "adoption-owner@example.com", "SERVER_OWNER")
        StoreSettingFixtures.clear()
    }

    @AfterEach
    fun forget() = StoreSettingFixtures.clear()

    @Test
    fun withNothingSavedTheBundledStoreIsAdoptedAsPending() {
        StoreSettingFixtures.offerBundled()
        assertEquals(StoreAdoption.ADOPTED, handler.adoptBundled())
        read {
            assertEquals(StoreSettingRow.BUNDLED, store.findPending(), "the bundled reference, pending")
            assertNull(store.find(), "and nothing in use until its check passes")
            assertNull(store.lastPendingCheck(), "and not checked by the adoption itself")
        }
    }

    @Test
    fun aPendingStoreOfTheOwnersOwnIsNeverReplaced() {
        StoreSettingFixtures.offerBundled()
        val own = s3()
        StoreSettingFixtures.setPending(own)
        assertEquals(StoreAdoption.NONE, handler.adoptBundled())
        read { assertEquals(own, store.findPending()) }

        StoreSettingFixtures.clear()
        assertEquals(StoreAdoption.ADOPTED, handler.adoptBundled(), "control: the same call on an empty instance adopts")
    }

    @Test
    fun aStoreInUseIsNeverReplaced() {
        StoreSettingFixtures.offerBundled()
        val own = s3()
        StoreSettingFixtures.set(own)
        assertEquals(StoreAdoption.NONE, handler.adoptBundled())
        read {
            assertEquals(own, store.find())
            assertNull(store.findPending(), "nothing pending beside it")
        }

        StoreSettingFixtures.clear()
        assertEquals(StoreAdoption.ADOPTED, handler.adoptBundled(), "control: the same call on an empty instance adopts")
    }

    @Test
    fun aDeploymentThatOffersNoBundledStoreAdoptsNothing() {
        assertEquals(StoreAdoption.NONE, handler.adoptBundled())
        read { assertNull(store.findPending()) }

        StoreSettingFixtures.offerBundled()
        assertEquals(StoreAdoption.ADOPTED, handler.adoptBundled(), "control: offered, it adopts")
    }

    @Test
    fun aBundledStoreAPreviousBootAdoptedIsCheckedAgainNotSavedAgain() {
        StoreSettingFixtures.offerBundled()
        assertEquals(StoreAdoption.ADOPTED, handler.adoptBundled(), "control: the first boot adopts")
        assertEquals(StoreAdoption.RESUMED, handler.adoptBundled(), "the next boot finds it pending")
        read { assertEquals(StoreSettingRow.BUNDLED, store.findPending()) }
    }

    @Test
    fun theAdoptionsCheckChecksNothingOnceSomethingElseIsPending() {
        StoreSettingFixtures.offerBundled()
        StoreSettingFixtures.setPending(s3())
        assertInstanceOf(StoreCheckRun.NotConfigured::class.java, handler.check(onlyPending = StoreSettingRow.BUNDLED))
        read { assertNull(store.lastPendingCheck(), "the owner's pending store was not checked by the server") }
    }

    /**
     * The Server Owner's save racing the adoption (decision 2): the held side is the adoption's transaction
     * ([StoreHandler.adoptInTransaction]), the overlapping one the owner's `PUT`, whose read saw nothing pending. It waits
     * on the instance singleton's lock, then replaces the adopted store with their own — without the lock it inserted a
     * second pending row and failed on the first's uncommitted key.
     */
    @Test
    fun anOwnersSaveDuringTheAdoptionWaitsForItAndReplacesIt() {
        raceRounds { round ->
            StoreSettingFixtures.clear()
            StoreSettingFixtures.offerBundled()
            val answer = whileUncommitted(
                change = { assertEquals(StoreAdoption.ADOPTED, handler.adoptInTransaction(), "control: the held side adopts") },
                overlapping = {
                    givenAdminChannel().header("Authorization", bearer(OWNER_ID)).contentType(ContentType.JSON)
                        .body("""{"bundled":false,"type":"S3","endpoint":"http://s3.invalid:9000","bucket":"own-$round","region":"eu-central-1","accessKey":"a","secretKey":"s"}""")
                        .`when`().put("/api/instance/store").then().extract()
                },
            )
            assertEquals(200, answer.statusCode(), answer.asString())
            read { assertEquals("own-$round", store.findPending()?.bucket, "the owner's store is the one pending") }
        }
    }

    private fun s3() = StoreSettingRow(false, StoreType.S3, "http://s3.invalid:9000", null, "own", "us-east-1", "access", null, null, null)

    private fun read(block: () -> Unit) = QuarkusTransaction.requiringNew().run(block)

    private companion object {
        /** Distinct from every other class's: the `%test` H2 mem DB is JVM-shared across test classes. */
        const val OWNER_ID = "9a3f5c17-2e84-4b6d-a0c9-6d1e8f2b4a73"
    }
}
