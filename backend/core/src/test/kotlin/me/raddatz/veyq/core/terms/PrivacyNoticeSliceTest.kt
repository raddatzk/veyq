package me.raddatz.veyq.core.terms

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsSliceTest
import me.raddatz.veyq.core.admin.operator.OperatingMode
import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorRecipientEntity
import me.raddatz.veyq.core.blobstore.StoreSettingEntity
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRetentionEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceOrigins
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceStore
import me.raddatz.veyq.core.platform.capabilities.ServiceAddress
import me.raddatz.veyq.core.platform.capabilities.StoredExternalServiceAddresses
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The instance privacy notice over the full HTTP stack (uc.admin.privacy-notice; ADR 0035 decision 8, decisions 1–3 of
 * cr-instance-privacy-notice), with the values it renders set on the admin channel (uc.admin.operator-details). Every
 * negative stands beside a control on the same read:
 *   - served without a session, and the read writes nothing — no row, and the caller's address in no log line;
 *   - a passage bound to a switch is there exactly while the switch is on, for the space the host serves;
 *   - the breached-password check names the address the instance calls: a mirror replaces HIBP's name;
 *   - the recipients follow the mode when none is named; the recipients and the officer are set, served and cleared;
 *   - the instance suggests the recipients it knows of (an external store's host, the mail relay's host).
 *
 * The operator row, the recipients, the switches, the services, the store and the mail settings are instance-wide H2
 * state: [restore] puts each back.
 */
@QuarkusTest
class PrivacyNoticeSliceTest {

    @Inject lateinit var modes: OperatingModeHandler
    @Inject lateinit var services: ExternalServiceStore
    @Inject lateinit var addresses: StoredExternalServiceAddresses
    @Inject lateinit var origins: ExternalServiceOrigins

    @BeforeEach
    fun start() {
        ensureUser(OWNER_ID, "privacy-owner@example.com", "SERVER_OWNER")
        restore()
    }

    @AfterEach
    fun restore() {
        QuarkusTransaction.requiringNew().run {
            OperatorRecipientEntity.deleteAll()
            OperatorDetailsEntity.deleteAll()
            GuestWriteRetentionEntity.deleteAll()
            StoreSettingEntity.delete("id = ?1 and endpoint = ?2", StoreSettingEntity.ACTIVE_ID, EXTERNAL_STORE)
            services.set(CapabilityIds.BREACHED_PASSWORD, ServiceAddress(HIBP, null))
        }
        InstanceMailSettingsSliceTest.clear()
        addresses.invalidate()
        origins.refresh()
        CapabilitySwitchFixtures.clear()
        TenantCapabilitySwitchFixtures.clear()
        modes.afterCommit()
    }

    @Test
    fun theNoticeIsServedWithoutASessionAndTheReadWritesNothing() {
        val before = rows()
        val logs = capture {
            given().header("X-Forwarded-For", CALLER).`when`().get(PATH).then().statusCode(200)
                .body("revision", equalTo(3)).body("draftBanner.en", equalTo("*Draft: this privacy notice has not yet been reviewed by a lawyer.*"))
        }
        assertEquals(before, rows(), "reading the notice writes no row")
        assertFalse(logs.contains(CALLER), "the caller's address is in no log line")
        val ids = notice().getList<String>("passages.id")
        for (id in listOf("controller", "account", "reporter-contact", "rights", "right-to-object")) assertTrue(id in ids, id)
    }

    @Test
    fun theRecordsPassageFollowsItsSwitchWithTheNServedNow() {
        owner().contentType(ContentType.JSON).body("""{"retentionDays":12}""").`when`().put("/api/instance/guest-write-record")
            .then().statusCode(200)
        val on = passage("guest-write-record")
        assertTrue(on!!.contains("deleted 12 days after the write"), "organisation, the record on (its preset), with N now: $on")
        assertTrue(passage("guest-write-record-objection") != null, "and its right to object, apart")
        CapabilitySwitchFixtures.with(GuestWriteRecords.SWITCH) {
            assertNull(passage("guest-write-record"), "the record off: the passage goes")
            assertNull(passage("guest-write-record-objection"), "and its right to object with it")
        }
    }

