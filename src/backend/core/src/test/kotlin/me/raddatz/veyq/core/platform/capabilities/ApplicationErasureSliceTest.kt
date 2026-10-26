package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsStore
import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.admin.instance.ReleaseNoticeStore
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.mail.SmtpDelivery
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The erasure row of an application left switched off and the two mails (uc.platform.erase-switched-off-application,
 * the row of one tenant (e), two levels (f), the mails (g) and the API (h), each a section below), with core alone: no application kind exists here, so this
 * class proves what the switches, the tick and the API do with the row, and the root's `ApplicationErasureSweepTest`
 * what the erasure does to the content. The clock is driven through each writer's `now`.
 *
 * Every negative has a control on the same predicate: a row that must go has a sibling that must stay, a mail that
 * must not go has one that must.
 */
@QuarkusTest
class ApplicationErasureSliceTest {

    @Inject lateinit var switches: CapabilitySwitchStore
    @Inject lateinit var tenantSwitches: TenantCapabilitySwitchStore
    @Inject lateinit var store: ApplicationErasureStore
    @Inject lateinit var purge: ApplicationErasurePurge
    @Inject lateinit var tenantStore: TenantStore
    @Inject lateinit var settings: InstanceMailSettingsStore
    @Inject lateinit var notices: ReleaseNoticeStore
    @Inject lateinit var wire: SmtpDelivery

    private val t0: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private val window: Duration get() = store.window

    @AfterEach
    fun switchEverythingBackOn() {
        TenantCapabilitySwitchFixtures.clear()
        CapabilitySwitchFixtures.clear()
        QuarkusTransaction.requiringNew().run { ApplicationErasureEntity.deleteAll() }
    }

    // --- (e) the row of one tenant ------------------------------------------------------------------------------------

    @Test
    fun aTenantSwitchOffNamesTheDayAndASwitchOnBeforeItCancelsIt() {
        val a = tenant()
        val b = tenant()
        tenantSwitch(a.id, DEPOT, true, t0)
        val row = row(a.id, DEPOT)!!
        assertEquals(t0, row.offSince)
        assertEquals(t0.plus(window), row.dueAt)
        assertEquals(SwitchLevel.TENANT.name, row.switchedBy)
        assertNull(row(b.id, DEPOT), "control: another tenant has no row")
        assertNull(row(a.id, MEMORIES), "control: another application of the same tenant has no row")

        // Off twice: the day members were told stays.
        tenantSwitch(a.id, DEPOT, true, t0.plus(Duration.ofDays(3)))
        assertEquals(t0.plus(window), row(a.id, DEPOT)!!.dueAt)

        // One tick before the day: cancelled, and the tick erases nothing.
        tenantSwitch(a.id, DEPOT, false, row.dueAt.minusMillis(1))
        assertNull(row(a.id, DEPOT), "switched on before the day, the erasure is cancelled")
        assertEquals(0, purge.run(row.dueAt.plusSeconds(1)).ended)
    }

    @Test
    fun aSwitchOnFromTheDayKeepsTheRowUntilThePurgeEndsItAndAStillOffApplicationStartsAgain() {
        val a = tenant()
        tenantSwitch(a.id, DEPOT, true, t0)
        val due = row(a.id, DEPOT)!!.dueAt
        tenantSwitch(a.id, DEPOT, false, due)
        assertNotNull(row(a.id, DEPOT), "from the day the erasure is committed: a switch-on keeps the row")
        // Off again while the committed erasure still runs: the row, and its day, stay.
        tenantSwitch(a.id, DEPOT, true, due.plusSeconds(5))
        assertEquals(due, row(a.id, DEPOT)!!.dueAt)

        val later = due.plusSeconds(10)
        val tick = purge.run(later)
        assertEquals(1, tick.ended)
        val fresh = row(a.id, DEPOT)!!
        assertEquals(later, fresh.offSince, "still off when the purge ends: a fresh window from now")
        assertEquals(later.plus(window), fresh.dueAt)

        // Control: on when the purge ends, the row goes.
        tenantSwitch(a.id, DEPOT, false, fresh.dueAt)
        purge.run(fresh.dueAt.plusSeconds(1))
        assertNull(row(a.id, DEPOT))
    }

