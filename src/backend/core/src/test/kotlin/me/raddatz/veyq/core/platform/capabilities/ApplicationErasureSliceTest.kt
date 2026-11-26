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
import org.hamcrest.CoreMatchers.notNullValue
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
 * the row of one tenant (e), two levels (f), the mails (g) and the API (h), each a section below), with core alone: no
 * application kind exists here, so this class proves what the switches, the tick and the API do with the row over
 * [CoreTestApplicationKinds]' stand-in kinds, and the root's `ApplicationErasureSweepTest` what the erasure does to the
 * content. The clock is driven through each writer's `now`.
 *
 * **A row exists only where the tenant holds a node of the application** (ADR 0088 decision 5): every writer of a row's
 * existence — the tenant switch, the instance switch, the tick's opening of a missing row and its end of a window, and
 * no longer a tenant's creation — has a case where the tenant holds one (a row, as before) beside one where it holds
 * none (no row), and a trashed node counts as held.
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

    /** The tenant switch's no-row case: nothing of the application held, nothing to erase — beside a held one's row. */
    @Test
    fun aTenantSwitchOffWhereTheTenantHoldsNothingOfTheApplicationNamesNoDay() {
        val a = tenant()
        val empty = tenant(holding = emptySet())
        tenantSwitch(empty.id, DEPOT, true, t0)
        assertNull(row(empty.id, DEPOT), "a tenant that holds no node of Depot gets no day")
        tenantSwitch(a.id, DEPOT, true, t0)
        assertNotNull(row(a.id, DEPOT), "control: the same switch where a node of Depot is held names the day")
        // Only that application's nodes count: a tenant that holds Memories but no Depot gets no Depot day.
        val memoriesOnly = tenant(holding = setOf(MEMORIES))
        tenantSwitch(memoriesOnly.id, DEPOT, true, t0)
        assertNull(row(memoriesOnly.id, DEPOT), "a node of another application is not one of Depot")
        tenantSwitch(memoriesOnly.id, MEMORIES, true, t0)
        assertNotNull(row(memoriesOnly.id, MEMORIES), "control: its own application's node is")
    }

    /** A trashed node can be restored while the application is off, so it is there to erase: it counts as held. */
    @Test
    fun aTrashedNodeOfTheApplicationCountsAsHeld() {
        val trashedOnly = tenant(holding = emptySet())
        val node = seedNode(trashedOnly.id, CoreTestApplicationKinds.of(DEPOT), wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run { NodeEntity.update("deletedAt = ?1 where id = ?2", t0, node) }
        tenantSwitch(trashedOnly.id, DEPOT, true, t0)
        assertNotNull(row(trashedOnly.id, DEPOT), "a tenant whose only Depot node is in the trash gets its day")
        // Control on the same predicate: the same tenant, the node gone for good, switched off again — no day.
        QuarkusTransaction.requiringNew().run { NodeEntity.delete("id", node) }
        tenantSwitch(trashedOnly.id, DEPOT, false, t0.plusSeconds(1))
        tenantSwitch(trashedOnly.id, DEPOT, true, t0.plusSeconds(2))
        assertNull(row(trashedOnly.id, DEPOT), "nothing held any more: no day")
    }

    @Test
    fun aSwitchOnFromTheDayKeepsTheRowUntilThePurgeEndsItAndAStillOffApplicationStartsAgain() {
        val a = tenant()
        tenantSwitch(a.id, DEPOT, true, t0)
        val due = row(a.id, DEPOT)!!.dueAt
        tenantSwitch(a.id, DEPOT, false, due)
        assertNotNull(row(a.id, DEPOT), "from the day the erasure is committed: a switch-on keeps the row")
        // A file made while it is on again (A2): made after the day, so the erasure spares it and it is still held.
        madeAt(seedNode(a.id, CoreTestApplicationKinds.of(DEPOT), wrappedContentKey = CONTENT_KEY), due.plusSeconds(1))
        // Off again while the committed erasure still runs: the row, and its day, stay.
        tenantSwitch(a.id, DEPOT, true, due.plusSeconds(5))
        assertEquals(due, row(a.id, DEPOT)!!.dueAt)

        val later = due.plusSeconds(10)
        val tick = purge.run(later)
        assertEquals(1, tick.ended)
        val fresh = row(a.id, DEPOT)!!
        assertEquals(later, fresh.offSince, "still off and still held when the purge ends: a fresh window from now")
        assertEquals(later.plus(window), fresh.dueAt)

        // Control: on when the purge ends, the row goes.
        tenantSwitch(a.id, DEPOT, false, fresh.dueAt)
        purge.run(fresh.dueAt.plusSeconds(1))
        assertNull(row(a.id, DEPOT))
    }

    /** The tick's end with nothing left: no next window, though the application is still off — beside one that holds on. */
    @Test
    fun aWindowThatEndsWithNothingLeftStartsNoNextOneWhileStillOff() {
        val emptied = tenant(holding = setOf(DEPOT))
        val keeps = tenant(holding = setOf(DEPOT))
        tenantSwitch(emptied.id, DEPOT, true, t0)
        tenantSwitch(keeps.id, DEPOT, true, t0)
        val due = row(emptied.id, DEPOT)!!.dueAt
        // [keeps] was switched on after the day, made a file, and is off again: that file is not erased on this day.
        tenantSwitch(keeps.id, DEPOT, false, due)
        madeAt(seedNode(keeps.id, CoreTestApplicationKinds.of(DEPOT), wrappedContentKey = CONTENT_KEY), due.plusSeconds(1))
        tenantSwitch(keeps.id, DEPOT, true, due.plusSeconds(2))

        val later = due.plusSeconds(10)
        val tick = purge.run(later)
        assertEquals(2, tick.ended)
        assertTrue(QuarkusTransaction.requiringNew().call { store.isOff(emptied.id, DEPOT) }, "the application is still off")
        assertNull(row(emptied.id, DEPOT), "every node of it erased: no next window, no next notice")
        assertEquals(later.plus(window), row(keeps.id, DEPOT)!!.dueAt, "control: a node still held starts the next window")
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
        val empty = tenant(holding = emptySet())
        tenantSwitch(a.id, MEMORIES, true, t0)
        val later = t0.plus(Duration.ofDays(4))
        QuarkusTransaction.requiringNew().run { switches.set(MEMORIES, true, later) }
        assertEquals(t0.plus(window), row(a.id, MEMORIES)!!.dueAt, "a tenant already off keeps its earlier day")
        assertEquals(SwitchLevel.TENANT.name, row(a.id, MEMORIES)!!.switchedBy)
        assertEquals(later.plus(window), row(b.id, MEMORIES)!!.dueAt, "every other tenant gets its own day from now")
        assertEquals(SwitchLevel.INSTANCE.name, row(b.id, MEMORIES)!!.switchedBy)
        assertNull(row(empty.id, MEMORIES), "a tenant that holds nothing of Memories gets no day (control: b's)")

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

    /** A4: a new tenant holds nothing, so an application the instance holds off is simply absent there — no day. */
    @Test
    fun aTenantCreatedWhileTheInstanceHoldsAnApplicationOffGetsNoDay() {
        val existing = tenant()
        QuarkusTransaction.requiringNew().run { switches.set(EXPENSES, true, t0) }
        val (id, _) = tenantStore.createPendingTenant(
            "New", "new-${UUID.randomUUID()}.example.com", "owner-${UUID.randomUUID()}@example.com", "h-${UUID.randomUUID()}",
            Instant.now().plus(Duration.ofDays(1)),
        )
        assertNull(row(id, EXPENSES), "a tenant created while Expenses is off holds nothing of it: no day")
        assertEquals(0, purge.run(t0.plusSeconds(1)).opened, "and the tick opens none for it")
        assertNull(row(id, EXPENSES))
        assertNotNull(row(existing.id, EXPENSES), "control: the instance switch named the day where a Pot is held")
    }

    @Test
    fun aMissingRowIsOpenedByTheTickFromNowNeverEarlier() {
        val a = tenant()
        val empty = tenant(holding = emptySet())
        tenantSwitch(a.id, DEPOT, true, t0)
        tenantSwitch(empty.id, DEPOT, true, t0)
        QuarkusTransaction.requiringNew().run { ApplicationErasureEntity.deleteAll() } // as before this table existed
        val now = t0.plus(Duration.ofDays(40))
        assertEquals(1, purge.run(now).opened, "only where a node of Depot is held")
        assertEquals(now.plus(window), row(a.id, DEPOT)!!.dueAt)
        assertNull(row(empty.id, DEPOT), "a tenant off that holds nothing of Depot gets no row from the tick")
        assertEquals(0, purge.run(now).opened, "a row that exists is not opened again")

        // A write that read Depot as on before the switch committed lands a node with no row (the gate reads the cached
        // switch, not under the tenant's lock): the next tick opens its window, from then.
        seedNode(empty.id, CoreTestApplicationKinds.of(DEPOT), wrappedContentKey = CONTENT_KEY)
        val next = now.plus(Duration.ofHours(1))
        assertEquals(1, purge.run(next).opened)
        assertEquals(next.plus(window), row(empty.id, DEPOT)!!.dueAt)
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
        val empty = tenant(holding = emptySet())
        val recorder = recording()
        QuarkusTransaction.requiringNew().run { switches.set(MEMORIES, true, t0) }
        purge.run(t0.plusSeconds(1))
        val emptyMembers = setOf(empty.ownerEmail, empty.memberEmail)
        assertTrue(recorder.sent.none { it.to in emptyMembers }, "a tenant that holds nothing of it is mailed nothing")
        purge.run(t0.plus(window).minus(Duration.ofDays(7)))
        assertTrue(recorder.reminders().none { it.to in emptyMembers }, "and is reminded of nothing")
        assertTrue(recorder.reminders().any { it.to == a.ownerEmail }, "control: the tenant that holds Memories is reminded")
        val notice = recorder.notices().first { it.to == a.ownerEmail }
        assertTrue(notice.body.startsWith("The operator of this veyq server switched Memories off"), notice.body)
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

    /** What the banner reads: a tenant that holds nothing of an application switched off is told no day (decision 5). */
    @Test
    fun aTenantThatHoldsNothingOfTheApplicationReadsNoDay() {
        val empty = tenant(holding = emptySet())
        given().header("Authorization", bearer(empty.owner)).contentType(ContentType.JSON).body("""{"off":true}""")
            .`when`().put("/api/tenants/${empty.id}/capability-switches/$DEPOT")
            .then().statusCode(200).body("off", equalTo(true)).body("erasureDueAt", nullValue())
        given().header("Authorization", bearer(empty.member)).`when`().get("/api/tenants/${empty.id}/capabilities")
            .then().statusCode(200).body("applicationErasures.size()", equalTo(0))
        // Control on the same reads: the same switch where a node of Depot is held names its day.
        val a = tenant()
        given().header("Authorization", bearer(a.owner)).contentType(ContentType.JSON).body("""{"off":true}""")
            .`when`().put("/api/tenants/${a.id}/capability-switches/$DEPOT")
            .then().statusCode(200).body("erasureDueAt", notNullValue())
        given().header("Authorization", bearer(a.member)).`when`().get("/api/tenants/${a.id}/capabilities")
            .then().statusCode(200).body("applicationErasures.size()", equalTo(1))
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private data class World(
        val id: String, val owner: String, val member: String, val ownerEmail: String, val memberEmail: String,
        val pendingEmail: String,
    )

    /** A tenant with an owner, an accepted member and a pending one, holding one node of each application in [holding]. */
    private fun tenant(holding: Set<String> = setOf(MEMORIES, DEPOT, EXPENSES)): World {
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
        holding.forEach { seedNode(id, CoreTestApplicationKinds.of(it), wrappedContentKey = CONTENT_KEY) }
        return World(id, owner, member, ownerEmail, memberEmail, pendingEmail)
    }

    /** Set [nodeId]'s `createdAt` to [at]: a node made then, as the erasure reads it. */
    private fun madeAt(nodeId: String, at: Instant) =
        QuarkusTransaction.requiringNew().run { NodeEntity.update("createdAt = ?1 where id = ?2", at, nodeId) }

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
