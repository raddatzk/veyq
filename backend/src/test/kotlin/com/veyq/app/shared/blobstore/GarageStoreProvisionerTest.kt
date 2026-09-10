package com.veyq.app.shared.blobstore

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The bring-up's two hard properties (ADR 0067 Decision 3), asserted on the **call sequence** against
 * [FakeGarageAdminApi] — which is the only oracle that can tell "the step ran and was a no-op" apart from "the
 * step was skipped", and the difference between those two is the whole of what this CR had to get right.
 *
 * The fake reproduces the status codes measured on the pinned Garage v2.0.0, so a provisioner written for a
 * store that forgives repeats reddens here exactly as it would against a real node.
 */
class GarageStoreProvisionerTest {

    private lateinit var garage: FakeGarageAdminApi
    private lateinit var cors: FakeBucketCorsPolicy

    @BeforeEach
    fun start() {
        garage = FakeGarageAdminApi()
        garage.start()
        cors = FakeBucketCorsPolicy()
    }

    @AfterEach
    fun stop() {
        assertEquals(emptyList<String>(), garage.unknownPaths, "the provisioner called an Admin API path nobody measured")
        garage.stop()
    }

    private fun provisioner(): GarageStoreProvisioner = GarageStoreProvisioner(
        adminEndpoint = garage.endpoint,
        adminToken = "test-token",
        bucket = BUCKET,
        accessKey = ACCESS_KEY,
        secretKey = "secret",
        nodeZone = "local",
        nodeCapacity = "1G",
        cors = cors,
        corsOrigins = listOf("*"),
    )

    @Test
    fun aFreshNodeGetsEveryStep() {
        provisioner().provision()

        assertTrue(garage.hasRole, "the node must end up carrying a layout role")
        assertEquals(1, garage.layoutVersion, "a fresh node's first applied layout is version 1")
        assertEquals(mapOf(BUCKET to "bucket-id-$BUCKET"), garage.buckets)
        assertEquals(setOf(ACCESS_KEY), garage.keys)
        assertEquals(listOf("bucket-id-$BUCKET/$ACCESS_KEY"), garage.grants)
        assertEquals(listOf(BUCKET to listOf("*")), cors.writes, "ADR 0013 §4: the one policy that works here is `*`")
    }

    @Test
    fun aSecondRunWritesNothing() {
        provisioner().provision()
        garage.forgetCalls()
        cors.writes.clear()

        provisioner().provision()

        assertEquals(emptyList<String>(), garage.calls.filter { it in WRITE_PATHS - "/v2/AllowBucketKey" })
        assertEquals(emptyList<Pair<String, List<String>>>(), cors.writes, "a provisioned store needs no new policy")
        assertEquals(1, garage.buckets.size)
        assertEquals(1, garage.layoutVersion, "a second run must not bump the layout")
    }

    /**
     * **The sharper half, and the one this CR exists to keep honest.** `garage-init.sh` returned early the
     * moment `bucket info` succeeded. Copy that habit and this case goes red: the bucket, key, grant and layout
     * are all in place, only the CORS policy is gone — the exact shape of a store provisioned before the CORS
     * step existed (ADR 0067 Decision 3's own example) — and a per-bootstrap short-circuit never reaches it.
     */
    @Test
    fun aMissingCorsPolicyIsRestoredOnAStoreThatIsOtherwiseProvisioned() {
        provisioner().provision()
        cors.forget(BUCKET)
        cors.writes.clear()

        provisioner().provision()

        assertEquals(listOf(BUCKET to listOf("*")), cors.writes, "the step must repair its own precondition, alone")
        assertEquals(1, garage.buckets.size, "and must not have tried to recreate anything else")
    }

    /**
     * **The layout step generalises past `--version 1`.** `garage-init.sh` hardcoded that version, so it only
     * ever worked on a node that had never been laid out; here a node already at version 7 is simply past this
     * step. The fake refuses a stale `ApplyClusterLayout` version exactly as the real v2.0.0 does, so a
     * provisioner that still hardcoded `1` would go red on the call rather than silently.
     */
    @Test
    fun aNodeWhoseLayoutIsAlreadyAppliedAtAnyVersionIsPastTheLayoutStep() {
        garage.layoutVersion = 7
        garage.hasRole = true

        provisioner().provision()

        assertEquals(7, garage.layoutVersion, "an applied layout is not re-applied")
        assertTrue("/v2/UpdateClusterLayout" !in garage.calls, "and no role is staged over it")
    }

    /**
     * A node that has a layout version but no role — the state a `docker compose down -v` leaves behind on a
     * meta volume that survived, and the case where `current + 1` is the only version that is accepted.
     */
    @Test
    fun aRoleIsAppliedOverAnExistingLayoutVersion() {
        garage.layoutVersion = 4
        garage.hasRole = false

        provisioner().provision()

        assertEquals(5, garage.layoutVersion, "the new layout is current + 1, never a hardcoded 1")
        assertTrue(garage.hasRole)
    }

    @Test
    fun aRefusingAdminApiThrowsSoTheRunnerCanBoundIt() {
        garage.stop()
        val e = assertThrows(Exception::class.java) { provisioner().provision() }
        // The runner treats any throw as "try again"; what must NOT happen is a silent success.
        assertTrue(e !is AssertionError)
        garage = FakeGarageAdminApi().also { it.start() } // so @AfterEach's stop() has something to stop
    }

    private companion object {
        const val BUCKET = "veyq"
        const val ACCESS_KEY = "GK0123456789abcdef01234567"
        val WRITE_PATHS = setOf("/v2/UpdateClusterLayout", "/v2/ApplyClusterLayout", "/v2/CreateBucket", "/v2/ImportKey", "/v2/AllowBucketKey")
    }
}