    @Test
    fun aCoreFolderIsNeverErased() {
        val a = tenant()
        val folder = seedNode(a.id, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        tenantSwitch(a.id, DEPOT, true, t0)
        purge.run(t0.plus(window).plusSeconds(1))
        assertNotNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(folder) }, "a core folder organises only")
    }

    // --- (f) two levels -----------------------------------------------------------------------------------------------

    @Test
    fun anInstanceSwitchGivesEveryTenantItsDayAndKeepsAnEarlierOne() {
        val a = tenant()
        val b = tenant()
        tenantSwitch(a.id, MEMORIES, true, t0)
        val later = t0.plus(Duration.ofDays(4))
        QuarkusTransaction.requiringNew().run { switches.set(MEMORIES, true, later) }
        assertEquals(t0.plus(window), row(a.id, MEMORIES)!!.dueAt, "a tenant already off keeps its earlier day")
        assertEquals(SwitchLevel.TENANT.name, row(a.id, MEMORIES)!!.switchedBy)
        assertEquals(later.plus(window), row(b.id, MEMORIES)!!.dueAt, "every other tenant gets its own day from now")
        assertEquals(SwitchLevel.INSTANCE.name, row(b.id, MEMORIES)!!.switchedBy)

        // The tenant switches on while the instance holds it off: the row stays.
        tenantSwitch(a.id, MEMORIES, false, later)
        assertNotNull(row(a.id, MEMORIES))

        // The instance switches on: every row goes but where the tenant's own switch is still off.
        tenantSwitch(b.id, MEMORIES, true, later)
        QuarkusTransaction.requiringNew().run { switches.set(MEMORIES, false, later.plusSeconds(1)) }
        assertNull(row(a.id, MEMORIES))
        assertNotNull(row(b.id, MEMORIES), "control: the tenant's own switch still holds it off")
        assertEquals(later.plus(window), row(b.id, MEMORIES)!!.dueAt, "and its day does not move")
    }

    @Test
    fun aTenantCreatedWhileTheInstanceHoldsAnApplicationOffGetsItsOwnDayAndNoNotice() {
        QuarkusTransaction.requiringNew().run { switches.set(EXPENSES, true, t0) }
        val (id, _) = tenantStore.createPendingTenant(
            "New", "new-${UUID.randomUUID()}.example.com", "owner-${UUID.randomUUID()}@example.com", "h-${UUID.randomUUID()}",
            Instant.now().plus(Duration.ofDays(1)),
        )
        val row = row(id, EXPENSES)!!
        assertTrue(!row.offSince.isBefore(t0), "its own window, from its creation")
        assertEquals(SwitchLevel.INSTANCE.name, row.switchedBy)
        assertNotNull(row.noticeMailedAt, "nobody is a member yet: no notice is owed")
        assertNull(row(id, DEPOT), "control: an application the instance holds on has no row")
    }

    @Test
    fun aMissingRowIsOpenedByTheTickFromNowNeverEarlier() {
        val a = tenant()
        tenantSwitch(a.id, DEPOT, true, t0)
        QuarkusTransaction.requiringNew().run { ApplicationErasureEntity.deleteAll() } // as before this table existed
        val now = t0.plus(Duration.ofDays(40))
        assertEquals(1, purge.run(now).opened)
        assertEquals(now.plus(window), row(a.id, DEPOT)!!.dueAt)
        assertEquals(0, purge.run(now).opened, "a row that exists is not opened again")
    }

    // --- (g) the mails ------------------------------------------------------------------------------------------------

    @Test
    fun everyAcceptedMemberGetsTheNoticeOnceAndTheReminderOnceSevenDaysBeforeTheDay() {
        val a = tenant()
        val recorder = recording()
        tenantSwitch(a.id, DEPOT, true, t0)

        purge.run(t0.plusSeconds(1))
        assertEquals(setOf(a.ownerEmail, a.memberEmail), recorder.notices().map { it.to }.toSet())
        assertEquals(2, recorder.notices().size, "one each")
        assertFalse(recorder.sent.any { it.to == a.pendingEmail }, "a member who has not accepted the terms gets none")
        val notice = recorder.notices().first()
        val day = notice.body.substringAfter("until ").take(10)
        assertEquals(t0.plus(window).toString().take(10), day)
        assertTrue(notice.body.startsWith("The owner of the space switched Depot off in the space \"Fam\""), notice.body)
        assertFalse(notice.body.contains("Only the operator"), "a tenant's switch: its owner can switch it on")

        purge.run(t0.plusSeconds(2))
        assertEquals(2, recorder.sent.size, "the notice goes once")

        val due = t0.plus(window)
        purge.run(due.minus(Duration.ofDays(8)))
        assertTrue(recorder.reminders().isEmpty(), "eight days before the day: no reminder yet")
        purge.run(due.minus(Duration.ofDays(7)))
        assertEquals(setOf(a.ownerEmail, a.memberEmail), recorder.reminders().map { it.to }.toSet())
        purge.run(due.minus(Duration.ofDays(6)))
        assertEquals(2, recorder.reminders().size, "the reminder goes once")
        assertTrue(recorder.reminders().first().subject.contains("is erased on ${due.toString().take(10)}"))
    }

    @Test
    fun anInstanceSwitchNoticeNamesTheOperatorAsTheOnlyOneWhoCanSwitchItOn() {
        val a = tenant()
        val recorder = recording()
        QuarkusTransaction.requiringNew().run { switches.set(MEMORIES, true, t0) }
        purge.run(t0.plusSeconds(1))
        val notice = recorder.notices().first { it.to == a.ownerEmail }
        assertTrue(notice.body.startsWith("The operator of this veyq instance switched Memories off"), notice.body)
        assertTrue(notice.body.contains("Only the operator can switch it back on.") && notice.body.contains("Erinnerungen"))
    }

    @Test
    fun withoutInstanceMailNothingIsSentAndTheRowStands() {
        // The real mailer, with no instance mail configured in the test database: every try answers FAILED.
        assertNull(QuarkusTransaction.requiringNew().call { settings.find() }, "this test needs an instance without mail")
        val a = tenant()
        tenantSwitch(a.id, DEPOT, true, t0)
        val tick = purge.run(t0.plusSeconds(1))
        assertEquals(0, tick.notices)
        val row = row(a.id, DEPOT)!!
        assertEquals(t0.plus(window), row.dueAt, "the window does not grow")
        assertNotNull(row.noticeMailedAt, "the attempt was made: it is not tried again")
        // Control: the same tick with a mailer that sends counts the mails.
        tenantSwitch(a.id, EXPENSES, true, t0)
        recording()
        assertEquals(2, purge.run(t0.plusSeconds(2)).notices)
    }

    // --- (h) the API --------------------------------------------------------------------------------------------------

    @Test
    fun aMemberReadsTheDayAndTheOwnerReadsItBesideTheEntry() {
        val a = tenant()
        given().header("Authorization", bearer(a.member)).`when`().get("/api/tenants/${a.id}/capabilities")
            .then().statusCode(200).body("applicationErasures.size()", equalTo(0))

        val put = given().header("Authorization", bearer(a.owner)).contentType(ContentType.JSON).body("""{"off":true}""")
            .`when`().put("/api/tenants/${a.id}/capability-switches/$DEPOT")
            .then().statusCode(200).extract().path<String>("erasureDueAt")
        val due = Instant.parse(put)
        assertEquals(row(a.id, DEPOT)!!.dueAt, due)

        given().header("Authorization", bearer(a.member)).`when`().get("/api/tenants/${a.id}/capabilities")
            .then().statusCode(200)
            .body("applicationErasures.size()", equalTo(1))
            .body("applicationErasures[0].id", equalTo(DEPOT))
            .body("applicationErasures[0].dueAt", equalTo(put))
        val owner = given().header("Authorization", bearer(a.owner)).`when`().get("/api/tenants/${a.id}/capability-switches")
            .then().statusCode(200).extract().jsonPath()
        assertEquals(put, owner.getString("find { it.id == '$DEPOT' }.erasureDueAt"))
        assertNull(owner.getString("find { it.id == '$MEMORIES' }.erasureDueAt"), "control: an application that is on names no day")
        assertNull(owner.getString("find { it.id == 'core.public-shares' }.erasureDueAt"), "control: no entry but an application's names one")

        given().header("Authorization", bearer(a.owner)).contentType(ContentType.JSON).body("""{"off":false}""")
            .`when`().put("/api/tenants/${a.id}/capability-switches/$DEPOT")
            .then().statusCode(200).body("erasureDueAt", nullValue())
        given().header("Authorization", bearer(a.member)).`when`().get("/api/tenants/${a.id}/capabilities")
            .then().statusCode(200).body("applicationErasures.size()", equalTo(0))
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private data class World(
        val id: String, val owner: String, val member: String, val ownerEmail: String, val memberEmail: String,
        val pendingEmail: String,
    )

    private fun tenant(): World {
        val ownerEmail = "o-${UUID.randomUUID()}@example.com"
        val memberEmail = "m-${UUID.randomUUID()}@example.com"
        val pendingEmail = "p-${UUID.randomUUID()}@example.com"
        val owner = seedUser(email = ownerEmail)
        val id = seedTenant(owner = owner)
        val member = seedUser(email = memberEmail)
        val pending = seedUser(email = pendingEmail)
        QuarkusTransaction.requiringNew().run {
            TenantRoots.join(member, id)
            TenantRoots.join(pending, id)
            MembershipEntity.update("pendingConsent = true where userId = ?1 and scopeId = ?2", pending, id)
        }
        return World(id, owner, member, ownerEmail, memberEmail, pendingEmail)
    }

    private fun tenantSwitch(tenantId: String, id: String, off: Boolean, now: Instant) =
        QuarkusTransaction.requiringNew().run { tenantSwitches.set(tenantId, id, off, now) }

    private fun row(tenantId: String, application: String): ApplicationErasureEntity? =
        QuarkusTransaction.requiringNew().call { ApplicationErasureEntity.findById(ApplicationErasureKey(tenantId, application)) }

    private fun recording(): RecordingMailer =
        RecordingMailer(settings, notices, wire).also { QuarkusMock.installMockForType(it, InstanceNoticeMailer::class.java) }

    /** Instance mail that records what it was handed and answers [DeliveryResult.SENT] — Mailpit is not available here. */
    class RecordingMailer(settings: InstanceMailSettingsStore, notices: ReleaseNoticeStore, wire: SmtpDelivery) :
        InstanceNoticeMailer(settings, notices, wire) {
        data class Mail(val to: String, val subject: String, val body: String, val what: String)

        val sent = mutableListOf<Mail>()

        override fun mailTo(to: String, subject: String, body: String, what: String): DeliveryResult {
            synchronized(sent) { sent += Mail(to, subject, body, what) }
            return DeliveryResult.SENT
        }

        fun notices() = sent.filter { it.what == "an application's switch-off notice" }
        fun reminders() = sent.filter { it.what == "an application's erasure reminder" }
    }

    private companion object {
        const val MEMORIES = "memories"
        const val DEPOT = "depot"
        const val EXPENSES = "expenses"
    }
}
