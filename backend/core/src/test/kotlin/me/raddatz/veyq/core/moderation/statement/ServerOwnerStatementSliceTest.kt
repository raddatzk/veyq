package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsHandler
import me.raddatz.veyq.core.admin.instance.InstanceMailUpdateOutcome
import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorDetailsStore
import me.raddatz.veyq.core.admin.tenant.TenantPurge
import me.raddatz.veyq.core.account.erasure.AccountPurge
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.ModerationRecordsPurge
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.propose
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The Server Owner's own steps over content carry a statement of reasons (ADR 0093 § 7; DSA Art. 17(1)(c), (d);
 * uc.moderation.object-to-a-measure A7), over GreenMail on this class's own port: a sign-in block that takes a proposal
 * cannot go without one while a block with no case, no proposal and no "over content" goes as before; the blocked member
 * is mailed the statement **with** the facts, which the database holds only sealed; a tenant taken offline over content
 * tells its owners and nobody else, and its reactivation lifts the record; a case-less step's records go with it.
 */
@QuarkusTest
class ServerOwnerStatementSliceTest {

    @Inject
    lateinit var settings: InstanceMailSettingsHandler

    @Inject
    lateinit var operator: OperatorDetailsStore

    @Inject
    lateinit var purge: ModerationRecordsPurge

    @Inject
    lateinit var statements: Statements

    @Inject
    lateinit var statementMail: StatementMail

    @Inject
    lateinit var tenantPurge: TenantPurge

    @Inject
    lateinit var accountPurge: AccountPurge

    private lateinit var mailbox: GreenMail
    private lateinit var mod: CaseTestKit.Moderator
    private var contactBefore: String? = null

    @BeforeEach
    fun seed() {
        mod = CaseTestKit.moderator()
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
        mailbox = GreenMail(ServerSetup(PORT, "127.0.0.1", "smtp")).also { it.start() }
        val saved = settings.update(mod.id, "127.0.0.1", PORT, "veyq@example.com", null, null, tls = false)
        assertTrue(saved is InstanceMailUpdateOutcome.Ok, "$saved")
        mailbox.waitForIncomingEmail(1_000, 1)
        mailbox.purgeEmailFromAllMailboxes()
        contactBefore = QuarkusTransaction.requiringNew().call { OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.contactEmail }
        QuarkusTransaction.requiringNew().run { operator.lockedRow().contactEmail = CONTACT }
    }

