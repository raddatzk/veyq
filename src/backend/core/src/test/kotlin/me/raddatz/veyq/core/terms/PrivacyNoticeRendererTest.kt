package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.account.email.EmailChangeMailer
import me.raddatz.veyq.core.account.email.EmailConfirmationMailer
import me.raddatz.veyq.core.account.erasure.AccountErasureMail
import me.raddatz.veyq.core.account.recovery.RecoveryMailer
import me.raddatz.veyq.core.admin.instance.InstanceNoticeMailer
import me.raddatz.veyq.core.admin.tenant.SmtpTenantInviteMailer
import me.raddatz.veyq.core.admin.tenant.TenantDeletionMail
import me.raddatz.veyq.core.admin.tenant.TenantMailSender
import me.raddatz.veyq.core.moderation.statement.StatementMail
import me.raddatz.veyq.core.platform.capabilities.ApplicationErasureMail
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The privacy notice's text and its rendering (ADR 0035 decision 8, decisions 1–4 of cr-instance-privacy-notice), pure:
 * the shipped `contracts/terms/privacy-notice.json` is parsed as the jar carries it, and each rule of the renderer is
 * held by a case and its control on the same text — every negative beside the positive that shows the passage can be
 * there at all.
 */
class PrivacyNoticeRendererTest {

    private val file = File("../../contracts/terms/privacy-notice.json")
    private val text = PrivacyNoticeText.parse(ObjectMapper().readTree(file))

    private val all = NoticeValues(
        providerName = "Example e. V.",
        providerAddress = "Musterstraße 1\n12345 Musterstadt",
        providerEmail = "info@example.org",
        providerRepresentative = "Erika Mustermann",
        privacyContact = "privacy@example.org",
        dataProtectionOfficer = "DSB Beispiel GmbH, dsb@example.org",
        supervisoryAuthority = "LfD Musterland",
        recipients = listOf(NoticeRecipient("Example Hosting GmbH", RecipientKind.HOSTING, null, "DE")),
        retentionDays = 7,
        backupRetentionDays = 30,
        erasureWindowDays = 30,
        trashWindowDays = 14,
        applicationOffWindowDays = 45,
        breachedPasswordService = PrivacyNoticeRenderer.serviceLabel("https://api.pwnedpasswords.com/range/"),
        exchangeRateService = PrivacyNoticeRenderer.serviceLabel("https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}"),
        browserServices = listOf(BrowserService(SectionTexts("Maps", "Karten"), "tile.example.org")),
        storeHost = null,
    )

    private val everythingOn: (String) -> Boolean = { true }

    private fun render(
        values: NoticeValues = all,
        active: (String) -> Boolean = everythingOn,
    ) = PrivacyNoticeRenderer.render(text, values, active)

    private fun RenderedNotice.passage(id: String) = passages.firstOrNull { it.id == id }

    @Test
    fun theJarCarriesTheContractFileUnchanged() {
        assertEquals(text, PrivacyNoticeText.load(), "the build's copy of the notice differs from contracts/terms/")
    }

    @Test
    fun everyPlaceholderTheFileNamesIsOneTheRendererKnows() {
        for (p in render().passages) {
            assertFalse(Regex("""\{\w+}""").containsMatchIn(p.text.en + p.text.de), "an unfilled placeholder in ${p.id}")
        }
    }

    @Test
    fun anUnknownPlaceholderIsRefusedRatherThanPrinted() {
        val broken = text.copy(passages = text.passages + NoticePassage("x", false, NoticeConditions(), listOf(SectionLine(SectionTexts("{nobody}", "{nobody}"), false))))
        assertThrows<IllegalStateException> { PrivacyNoticeRenderer.render(broken, all, everythingOn) }
    }

    @Test
    fun theAnchorsTheClientsLinkExistAndAreStable() {
        // The invite acceptance links `account`, the report dialog `reporter-contact`, a guest's first write
        // `guest-write-record` (src/web/src/core/privacy/privacyNotice.ts) — renaming one breaks a link nobody sees.
        val ids = text.passages.map { it.id }
        for (anchor in listOf("account", "reporter-contact", "guest-write-record")) assertTrue(anchor in ids, anchor)
    }

