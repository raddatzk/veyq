package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
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

    private val file = File("../contracts/terms/privacy-notice.json")
    private val text = PrivacyNoticeText.parse(ObjectMapper().readTree(file))

    private val all = NoticeValues(
        providerName = "Example e. V.",
        providerAddress = "Musterstraße 1\n12345 Musterstadt",
        providerEmail = "info@example.org",
        providerRepresentative = "Erika Mustermann",
        privacyContact = "privacy@example.org",
        dataProtectionOfficer = "DSB Beispiel GmbH, dsb@example.org",
        supervisoryAuthority = "LfD Musterland",
        recipients = listOf(NoticeRecipient("Example Hosting GmbH", "hosts the server", "Germany")),
        retentionDays = 7,
        backupRetentionDays = 30,
        erasureWindowDays = 30,
        breachedPasswordService = PrivacyNoticeRenderer.serviceLabel("https://api.pwnedpasswords.com/range/"),
        browserServices = listOf(BrowserService(SectionTexts("Maps", "Karten"), "tile.example.org")),
        storeHost = null,
    )

    private val everythingOn: (String) -> Boolean = { true }

    private fun render(
        variant: SectionVariant = SectionVariant.FULL,
        values: NoticeValues = all,
        active: (String) -> Boolean = everythingOn,
    ) = PrivacyNoticeRenderer.render(text, variant, values, active)

    private fun RenderedNotice.passage(id: String) = passages.firstOrNull { it.id == id }

    @Test
    fun theJarCarriesTheContractFileUnchanged() {
        assertEquals(text, PrivacyNoticeText.load(), "the build's copy of the notice differs from contracts/terms/")
    }

    @Test
    fun everyPlaceholderTheFileNamesIsOneTheRendererKnows() {
        for (variant in SectionVariant.entries) {
            for (p in render(variant).passages) {
                assertFalse(Regex("""\{\w+}""").containsMatchIn(p.text.en + p.text.de), "an unfilled placeholder in $variant ${p.id}")
            }
        }
    }

    @Test
    fun anUnknownPlaceholderIsRefusedRatherThanPrinted() {
        val broken = text.copy(passages = text.passages + NoticePassage("x", false, NoticeConditions(), listOf(SectionLine(SectionTexts("{nobody}", "{nobody}"), false))))
        assertThrows<IllegalStateException> { PrivacyNoticeRenderer.render(broken, SectionVariant.FULL, all, everythingOn) }
    }

    @Test
    fun theAnchorsTheClientsLinkExistAndAreStable() {
        // The invite acceptance links `account`, the report dialog `reporter-contact`, a guest's first write
        // `guest-write-record` (web/src/core/privacy/privacyNotice.ts) — renaming one breaks a link nobody sees.
        val ids = text.passages.map { it.id }
        for (anchor in listOf("account", "reporter-contact", "guest-write-record")) assertTrue(anchor in ids, anchor)
    }

    @Test
    fun theRecordsPassageFollowsItsSwitchWithNAndBAndItsRightToObjectApart() {
        val on = render()
        val record = on.passage("guest-write-record")!!
        assertTrue(record.text.en.contains("deleted 7 days after the write") && record.text.de.contains("7 Tage nach dem Schreibvorgang"), record.text.en)
        assertTrue(record.text.en.contains("up to 30 days longer"), "N + B: a backup made another way, by B")
        val objection = on.passage("guest-write-record-objection")!!
        assertTrue(objection.separate, "the right to object is set apart (Art. 21(4))")
        assertTrue(objection.text.en.contains("guest id") && objection.text.en.contains("does not stop a later write from being recorded"))
        assertFalse(record.text.en.contains("Your right to object"), "never inside the record's own passage")

        val off = render(active = { it != GuestWriteRecords.SWITCH })
        assertNull(off.passage("guest-write-record"), "the switch off: the passage goes")
        assertNull(off.passage("guest-write-record-objection"), "and its right to object with it")
        assertTrue(off.passage("right-to-object") != null, "control: the general right to object stays")
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

    @Test
    fun theOtherBrowserServicesAreListedWhileOneIsOnAndAbsentWhileNoneIs() {
        val on = render().passage("browser-services")!!
        assertTrue(on.text.en.contains("- Maps: tile.example.org") && on.text.de.contains("- Karten: tile.example.org"), on.text.en)
        assertNull(render(values = all.copy(browserServices = emptyList())).passage("browser-services"))
    }

    @Test
    fun theRecipientsFollowTheModeWhenNoneIsNamed() {
        val none = all.copy(recipients = emptyList())
        assertNull(render(SectionVariant.REDUCED, none).passage("recipients"), "private, no recipient: the passage is absent")
        val full = render(SectionVariant.FULL, none).passage("recipients")!!
        assertTrue(full.text.en.contains("not stated by this operator"), "organisation, no recipient: the gap is visible")
        // control: a named recipient renders in both, with what it does and where
        for (variant in SectionVariant.entries) {
            assertTrue(render(variant).passage("recipients")!!.text.en.contains("- Example Hosting GmbH: hosts the server (Germany)"), "$variant")
        }
    }

    @Test
    fun aRecipientsMissingCountryIsAGapInTheFullVariantAndLeftOutInTheReducedOne() {
        val noCountry = all.copy(recipients = listOf(NoticeRecipient("Mail AG", "relays the mail", null)))
        assertTrue(render(SectionVariant.FULL, noCountry).passage("recipients")!!.text.en.contains("- Mail AG: relays the mail (not stated by this operator)"))
        val reduced = render(SectionVariant.REDUCED, noCountry).passage("recipients")!!.text.en
        assertTrue(reduced.contains("- Mail AG: relays the mail") && !reduced.contains("not stated"), reduced)
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
    fun anEmptyValueIsAVisibleGapInTheFullVariantAndAbsentInTheReducedOne() {
        val unset = all.copy(providerName = null, supervisoryAuthority = " ")
        val full = render(SectionVariant.FULL, unset)
        assertTrue(full.passage("controller")!!.text.en.contains("- Name: not stated by this operator"))
        assertTrue(full.passage("rights")!!.text.en.contains("competent for the operator: not stated by this operator."), "a blank value is a gap too")
        val reduced = render(SectionVariant.REDUCED, unset)
        assertFalse(reduced.passage("controller")!!.text.en.contains("- Name:"), "reduced: the line goes")
        assertFalse(reduced.passages.any { it.text.en.contains("not stated by this operator") }, "reduced: no gap anywhere")
        // control: set values render in both
        assertTrue(render(SectionVariant.REDUCED).passage("controller")!!.text.en.contains("- Name: Example e. V."))
    }

    @Test
    fun theOfficerIsOptionalInBothVariants() {
        for (variant in SectionVariant.entries) {
            assertFalse(render(variant, all.copy(dataProtectionOfficer = null)).passage("controller")!!.text.en.contains("Data-protection officer"), "$variant")
            assertTrue(render(variant).passage("controller")!!.text.en.contains("- Data-protection officer: DSB Beispiel GmbH, dsb@example.org"), "control, $variant")
        }
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
        val r = render(values = all.copy(providerAddress = "Musterstraße 1\n# 12345\n- x", recipients = listOf(NoticeRecipient("A\n## B", "c", "D"))))
        assertTrue(r.passage("controller")!!.text.en.contains("- Address: Musterstraße 1, # 12345, - x"))
        assertTrue(r.passage("recipients")!!.text.en.contains("- A, ## B: c (D)"))
    }

    @Test
    fun aDraftSaysSoAndOnlyWhileItIsOne() {
        assertTrue(text.draft, "the notice is a draft until a qualified legal review")
        assertTrue(render().draftBanner!!.en.startsWith("*Draft:") && render().draftBanner!!.de.startsWith("*Entwurf:"))
        assertNull(PrivacyNoticeRenderer.render(text.copy(draft = false), SectionVariant.FULL, all, everythingOn).draftBanner, "control: no banner once reviewed")
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