    @AfterEach
    fun stop() {
        mailbox.stop()
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity.deleteAll()
            operator.lockedRow().contactEmail = contactBefore
        }
    }

    private fun statement(id: String = UUID.randomUUID().toString(), facts: String? = FACTS, provision: String? = "Space terms, clause 4") =
        """{"statementId":"$id","ground":"TERMS_BREACH"""" + (provision?.let { ""","provision":"$it"""" } ?: "") +
            (facts?.let { ""","facts":"$it","factsForLog":{"epoch":1,"sealedFacts":"$SEALED_STEP_FACTS"}""" } ?: "") + "}"

    private fun block(address: String, extra: String = "") =
        ModerationTestKit.asOwner(mod.id).body("""{"address":"$address"$extra}""").post("/api/instance/moderation/sign-in-blocks")

    private fun emailOf(userId: String) = QuarkusTransaction.requiringNew().call { me.raddatz.veyq.core.identity.VeyqUser.findById(userId)!!.email }

    /** A member's folder with a cover they uploaded, reported: the case a moderator proposes the Server Owner's steps on. */
    private fun reported(uploader: String): Pair<String, String> {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        seedAttachment(m.scopeId, root, NodeCover.SLOT, uploaderUserId = uploader)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", root)))).then().statusCode(201)
        return openCase("NODE", root)!!.id to m.tenantId
    }

    @Test
    fun aSignInBlockFromAProposalCarriesAStatementAndOneWithNoCaseNoProposalAndNoFlagGoesWithout() {
        val proposed = seedUser()
        val (caseId, _) = reported(proposed)
        mod.propose(caseId, "MEMBER_SIGN_IN", targetId = proposed).then().statusCode(201)
        // From a proposal it is over content: refused without its statement, and nothing was blocked.
        block(emailOf(proposed)).then().statusCode(400).body("code", equalTo("statement_required"))
        assertEquals(0L, blocksOn(proposed))
        // A block the Server Owner marks as over content needs one too.
        val marked = seedUser()
        block(emailOf(marked), ""","overContent":true""").then().statusCode(400).body("code", equalTo("statement_required"))
        // The organisation's full form asks for the facts and the provision.
        block(emailOf(proposed), ""","statement":${statement(facts = null)}""").then().statusCode(400)
            .body("code", equalTo("statement_facts_required"))
        block(emailOf(proposed), ""","statement":${statement(provision = null)}""").then().statusCode(400)
            .body("code", equalTo("statement_provision_required"))
        // Control: a block with no case, no proposal and no flag is taken without a statement, as before.
        val plain = seedUser()
        block(emailOf(plain)).then().statusCode(200)
        assertEquals(1L, blocksOn(plain))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { StatementRecipientEntity.count("recipientId", plain) })
        // With its statement the proposal's block is taken, recorded on the proposal's case, and settles it.
        block(emailOf(proposed), ""","statement":${statement()}""").then().statusCode(200)
        val row = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.find("targetId = ?1 and kind = ?2", proposed, BlockKind.MEMBER_SIGN_IN.name).firstResult()!! }
        assertEquals(caseId, row.caseId)
        val written = QuarkusTransaction.requiringNew().call { ModerationStatementEntity.find("measureId", row.id).firstResult()!! }
        assertEquals(caseId, written.caseId)
        assertEquals("REPORT", written.basis)
        assertEquals("Space terms, clause 4", written.provision)
        // A Server Owner's statement is a mail's: it is not listed in the app (the member cannot sign in to read it).
        assertTrue(statements.mine(proposed).isEmpty())
    }

    @Test
    fun theBlockedMemberIsMailedTheStatementWithTheFactsWhichTheDatabaseHoldsOnlySealed() {
        val member = seedUser()
        val statementId = UUID.randomUUID().toString()
        block(emailOf(member), ""","overContent":true,"statement":${statement(statementId)}""").then().statusCode(200)
        val sent = mailsTo(emailOf(member)).single()
        val text = textOf(sent)
        assertTrue(text.contains("your account was blocked from signing in"), text)
        assertTrue(text.contains("You are told as the holder of the account."), text)
        assertTrue(text.contains("What they found: $FACTS"), text)
        assertTrue(text.contains("The measure was taken after their own finding."), text)
        assertTrue(text.contains("It relies on Space terms, clause 4."), text)
        assertTrue(text.contains("It applies wherever this veyq instance is reached"), text)
        assertTrue(text.contains("If you disagree, write to $CONTACT"), text)
        assertFalse(text.contains("you can object once, in veyq"), "a blocked member cannot object in veyq:\n$text")
        assertTrue(text.contains("Was sie festgestellt haben: $FACTS"), text)
        // The facts are stored only sealed: the statement row holds the sealed copy, no event and no row carries the text.
        QuarkusTransaction.requiringNew().run {
            val row = ModerationStatementEntity.findById(statementId)!!
            assertEquals(SEALED_STEP_FACTS, row.sealedFacts)
            assertEquals(1, row.epoch)
            assertNull(row.caseId)
            assertEquals("OWN_FINDING", row.basis)
            val stored = ModerationEventEntity.listAll().map { listOf(it.reason, it.targetId, it.sealedText).joinToString() } +
                listOf(row.sealedFacts, row.provision, row.ground).joinToString() +
                StatementRecipientEntity.list("statementId", statementId).map { listOf(it.roles, it.sealedFacts).joinToString() }
            assertTrue(stored.none { it.contains(FACTS) }, "the facts are stored in the clear")
            assertTrue(ModerationEventEntity.list("caseId is null and event = ?1 and targetId = ?2", "STATEMENT_SENT", statementId).isNotEmpty())
            assertNotNull(StatementRecipientEntity.find("statementId", statementId).firstResult()!!.mailedAt)
        }
        // Mailed once: the cleanup's retry leaves a Server Owner's statement alone (it holds no clear facts to resend).
        QuarkusTransaction.requiringNew().run { StatementRecipientEntity.update("mailedAt = null where statementId = ?1", statementId) }
        mailbox.purgeEmailFromAllMailboxes()
        statementMail.retryDue()
        mailbox.waitForIncomingEmail(500, 1)
        assertEquals(0, mailbox.receivedMessages.size)
    }

    @Test
    fun aTenantTakenOfflineOverContentTellsItsOwnersOnlyAndReactivatingLiftsTheRecord() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val owner = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(owner, m.tenantId, MembershipEntity.OWNER) }
        val domain = QuarkusTransaction.requiringNew().call { TenantEntity.findById(m.tenantId)!!.domain }
        // Over content without a statement: refused, and the tenant stays active.
        ModerationTestKit.asOwner(mod.id).body("""{"overContent":true}""").post("/api/tenants/${m.tenantId}/suspend")
            .then().statusCode(400).body("code", equalTo("statement_required"))
        assertEquals(TenantEntity.ACTIVE, QuarkusTransaction.requiringNew().call { TenantEntity.findById(m.tenantId)!!.status })
        ModerationTestKit.asOwner(mod.id).body("""{"overContent":true,"statement":${statement()}}""").post("/api/tenants/${m.tenantId}/suspend")
            .then().statusCode(204)
        // The owner is mailed with the facts and the tenant's domain; the member (no owner) is told nothing (owner Q25).
        val text = textOf(mailsTo(emailOf(owner)).single())
        assertTrue(text.contains("a space you own, $domain, was taken offline"), text)
        assertTrue(text.contains("You are told as an owner of the space."), text)
        assertTrue(text.contains("What they found: $FACTS"), text)
        assertTrue(mailsTo(emailOf(m.userId), wait = false).isEmpty(), "a member who owns nothing is told")
        val record = QuarkusTransaction.requiringNew().call {
            ModerationBlockEntity.find("kind = ?1 and targetId = ?2", BlockKind.TENANT_OFFLINE.name, m.tenantId).firstResult()!!
        }
        assertNotNull(record.activeKey)
        // Reactivating lifts the record; a later suspension for another reason (no body) carries none.
        ModerationTestKit.asOwner(mod.id).post("/api/tenants/${m.tenantId}/reactivate").then().statusCode(204)
        QuarkusTransaction.requiringNew().run { assertNull(ModerationBlockEntity.findById(record.id)!!.activeKey) }
        mailbox.purgeEmailFromAllMailboxes()
        ModerationTestKit.asOwner(mod.id).post("/api/tenants/${m.tenantId}/suspend").then().statusCode(204)
        assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("kind = ?1 and targetId = ?2", BlockKind.TENANT_OFFLINE.name, m.tenantId) })
        ModerationTestKit.asOwner(mod.id).post("/api/tenants/${m.tenantId}/reactivate").then().statusCode(204)
    }

    /**
     * A tenant deleted over content (ADR 0101 decision 10, DSA Art. 17(1)(c)) carries its statement as a suspension does:
     * refused without one, the tenant untouched; with one, a `TENANT_DELETED` measure, its owners mailed the deletion with
     * the facts and the member nothing; a stop lifts the record.
     */
    @Test
    fun aTenantDeletedOverContentTellsItsOwnersOnlyAndAStopLiftsTheRecord() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val owner = seedUser()
        QuarkusTransaction.requiringNew().run { TenantRoots.join(owner, m.tenantId, MembershipEntity.OWNER) }
        val domain = QuarkusTransaction.requiringNew().call { TenantEntity.findById(m.tenantId)!!.domain }
        ModerationTestKit.asOwner(mod.id).body("""{"domain":"$domain","overContent":true}""").post("/api/tenants/${m.tenantId}/deletion")
            .then().statusCode(400).body("code", equalTo("statement_required"))
        assertEquals(TenantEntity.ACTIVE, QuarkusTransaction.requiringNew().call { TenantEntity.findById(m.tenantId)!!.status })
        ModerationTestKit.asOwner(mod.id).body("""{"domain":"$domain","overContent":true,"statement":${statement()}}""")
            .post("/api/tenants/${m.tenantId}/deletion").then().statusCode(200)
        assertEquals(TenantEntity.PENDING_DELETION, QuarkusTransaction.requiringNew().call { TenantEntity.findById(m.tenantId)!!.status })
        val text = textOf(mailsTo(emailOf(owner)).single { textOf(it).contains("was deleted") })
        assertTrue(text.contains("a space you own, $domain, was deleted"), text)
        assertTrue(text.contains("What they found: $FACTS"), text)
        assertTrue(mailsTo(emailOf(m.userId), wait = false).none { textOf(it).contains("What they found") }, "a member who owns nothing is told")
        val record = QuarkusTransaction.requiringNew().call {
            ModerationBlockEntity.find("kind = ?1 and targetId = ?2", BlockKind.TENANT_DELETED.name, m.tenantId).firstResult()!!
        }
        assertNotNull(record.activeKey)
        ModerationTestKit.asOwner(mod.id).delete("/api/tenants/${m.tenantId}/deletion").then().statusCode(204)
        QuarkusTransaction.requiringNew().run { assertNull(ModerationBlockEntity.findById(record.id)!!.activeKey) }
        // Control: a deletion for another reason (not over content, no proposal) carries none and records none.
        ModerationTestKit.asOwner(mod.id).body("""{"domain":"$domain"}""").post("/api/tenants/${m.tenantId}/deletion").then().statusCode(200)
        assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("kind = ?1 and targetId = ?2", BlockKind.TENANT_DELETED.name, m.tenantId) })
        ModerationTestKit.asOwner(mod.id).delete("/api/tenants/${m.tenantId}/deletion").then().statusCode(204)
    }

    /**
     * A measure on what is erased ends with it (GDPR Art. 5(1)(e)): the tenant's purge lifts its record over content and
     * an account's purge its sign-in block, so their statements go at their date instead of standing on nothing forever.
     */
    @Test
    fun aMeasureOnAnErasedTenantOrAccountEndsWithItAndItsRecordsGoAtTheirDate() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val domain = QuarkusTransaction.requiringNew().call { TenantEntity.findById(m.tenantId)!!.domain }
        val tenantStatement = UUID.randomUUID().toString()
        ModerationTestKit.asOwner(mod.id).body("""{"domain":"$domain","overContent":true,"statement":${statement(tenantStatement)}}""")
            .post("/api/tenants/${m.tenantId}/deletion").then().statusCode(200)
        val member = seedUser()
        val memberStatement = UUID.randomUUID().toString()
        block(emailOf(member), ""","overContent":true,"statement":${statement(memberStatement)}""").then().statusCode(200)
        ModerationTestKit.asOwner(mod.id).body("""{"address":"${emailOf(member)}"}""").post("/api/instance/accounts/erasures")
            .then().statusCode(200)
        val measures = QuarkusTransaction.requiringNew().call {
            listOf(tenantStatement, memberStatement).map { ModerationStatementEntity.findById(it)!!.measureId }
        }
        // Control: while the two stand, nothing of them goes, however late.
        purge.sweep(Instant.now().plus(Duration.ofDays(5 * 366)))
        QuarkusTransaction.requiringNew().run { measures.forEach { assertNotNull(ModerationBlockEntity.findById(it)!!.activeKey) } }

        tenantPurge.purge(m.tenantId)
        accountPurge.purge(member)
        val lifted = QuarkusTransaction.requiringNew().call {
            measures.map { id -> ModerationBlockEntity.findById(id)!!.also { assertNull(it.activeKey); assertNull(it.liftedBy) }.liftedAt!! }
        }
        purge.sweep(ModerationLog.retentionEnd(lifted.maxOf { it }).plusSeconds(60))
        QuarkusTransaction.requiringNew().run {
            listOf(tenantStatement, memberStatement).forEach { assertNull(ModerationStatementEntity.findById(it)) }
            measures.forEach { assertNull(ModerationBlockEntity.findById(it)) }
        }
    }

    @Test
    fun aCaselessStepsStatementGoesWithTheStepAfterItsLiftAndNeverWhileItStands() {
        val member = seedUser()
        val statementId = UUID.randomUUID().toString()
        block(emailOf(member), ""","overContent":true,"statement":${statement(statementId)}""").then().statusCode(200)
        val blockId = QuarkusTransaction.requiringNew().call { ModerationStatementEntity.findById(statementId)!!.measureId }
        val farFuture = Instant.now().plus(Duration.ofDays(5 * 366))
        // While the block stands nothing goes, however late.
        purge.sweep(farFuture)
        assertNotNull(QuarkusTransaction.requiringNew().call { ModerationStatementEntity.findById(statementId) })
        ModerationTestKit.asOwner(mod.id).delete("/api/instance/moderation/sign-in-blocks/$blockId").then().statusCode(200)
        val lifted = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.findById(blockId)!!.liftedAt!! }
        // Control: before 31 December of the third year after the lift it stays.
        purge.sweep(ModerationLog.retentionEnd(lifted).minusSeconds(60))
        assertNotNull(QuarkusTransaction.requiringNew().call { ModerationStatementEntity.findById(statementId) })
        // After it, the step, its statement and its recipients go together.
        purge.sweep(ModerationLog.retentionEnd(lifted).plusSeconds(60))
        QuarkusTransaction.requiringNew().run {
            assertNull(ModerationStatementEntity.findById(statementId))
            assertEquals(0L, StatementRecipientEntity.count("statementId", statementId))
            assertNull(ModerationBlockEntity.findById(blockId))
        }
    }

    private fun blocksOn(accountId: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("targetId = ?1 and kind = ?2", accountId, BlockKind.MEMBER_SIGN_IN.name) }

    private fun mailsTo(to: String, wait: Boolean = true): List<MimeMessage> {
        if (wait) mailbox.waitForIncomingEmail(2_000, 1)
        return mailbox.receivedMessages.filter { m -> m.allRecipients.any { it.toString().equals(to, ignoreCase = true) } }
    }

    private fun textOf(mail: MimeMessage): String = partsOf(mail).first { it.isMimeType("text/plain") }.content as String

    private fun partsOf(mail: Part): List<Part> = when (val content = mail.content) {
        is MimeMultipart -> (0 until content.count).flatMap { partsOf(content.getBodyPart(it)) }
        else -> listOf(mail)
    }

    private companion object {
        const val PORT = 13975
        const val CONTACT = "abuse@club.example.org"
        const val FACTS = "The account posted the same unlawful image in three spaces after two warnings."
        const val SEALED_STEP_FACTS = "c2VhbGVkLXN0ZXAtZmFjdHM="
    }
}