    @Test
    fun theRecordsPassageIsAlwaysThereWithNAndBAndItsRightToObjectApart() {
        val on = render()
        val record = on.passage("guest-write-record")!!
        assertTrue(record.text.en.contains("deleted 7 days after the write") && record.text.de.contains("7 Tage nach dem Schreibvorgang"), record.text.en)
        assertTrue(record.text.en.contains("up to 30 days longer"), "N + B: a backup made another way, by B")
        val objection = on.passage("guest-write-record-objection")!!
        assertTrue(objection.separate, "the right to object is set apart (Art. 21(4))")
        assertTrue(objection.text.en.contains("guest id") && objection.text.en.contains("does not stop a later write from being recorded"))
        assertFalse(record.text.en.contains("Your right to object"), "never inside the record's own passage")

        // No switch of its own (ADR 0093 § 10): the passages are bound to none, so nothing active or not drops them.
        val nothingOn = render(active = { false })
        assertTrue(nothingOn.passage("guest-write-record") != null && nothingOn.passage("guest-write-record-objection") != null)
        assertNull(nothingOn.passage("breached-password-check"), "control: a passage bound to a switch goes with it")
    }

    /** GDPR Art. 13(2)(a): the storage period of content, named by both windows the instance runs
     *  (uc.platform.erase-switched-off-application), on the bundled store's page and an external one's alike. */
    @Test
    fun theStoragePassageNamesTheTrashAndTheSwitchedOffWindow() {
        for (values in listOf(all, all.copy(storeHost = "s3.example.org"))) {
            val storage = render(values = values).passages.single { it.id == "storage" || it.id == "storage-external" }
            assertTrue(storage.text.en.contains("waits 14 days in its space's trash"), storage.text.en)
            assertTrue(storage.text.en.contains("stays readable for 45 days and is then erased"), storage.text.en)
            assertTrue(storage.text.de.contains("liegt 14 Tage im Papierkorb") && storage.text.de.contains("45 Tage lang lesbar"), storage.text.de)
        }
    }

    /**
     * GDPR Art. 13(1)(e): the `mail` passage names which relay sends which mail, and each mail it names is sent where it
     * says. The recovery and the address mails go through the space's relay ([TenantMailSender]), the erasure and
     * moderation mails through the instance's ([InstanceNoticeMailer]); a mailer moved to the other relay turns this red
     * and asks for the passage to move with it.
     */
    @Test
    fun theMailPassageNamesWhichRelaySendsWhichMail() {
        fun sendsThrough(mailer: Class<*>, relay: Class<*>) = mailer.constructors.any { relay in it.parameterTypes }
        for (space in listOf(RecoveryMailer::class.java, EmailConfirmationMailer::class.java, EmailChangeMailer::class.java, SmtpTenantInviteMailer::class.java)) {
            assertTrue(sendsThrough(space, TenantMailSender::class.java), "${space.simpleName} no longer sends through the space's relay")
        }
        for (instance in listOf(AccountErasureMail::class.java, StatementMail::class.java, TenantDeletionMail::class.java, ApplicationErasureMail::class.java)) {
            assertTrue(sendsThrough(instance, InstanceNoticeMailer::class.java), "${instance.simpleName} no longer sends through the instance's relay")
        }

        val mail = render().passage("mail")!!
        val (instanceEn, spaceEn) = mail.text.en.split("The relay the space's owner set up").also { assertEquals(2, it.size, mail.text.en) }
        assertTrue(listOf("account's erasure", "moderation", "the space's deletion", "an application switched off").all { it in instanceEn }, instanceEn)
        assertTrue(listOf("invitations", "confirm or change your address", "recover your access").all { it in spaceEn }, spaceEn)
        assertFalse("recover" in instanceEn || "invitation" in instanceEn, "control: no space mail is named under the instance's relay: $instanceEn")

        val (instanceDe, spaceDe) = mail.text.de.split("Das Relay, das die Person").also { assertEquals(2, it.size, mail.text.de) }
        assertTrue(listOf("Löschung deines Kontos", "Moderation", "der des Bereichs", "einer Anwendung").all { it in instanceDe }, instanceDe)
        assertTrue(listOf("Einladungen", "bestätigen oder ändern", "Zugang wiederherstellen").all { it in spaceDe }, spaceDe)
        assertFalse("wiederherstellen" in instanceDe || "Einladung" in instanceDe, "control: $instanceDe")
    }