    @Test
    fun aMirrorReplacesHibpsNameAndTheSwitchDropsThePassage() {
        assertTrue(passage("breached-password-check")!!.contains("Have I Been Pwned (api.pwnedpasswords.com)"), "control: the default names HIBP")
        setService(CapabilityIds.BREACHED_PASSWORD, "https://pwned.mirror.example/range/")
        val mirror = passage("breached-password-check")!!
        assertTrue(mirror.contains("asks pwned.mirror.example whether"), mirror)
        assertFalse(mirror.contains("Have I Been Pwned") || mirror.contains("pwnedpasswords"), "a mirror never names HIBP")
        CapabilitySwitchFixtures.with(CapabilityIds.BREACHED_PASSWORD) {
            assertNull(passage("breached-password-check"), "the check off: no passage")
        }
    }

    @Test
    fun aSpacesOwnSwitchDropsItsServiceOnItsHostOnly() {
        val domain = "privacy-notice-host.example.com"
        val tenantId = seedTenant(domain = domain)
        assertTrue(passage("browser-services", domain)!!.contains("tile.openstreetmap.org"), "control: the map runs on the space's host")
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.MEMORIES_MAP) {
            assertFalse(passage("browser-services", domain)!!.contains("tile.openstreetmap.org"), "the space switched the map off: not named on its host")
            assertTrue(passage("browser-services")!!.contains("tile.openstreetmap.org"), "control: the instance level still names it")
        }
    }

    @Test
    fun theRecipientsFollowTheModeWhenNoneIsNamed() {
        assertTrue(passage("recipients")!!.contains("not stated by this operator"), "organisation, none named: the gap shows")
        modes.set(OperatingMode.PRIVATE)
        assertNull(passage("recipients"), "private, none named: the passage is absent")
        put(RECIPIENTS).statusCode(200)
        assertTrue(passage("recipients")!!.contains("- Example Hosting GmbH: hosts the server (Germany)"), "control: a named one renders in private too")
    }

    @Test
    fun theRecipientsAndTheOfficerAreSetServedInOrderAndCleared() {
        put(RECIPIENTS).statusCode(200)
            .body("recipients.name", equalTo(listOf("Example Hosting GmbH", "Mail AG", "SMS AG")))
            .body("recipients.kind", equalTo(listOf("HOSTING", "MAIL_RELAY", "OTHER")))
            .body("recipients.description", equalTo(listOf(null, null, "sends sign-in texts")))
            .body("recipients.country", equalTo(listOf("DE", null, "FI")))
            .body("dataProtectionOfficer", equalTo("DSB GmbH, dsb@example.org"))
        val recipients = passage("recipients")!!
        assertTrue(recipients.indexOf("Example Hosting GmbH") < recipients.indexOf("Mail AG"), "in the order given: $recipients")
        assertTrue(passage("controller")!!.contains("Data-protection officer: DSB GmbH, dsb@example.org"))
        // Each page names what they do in its own language; only an OTHER one's typed words are the same on both.
        assertTrue(recipients.contains("- Mail AG: relays the mail (not stated by this operator)"), recipients)
        val german = passage("recipients", language = "de")!!
        assertTrue(german.contains("- Example Hosting GmbH: hostet den Server (Deutschland)"), german)
        assertTrue(german.contains("- SMS AG: Sonstiges — „sends sign-in texts“ (Finnland)"), german)
        assertFalse(german.contains("hosts the server") || german.contains("relays the mail"), "no English kind on the German page: $german")
        // and the country by its name in the page's language, from the code stored
        assertTrue(recipients.contains("- Example Hosting GmbH: hosts the server (Germany)"), recipients)
        assertFalse(german.contains("Germany") || german.contains("Finland"), "no English country on the German page: $german")

        // A row with a blank name, an unknown kind, a description that does not fit its kind, a country that is no code
        // or no code the JDK knows, or a value over its bound is refused: each with nothing stored (control: the list
        // saved above stays, its codes accepted).
        put("""{"recipients":[{"name":" ","kind":"HOSTING"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"hosts the server"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"OTHER"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"OTHER","description":" "}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"HOSTING","description":"hosts the server"}]}""").statusCode(400)
            .body("code", equalTo("invalid_request"))
        put("""{"recipients":[{"name":"n","kind":"OTHER","description":"${"x".repeat(121)}"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"HOSTING","country":"Germany"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"HOSTING","country":"de"}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"HOSTING","country":" "}]}""").statusCode(400)
        put("""{"recipients":[{"name":"n","kind":"HOSTING","country":"XX"}]}""").statusCode(400)
            .body("code", equalTo("invalid_request"))
        put("""{"recipients":[{"name":"${"x".repeat(201)}","kind":"HOSTING"}]}""").statusCode(400)
        put("""{"recipients":[${(1..21).joinToString(",") { """{"name":"n$it","kind":"BACKUP"}""" }}]}""").statusCode(400)
        owner().`when`().get("/api/instance/operator").then()
            .body("recipients.name", equalTo(listOf("Example Hosting GmbH", "Mail AG", "SMS AG")))

        owner().`when`().delete("/api/instance/operator").then().statusCode(200)
            .body("recipients.size()", equalTo(0)).body("dataProtectionOfficer", equalTo(null))
        assertEquals(0L, QuarkusTransaction.requiringNew().call { OperatorRecipientEntity.count() })
    }

    @Test
    fun theInstanceSuggestsTheRecipientsItKnowsOf() {
        owner().`when`().get("/api/instance/operator").then().statusCode(200).body("recipientSuggestions.size()", equalTo(0))
        QuarkusTransaction.requiringNew().run {
            StoreSettingEntity.deleteById(StoreSettingEntity.ACTIVE_ID)
            StoreSettingEntity().apply { id = StoreSettingEntity.ACTIVE_ID; bundled = false; endpoint = EXTERNAL_STORE }.persist()
            InstanceMailSettingsEntity().apply { host = "SMTP.Example.org" }.persist()
        }
        owner().`when`().get("/api/instance/operator").then().statusCode(200)
            .body("recipientSuggestions.kind", equalTo(listOf("OBJECT_STORAGE", "MAIL_RELAY")))
            .body("recipientSuggestions.host", equalTo(listOf("s3.provider.example", "smtp.example.org")))
            .body("recipients.size()", equalTo(0)) // a suggestion is never stored, nor published
        assertNull(passage("recipients")!!.takeIf { it.contains("s3.provider.example") }, "nothing is published unconfirmed")
        assertTrue(passage("storage-external")!!.contains("at s3.provider.example"), "the notice says where the store is")
        assertNull(passage("storage"), "and not that it is the instance's own")
    }

    // --- helpers ---

    private fun notice(host: String? = null): JsonPath {
        var spec = given()
        if (host != null) spec = spec.header("X-Forwarded-Host", host)
        return spec.`when`().get(PATH).then().statusCode(200).extract().jsonPath()
    }

    /** The English text of passage [id] on [host]'s read, or null where it is absent. */
    private fun passage(id: String, host: String? = null, language: String = "en"): String? {
        val n = notice(host)
        val i = n.getList<String>("passages.id").indexOf(id)
        return if (i < 0) null else n.getString("passages[$i].text.$language")
    }

    private fun owner() = givenAdminChannel().header("Authorization", bearer(OWNER_ID))

    private fun put(body: String) = owner().contentType(ContentType.JSON).body(body).`when`().put("/api/instance/operator").then()

    private fun setService(id: String, url: String) {
        QuarkusTransaction.requiringNew().run { services.set(id, ServiceAddress(url, null)) }
        addresses.invalidate()
        origins.refresh()
    }

    private fun rows(): List<Long> = QuarkusTransaction.requiringNew().call {
        listOf(GuestWriteRecordEntity.count(), OperatorDetailsEntity.count(), OperatorRecipientEntity.count())
    }

    /** Every log record the app emits at its configured levels while [block] runs, as text; a control proves the capture. */
    private fun capture(block: () -> Unit): String {
        val records = mutableListOf<LogRecord>()
        val root = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }.apply { level = Level.ALL }
        root.addHandler(handler)
        try {
            block()
            Logger.getLogger("me.raddatz.veyq.core.terms").info(CONTROL)
        } finally {
            root.removeHandler(handler)
        }
        assertTrue(records.any { it.message == CONTROL }, "the capture missed the control record — it is not attached where the logs go")
        return records.joinToString("\n") { "${it.message} ${it.parameters?.joinToString() ?: ""} ${it.thrown ?: ""}" }
    }

    private companion object {
        const val PATH = "/api/operator/privacy-notice"
        const val OWNER_ID = "5d1f7a2c-3e8b-4c9d-b0a1-6f2e8d4c7b19"
        const val CALLER = "198.51.100.77"
        const val HIBP = "https://api.pwnedpasswords.com/range/"
        const val EXTERNAL_STORE = "https://s3.provider.example"
        const val CONTROL = "privacy-notice capture control"
        val RECIPIENTS = """
            {"recipients":[{"name":"Example Hosting GmbH","kind":"HOSTING","country":"DE"},
                           {"name":"Mail AG","kind":"MAIL_RELAY"},
                           {"name":"SMS AG","kind":"OTHER","description":"sends sign-in texts","country":"FI"}],
             "dataProtectionOfficer":"DSB GmbH, dsb@example.org"}
        """.trimIndent()
    }
}
