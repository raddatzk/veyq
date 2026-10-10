package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ScopeAccessWorld]'s core half builds with **core alone** (inbox #335): `:core`'s tests boot no application, so no
 * [WorldContent] bean is found, and the world still seeds every gate state with core's own content — an upload
 * session and a job leased to each space's owner. Before the split the world wrote a `MEMORY` node, a medium and a
 * share into every space, which core alone refuses, so it ran only where Memories was part of the application.
 */
@QuarkusTest
class ScopeAccessWorldCoreSliceTest {

    @Test
    fun theCoreWorldBuildsWithNoApplicationAndHoldsEveryGateState() {
        assertTrue(ScopeAccessWorld.discovered().isEmpty(), "core's tests boot no application, so no WorldContent bean")
        val w = ScopeAccessWorld().build()
        QuarkusTransaction.requiringNew().run {
            for (s in listOf(w.live, w.control, w.deleting, w.suspended)) {
                assertTrue(s.content.isEmpty(), "no application contributed content")
                assertEquals(s.scopeId, UploadSessionEntity.findById(s.uploadId)!!.scopeId, "the spare upload session is in its space")
                assertEquals(s.ownerId, JobEntity.findById(s.jobId)!!.leaseHolder, "the job is leased to the space's owner")
            }
            assertEquals(TenantEntity.SUSPENDED, TenantEntity.findById(w.suspended.tenantId)!!.status)
            assertNotNull(ScopeEntity.findById(w.deleting.scopeId)!!.deletedAt, "the deleting space is in its grace")
            assertEquals(null, ScopeEntity.findById(w.live.scopeId)!!.deletedAt)
        }
        val missing = assertThrows(IllegalStateException::class.java) { w.live["someApplication.id"] }
        assertTrue("no WorldContent seeded 'someApplication.id'" in missing.message!!, missing.message)
    }
}