    @Test
    fun theBreachedPasswordCheckNamesTheServiceTheInstanceCalls() {
        val default = render().passage("breached-password-check")!!
        assertTrue(default.text.en.contains("Have I Been Pwned (api.pwnedpasswords.com)"), default.text.en)

        val mirror = render(values = all.copy(breachedPasswordService = PrivacyNoticeRenderer.serviceLabel("https://pwned.mirror.example/range/"))).passage("breached-password-check")!!
        assertTrue(mirror.text.en.contains("asks pwned.mirror.example whether"), mirror.text.en)
        assertFalse(mirror.text.en.contains("Have I Been Pwned") || mirror.text.en.contains("pwnedpasswords"), "a mirror never names HIBP")

        assertNull(render(active = { it != CapabilityIds.BREACHED_PASSWORD }).passage("breached-password-check"), "the switch off: the passage goes")
    }

    /**
     * The exchange rates' passage (ADR 0108 decision 8, Art. 13 GDPR): it names the provider the instance calls and what
     * the request carries, exactly while its switch is on — the switch is `isActive`, which an unconfigured address keeps
     * off (the slice test drives that one) — and a mirror is named by its host alone.
     */
    @Test
    fun theExchangeRatesNameTheServiceTheInstanceCallsWhileTheirSwitchIsOn() {
        val default = render().passage("exchange-rates")!!
        assertTrue(default.text.en.contains("from Frankfurter (api.frankfurter.dev), at most once a day"), default.text.en)
        assertTrue(default.text.de.contains("von Frankfurter (api.frankfurter.dev)"), default.text.de)
        assertTrue(default.text.en.contains("no currency, no amount") && default.text.en.contains("your IP address and the address of this instance"))
        assertTrue(default.text.en.contains("Art. 6(1)(f) GDPR") && default.text.en.contains("can switch it off"))

        val mirror = render(values = all.copy(exchangeRateService = PrivacyNoticeRenderer.serviceLabel("https://rates.example.org/v2/rates?date={date}")))
            .passage("exchange-rates")!!
        assertTrue(mirror.text.en.contains("from rates.example.org, at most once a day"), mirror.text.en)
        assertFalse(mirror.text.en.contains("Frankfurter") || mirror.text.de.contains("Frankfurter"), "a mirror never names Frankfurter")

        val off = render(active = { it != CapabilityIds.EXPENSES_EXCHANGE_RATES })
        assertNull(off.passage("exchange-rates"), "the switch off: the passage goes")
        assertTrue(off.passage("breached-password-check") != null, "control: the other passage of its kind stays")
    }

    @Test
    fun theOtherBrowserServicesAreListedWhileOneIsOnAndAbsentWhileNoneIs() {
        val on = render().passage("browser-services")!!
        assertTrue(on.text.en.contains("- Maps: tile.example.org") && on.text.de.contains("- Karten: tile.example.org"), on.text.en)
        assertNull(render(values = all.copy(browserServices = emptyList())).passage("browser-services"))
    }

    @Test
    fun noRecipientNamedIsAVisibleGap() {
        val none = render(all.copy(recipients = emptyList())).passage("recipients")!!
        assertTrue(none.text.en.contains("not stated by this operator"), "no recipient: the gap is visible")
        // control: a named recipient renders with what it does and where
        assertTrue(render().passage("recipients")!!.text.en.contains("- Example Hosting GmbH: hosts the server (Germany)"))
    }

