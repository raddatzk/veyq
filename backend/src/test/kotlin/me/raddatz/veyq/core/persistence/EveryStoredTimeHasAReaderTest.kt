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
 * acceptance time back on 2026-10-09 as that evidence); #398 turned the share tombstone's time into a flag and dropped
 * the medium's creation time, which was only `updated_at`'s fallback. The trap is the default
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
                "ADR 0074 Decision 1's record of when control of the address was shown — kept by decision (#398)",
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
            "veyq_email_change.expires_at" to "EmailChangeStore spends only a live change; the confirmation purge deletes on it",
            "veyq_account_erasure.requested_at" to "AccountErasureMail retries a mail owed for seven days from it; served to the Server Owner",
            "veyq_account_erasure.due_at" to "AccountPurge erases the account once it is due; served to the Server Owner and the member",
            "veyq_account_erasure.mailed_at" to "AccountErasureMail mails only while it is unset, and retries those",
            // --- tenants + scopes
            "veyq_tenant.deletion_due_at" to "TenantPurge erases a tenant pending deletion once it is due; served to the Server Owner",
            "veyq_tenant_invite.expires_at" to "TenantStore/TenantAcceptHandler refuse and expire; served to the admin",
            "veyq_tenant_invite.created_at" to "TenantStore.currentInvite: the newest invite of a tenant",
            "veyq_scope.deleted_at" to "ScopePurge's grace cutoff; served as deletedAt/purgeAfter of a pending group",
            "veyq_membership.created_at" to "ScopesStore.oldestOtherMember: the orphan rule's successor (ADR 0014 §6)",
            "veyq_tenant_terms_acceptance.accepted_at" to "audit evidence of acceptance, one row per revision (when " +
                "which revision was accepted); read by operators/support from the database, deliberately not served " +
                "by an API (owner decisions 2026-10-09, history 2026-10-10 #402)",
            // --- content + blobs
            "veyq_node.created_at" to "served: NodeSummary.createdAt, the order equal-named siblings take (ADR 0015)",
            "veyq_node.deleted_at" to "NodeSubtreePurge's cutoff; the trash list's order",
            "veyq_upload_session.last_window_at" to "BlobUploadStore purges a session with no window inside the retention",
            "veyq_store_setting.checked_at" to "InstanceResource serves it as the last check's checkedAt",
            "veyq_store_reclaim.created_at" to "StoreReclaimSweep takes the oldest due rows first",
            "veyq_store_reclaim.next_attempt_at" to "StoreReclaimSweep's not-before",
            "veyq_store_reclaim.abandoned_at" to "StoreReclaimSweep skips an abandoned row",
            // --- the bucket reconcile (ADR 0062 § Consequences) — operator state, no member's activity
            "veyq_store_reconcile.pass_started_at" to "its presence is the pass in progress; served: passInProgress.startedAt",
            "veyq_store_reconcile.last_listed_at" to "StoreReconcileStore.writePage's gap rule (the store's clock)",
            "veyq_store_reconcile.completed_started_at" to "StoreReconcileStore.open: the next pass waits a period after it; served",
            "veyq_store_reconcile.completed_at" to "served: StoreReconcilePass.completedAt",
            "veyq_store_reconcile.failed_at" to "served: StoreReconcileFailure.at",
            "veyq_store_reconcile.exclusive_confirmed_at" to "its presence arms the deletion (StoreReconcileEntity.armed); served",
            "veyq_store_reconcile.paused_at" to "its presence disarms the deletion (StoreReconcileEntity.armed); served",
            "veyq_store_reconcile.clocks_restarted_at" to "served: StoreReconcile.clocksRestartedAt",
            "veyq_store_orphan.first_seen_at" to "StoreReconcileStore.complete: a key is due once the grace has passed since it",
            // --- instance: the release check and instance mail (ADR 0097) — operator state, no member's activity
            "veyq_release_check.last_success_at" to "served: InstanceReleaseNotices.lastSuccessAt (uc.admin.learn-of-new-releases A2)",
            "veyq_release_notice.found_at" to "served: InstanceReleaseNotice.foundAt",
            "veyq_release_notice.mailed_at" to "served: InstanceReleaseNotice.mailedAt; a notice with one is never mailed again",
            "veyq_instance_mail_settings.code_expires_at" to "InstanceMailConfirmation refuses an expired code",
            "veyq_instance_mail_settings.confirmed_at" to "served: InstanceMailSettings.confirmedAt; its presence is the confirmation",
            // --- the operator details (ADR 0093 § 11): the notification address's code confirmation, ADR 0097 decision 5
            "veyq_operator_details.code_expires_at" to "CodeConfirmation.codeLive refuses an expired code",
            "veyq_operator_details.confirmed_at" to "served: InstanceOperator.notificationConfirmedAt; its presence is the confirmation",
            // --- moderation (ADR 0099, ADR 0093 § 11)
            "veyq_moderator.appointed_at" to "served: InstanceModerator.appointedAt, ModeratorAppointment.appointedAt; orders the roster",
            "veyq_moderator.accepted_at" to "its presence is the MODERATOR role (ModeratorEntity.accepted); served: acceptedAt",
            "veyq_moderator.removed_at" to "its presence ends the role and the roster entry (ModeratorEntity.onRoster)",
            "veyq_moderator.code_expires_at" to "ModeratorAddress.codeLive refuses an expired code",
            "veyq_moderator.confirmed_at" to "its presence is the address confirmation (ModeratorAddress.confirmed)",
            "veyq_moderation_epoch.rotation_due_at" to "served: InstanceModeration.rotationDueSince; RotationDueNotice mails it",
            "veyq_moderation_epoch.rotation_due_mailed_at" to "RotationDueNotice's claim: mailed once, released on a failed send",
            "veyq_moderation_challenge.expires_at" to "ModerationProofGate refuses an expired challenge; ModerationPurge deletes it",
        // --- reports and holds (ADR 0093 §§ 1-2, 4; uc.moderation.report, en.moderation.hold)
        "veyq_moderation_report.received_at" to "ReportHandler ends the case-open hold a report takes 90 days after it",
        "veyq_hold.expires_at" to "Holds.aliveFor decides whether a report takes a new hold; ModerationPurge deletes a hold past it",
        "veyq_hold_pin.expires_at" to "StoreReclaimStore.due leaves a reclaim row of a pinned key out until then",
        "veyq_moderation_case.noticed_at" to "CaseNotices.mailBundle counts a day from the last bundle's",
            "veyq_moderation_event.occurred_at" to "ModerationLog.closeFor dates an event from the later of it and the removal",
            "veyq_moderation_event.expires_at" to "ModerationPurge deletes the event past it (ModerationLog.purgeExpired)",
            // --- review and measures (ADR 0093 §§ 4-6, 11; uc.moderation.review-a-case, uc.moderation.take-a-measure)
            "veyq_moderation_event.hold_until" to "served: ModerationCaseEvent.until, an extension's new end",
            "veyq_moderation_case.decided_at" to "served: ModerationCase.decidedAt; a measure's hold counts from it where nobody can be told",
            "veyq_moderation_case.closed_at" to "ModerationLog.appendCase dates a closed case's later events from it; served: closedAt",
            "veyq_moderation_block.taken_at" to "served: ModerationMeasure.takenAt, AccountSignInBlock.blockedAt; orders a case's measures",
            "veyq_moderation_block.lifted_at" to "served: ModerationMeasure.liftedAt — the record that a measure was lifted (active_key says whether)",
            "veyq_moderation_proposal.proposed_at" to "served: ModerationProposal.proposedAt, OpenModerationProposal.proposedAt; orders both lists",
            "veyq_moderation_proposal.settled_at" to "served: ModerationProposal.settledAt",
            // --- the statement of reasons and the objection (ADR 0093 §§ 4, 7; uc.moderation.object-to-a-measure)
            "veyq_moderation_statement.sent_at" to "CaseDecisions.decide ends the measure's hold six months after it; StatementMail.retryDue retries a week after it",
            "veyq_moderation_statement_recipient.mailed_at" to "StatementMail's claim: a member is mailed once, retried while it is null",
            "veyq_moderation_objection.received_at" to "served: ModerationObjection.receivedAt, StatementObjection.receivedAt; orders a case's objections",
            "veyq_moderation_objection.decided_at" to "its presence closes the objection (ModerationObjectionEntity.open); served: decidedAt",
            // --- an authority's order and the deferral (ADR 0093 §§ 4, 7, 12; uc.moderation.authority-order)
            "veyq_authority_order.received_at" to "served: AuthorityOrder.receivedAt, the hour's proof; a preservation " +
                "order's or an information request's hold counts from it (AuthorityOrderHandler)",
            "veyq_authority_order.entered_at" to "served: AuthorityOrder.enteredAt; orders listModerationOrders; dates an " +
                "order's case in the queue (CaseViews.queue)",
            "veyq_authority_order.blocked_at" to "served: AuthorityOrder.blockedAt — the Annex II text's block time",
            "veyq_authority_order.hold_until" to "served: AuthorityOrder.holdUntil, the end the order sets for its hold",
            "veyq_hold.floor_until" to "Holds.moveEnd and Holds.endNow never end a hold before it; served: ModerationHold.floorUntil",
            "veyq_hold.ceiling_until" to "Holds.moveEnd moves a measure's end at most to it and Holds.capAt brings a hold " +
                "back to it (a child-sexual-abuse case's 90 days, ADR 0093 § 4)",
            "veyq_moderation_statement.deferred_until" to "StatementDeferrals.releaseDue sends the statement once it passed; " +
                "served: ModerationMeasureStatement.deferredUntil, ModerationCaseSummary.statementDeferredUntil",
            // --- the guest-write record (ADR 0093 § 10, en.moderation.guest-write-record)
            "veyq_guest_write_record.observed_at" to "GuestWriteRecordPurge deletes a row older than N as it stands now (GuestWriteRecords.purge)",
            "veyq_guest_write_record.expires_at" to "GuestWriteRecordPurge deletes a row past it, at startup and on its interval",
            // --- tasks
            "veyq_job.created_at" to "TasksStore claims the oldest job first and picks the newest covering rotation",
            "veyq_job.lease_expires_at" to "TasksStore re-offers a job whose lease ran out",
            // --- sharing
            "veyq_share.created_at" to "served: ShareSummary.createdAt on the manage list",
            "veyq_share.expires_at" to "ShareGuestGate refuses an expired link; served on the manage list",
            // --- Memories
            "veyq_bucket.created_at" to "MediaStore lists a node's buckets oldest first",
            "veyq_media.updated_at" to "the gallery's keyset cursor (cr-0107); served as MediaView.updatedAt — set " +
                "at attach, so it is also the medium's only creation time (#398 dropped created_at)",
            "veyq_media.deleted_at" to "the media purge's cutoff; the trash list's order",
        )
    }
}
