package me.raddatz.veyq.core.persistence

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.persister.entity.AbstractEntityPersister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every time the server stores names what reads it (inbox #318). A server time is exact and tells a database reader
 * *when* a member did something — accepted terms, re-sealed a preference, renamed a device. Data the server neither
 * needs nor serves is not stored, so a time column earns its place by a reader: a sort, a purge cutoff, an expiry
 * check, a served field — or, named as such, a person reading the database (the terms acceptance time: audit
 * evidence). #318 measured nineteen written-never-read times and dropped them (the owner brought the terms
 * acceptance time back on 2026-10-09 as that evidence); the trap is the default
 * (`created_at TIMESTAMP NOT NULL` on every new table), so this reads Hibernate's own mapping of every entity, in any
 * module on the classpath, and holds the set of `Instant` columns to [READERS] both ways: a new time column with no
 * entry is red until someone names its reader, and an entry whose column is gone is red until it is struck.
 *
 * The reasons are claims about the code, checked by review, not by this test: it proves the decision was made, not
 * that the reader still exists. When the reader leaves, the entry and the column leave with it.
 */
@QuarkusTest
class EveryStoredTimeHasAReaderTest {

    @Inject
    lateinit var emf: EntityManagerFactory

    @Test
    fun everyStoredTimeHasANamedReader() {
        val persisters = emf.unwrap(SessionFactoryImplementor::class.java).mappingMetamodel.streamEntityDescriptors().toList()
        // Tells "no time column" apart from "no entity was read": the persistence unit has dozens.
        assertTrue(persisters.size >= 30, "read only ${persisters.size} entities — is this the application's persistence unit?")
        val stored = persisters.map { it as AbstractEntityPersister }.flatMap { p ->
            p.propertyNames.withIndex()
                .filter { (i, _) -> p.propertyTypes[i].returnedClass == Instant::class.java }
                .flatMap { (_, name) -> p.getPropertyColumnNames(name).map { "${p.tableName}.$it" } }
        }.toSortedSet()
        assertEquals(
            READERS.keys.toSortedSet(), stored,
            "a stored time without a READERS entry (add its reader, or do not store it), or an entry whose column is " +
                "gone (strike it) — inbox #318",
        )
    }

    companion object {
        /** `table.column` → what reads it. Grouped by owner; Memories' tables boot with it on the root classpath. */
        val READERS: Map<String, String> = mapOf(
            // --- account + sessions
            "veyq_user.email_confirmed_at" to "EmailConfirmationHandler surfaces 'confirmed' (presence); the time is " +
                "ADR 0074 Decision 1's record of when control was shown — narrowing it to a flag is an ADR change",
            "veyq_login_challenge.expires_at" to "LoginHandler refuses an expired challenge; SessionService purges on it",
            "veyq_session_family.created_at" to "served: SessionFamily.createdAt on the sessions list",
            "veyq_session_family.last_used_at" to "served + sorted: the sessions list",
            "veyq_refresh_token.expires_at" to "SessionService refuses an expired token, scopes the list, purges on it",
            "veyq_access_token.expires_at" to "SessionService refuses an expired token and purges on it",
            "veyq_account_recovery.expires_at" to "RecoveryStore refuses and expires a stale recovery",
            "veyq_account_recovery.created_at" to "RecoveryStore purges terminal rows older than the retention",
            "veyq_device_pairing.expires_at" to "PairingStore refuses and expires a stale pairing",
            "veyq_device_pairing.created_at" to "PairingStore purges terminal rows older than the retention",
            "veyq_email_confirmation.expires_at" to "EmailConfirmationStore refuses and expires a stale link",
            "veyq_email_confirmation.created_at" to "EmailConfirmationStore purges terminal rows older than the retention",
            // --- tenants + scopes
            "veyq_tenant_invite.expires_at" to "TenantStore/TenantAcceptHandler refuse and expire; served to the admin",
            "veyq_tenant_invite.created_at" to "TenantStore.currentInvite: the newest invite of a tenant",
            "veyq_scope.deleted_at" to "ScopePurge's grace cutoff; served as deletedAt/purgeAfter of a pending group",
            "veyq_membership.created_at" to "ScopesStore.oldestOtherMember: the orphan rule's successor (ADR 0014 §6)",
            "veyq_tenant_terms_acceptance.accepted_at" to "audit evidence of acceptance (when, which version); read " +
                "by operators/support from the database, deliberately not served by an API (owner decision 2026-10-09)",
            // --- content + blobs
            "veyq_node.created_at" to "served: NodeSummary.createdAt, the order equal-named siblings take (ADR 0015)",
            "veyq_node.deleted_at" to "NodeSubtreePurge's cutoff; the trash list's order",
            "veyq_upload_session.last_window_at" to "BlobUploadStore purges a session with no window inside the retention",
            "veyq_store_reclaim.created_at" to "StoreReclaimSweep takes the oldest due rows first",
            "veyq_store_reclaim.next_attempt_at" to "StoreReclaimSweep's not-before",
            "veyq_store_reclaim.abandoned_at" to "StoreReclaimSweep skips an abandoned row",
            // --- tasks
            "veyq_job.created_at" to "TasksStore claims the oldest job first and picks the newest covering rotation",
            "veyq_job.lease_expires_at" to "TasksStore re-offers a job whose lease ran out",
            // --- sharing
            "veyq_share.created_at" to "served: ShareSummary.createdAt on the manage list",
            "veyq_share.expires_at" to "ShareGuestGate refuses an expired link; served on the manage list",
            "veyq_share.deleted_at" to "the tombstone: every live read filters on its presence (the time itself is " +
                "unread — a flag would do, inbox #318's follow-up)",
            // --- Memories
            "veyq_bucket.created_at" to "MediaStore lists a node's buckets oldest first",
            "veyq_media.created_at" to "MediaStore's fallback for a null updated_at (the write paths set both)",
            "veyq_media.updated_at" to "the gallery's keyset cursor (cr-0107); served as MediaView.updatedAt",
            "veyq_media.deleted_at" to "the media purge's cutoff; the trash list's order",
        )
    }
}