    @Test
    fun aRecipientsMissingCountryIsAGap() {
        val noCountry = all.copy(recipients = listOf(NoticeRecipient("Mail AG", RecipientKind.MAIL_RELAY, null, null)))
        assertTrue(render(noCountry).passage("recipients")!!.text.en.contains("- Mail AG: relays the mail (not stated by this operator)"))
    }

    @Test
    fun eachRecipientKindIsNamedInThePagesOwnLanguage() {
        val kinds = RecipientKind.entries.filter { it != RecipientKind.OTHER }
        val r = render(values = all.copy(recipients = kinds.map { NoticeRecipient(it.name, it, null, null) })).passage("recipients")!!
        for (kind in kinds) {
            val label = text.recipientKinds.getValue(kind)
            assertTrue(label.en != label.de, "$kind: a label of its own per language")
            assertTrue(r.text.en.contains("- ${kind.name}: ${label.en} (not stated by this operator)"), r.text.en)
            assertTrue(r.text.de.contains("- ${kind.name}: ${label.de} (von der Betreiberseite nicht angegeben)"), r.text.de)
            assertFalse(r.text.de.contains(label.en), "$kind: the German page carries no English label")
        }
        assertTrue(r.text.de.contains("- HOSTING: hostet den Server"), "the shipped German label: ${r.text.de}")
    }

    @Test
    fun aRecipientsCountryIsNamedInThePagesOwnLanguage() {
        val r = render(values = all.copy(recipients = listOf(
            NoticeRecipient("Example Hosting GmbH", RecipientKind.HOSTING, null, "DE"),
            NoticeRecipient("Mail Inc.", RecipientKind.MAIL_RELAY, null, "US"),
        ))).passage("recipients")!!
        assertTrue(r.text.en.contains("- Example Hosting GmbH: hosts the server (Germany)"), r.text.en)
        assertTrue(r.text.en.contains("- Mail Inc.: relays the mail (United States)"), r.text.en)
        assertTrue(r.text.de.contains("- Example Hosting GmbH: hostet den Server (Deutschland)"), r.text.de)
        assertTrue(r.text.de.contains("- Mail Inc.: versendet die E-Mails (Vereinigte Staaten)"), r.text.de)
        // never the other language's word, and never the bare code
        assertFalse(r.text.de.contains("Germany") || r.text.de.contains("United States"), "no English country on the German page: ${r.text.de}")
        assertFalse(r.text.en.contains("Deutschland") || r.text.en.contains("(DE)"), r.text.en)
    }

    @Test
    fun anOtherRecipientsDescriptionIsShownAsTypedAndQuotedOnBothPages() {
        val r = render(values = all.copy(recipients = listOf(NoticeRecipient("SMS AG", RecipientKind.OTHER, "sends sign-in texts", "FI"))))
            .passage("recipients")!!
        assertTrue(r.text.en.contains("- SMS AG: other — “sends sign-in texts” (Finland)"), r.text.en)
        assertTrue(r.text.de.contains("- SMS AG: Sonstiges — „sends sign-in texts“ (Finnland)"), r.text.de)
    }

    @Test
    fun onlyAnOtherRecipientTakesADescriptionAndItNeedsOne() {
        assertTrue(RecipientKind.OTHER.accepts("sends sign-in texts"))
        assertFalse(RecipientKind.OTHER.accepts(null))
        assertFalse(RecipientKind.OTHER.accepts("  "))
        for (kind in RecipientKind.entries.filter { it != RecipientKind.OTHER }) {
            assertTrue(kind.accepts(null) && kind.accepts(""), "$kind without one")
            assertFalse(kind.accepts("hosts the server"), "$kind with one")
        }
    }

    @Test
    fun theFileIsRefusedWithoutALabelPerKindOrWithTheDescriptionOutsideOther() {
        fun root() = ObjectMapper().readTree(file).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        fun kinds(r: com.fasterxml.jackson.databind.node.ObjectNode) = r.get("recipientKinds") as com.fasterxml.jackson.databind.node.ObjectNode
        PrivacyNoticeText.parse(root()) // control: the shipped file parses

        assertThrows<IllegalArgumentException> { PrivacyNoticeText.parse(root().also { kinds(it).remove("BACKUP") }) }
        assertThrows<IllegalArgumentException> {
            PrivacyNoticeText.parse(root().also { kinds(it).set<com.fasterxml.jackson.databind.JsonNode>("FAX", kinds(it).get("HOSTING")) })
        }
        assertThrows<IllegalArgumentException> {
            PrivacyNoticeText.parse(root().also { (kinds(it).get("HOSTING") as com.fasterxml.jackson.databind.node.ObjectNode).put("de", "{description}") })
        }
        assertThrows<IllegalArgumentException> {
            PrivacyNoticeText.parse(root().also { (kinds(it).get("OTHER") as com.fasterxml.jackson.databind.node.ObjectNode).put("en", "other") })
        }
    }

    @Test
    fun theStorePassageSaysWhereEncryptedContentIs() {
        val bundled = render()
        assertTrue(bundled.passage("storage") != null && bundled.passage("storage-external") == null)
        val external = render(values = all.copy(storeHost = "s3.example.com"))
        assertNull(external.passage("storage"))
        assertTrue(external.passage("storage-external")!!.text.en.contains("at s3.example.com"))
    }

    @Test
    fun anEmptyValueIsAVisibleGap() {
        val unset = all.copy(providerName = null, supervisoryAuthority = " ")
        val r = render(unset)
        assertTrue(r.passage("controller")!!.text.en.contains("- Name: not stated by this operator"))
        assertTrue(r.passage("rights")!!.text.en.contains("competent for the operator: not stated by this operator."), "a blank value is a gap too")
        // control: a set value replaces its gap
        assertTrue(render().passage("controller")!!.text.en.contains("- Name: Example e. V."))
        assertFalse(render().passage("controller")!!.text.en.contains("not stated by this operator"))
    }

    @Test
    fun theOfficerIsOptional() {
        assertFalse(render(all.copy(dataProtectionOfficer = null)).passage("controller")!!.text.en.contains("Data-protection officer"))
        assertTrue(render().passage("controller")!!.text.en.contains("- Data-protection officer: DSB Beispiel GmbH, dsb@example.org"), "control")
    }

    @Test
    fun theErasureAndTheBackupsRenderTheInstancesOwnValues() {
        val r = render(values = all.copy(erasureWindowDays = 14, backupRetentionDays = 1))
        assertTrue(r.passage("erasure")!!.text.en.contains("14 days later your account"), r.passage("erasure")!!.text.en)
        assertTrue(r.passage("backups")!!.text.en.contains("at the latest 1 day after it was made"))
        assertTrue(r.passage("backups")!!.text.de.contains("spätestens 1 Tag nach ihrer Erstellung"))
    }

    @Test
    fun aValuesNewlinesRenderInlineSoATypedValueCannotStartAMarkdownBlock() {
        val r = render(values = all.copy(providerAddress = "Musterstraße 1\n# 12345\n- x", recipients = listOf(NoticeRecipient("A\n## B", RecipientKind.OTHER, "c\n# {storeHost}", "DE"))))
        assertTrue(r.passage("controller")!!.text.en.contains("- Address: Musterstraße 1, # 12345, - x"))
        // the description inline too, and put in as typed: a placeholder in it is text, never a value
        assertTrue(r.passage("recipients")!!.text.en.contains("- A, ## B: other — “c, # {storeHost}” (Germany)"), r.passage("recipients")!!.text.en)
    }

    @Test
    fun bothRightsToObjectAreSetApartAndNothingElseIs() {
        assertEquals(setOf("guest-write-record-objection", "right-to-object"), render().passages.filter { it.separate }.map { it.id }.toSet())
    }

    @Test
    fun theFileIsRefusedWithAPassageIdThatIsNoAnchorOrTwiceTheSame() {
        val root = ObjectMapper().readTree(file).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (root.get("passages").get(1) as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "about-this-notice")
        assertThrows<IllegalArgumentException> { PrivacyNoticeText.parse(root) }
        (root.get("passages").get(1) as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "Not An Anchor")
        assertThrows<IllegalArgumentException> { PrivacyNoticeText.parse(root) }
    }
}
