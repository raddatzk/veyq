package me.raddatz.veyq.core.terms

/**
 * What a recipient does for the instance (ADR 0035 decision 8): a fixed kind the notice names in each language
 * (`recipientKinds` in `contracts/terms/privacy-notice.json`), so the German page never carries the operator's English
 * or the reverse. Only [OTHER] says what it is in the operator's own words, a short description.
 */
enum class RecipientKind {
    HOSTING, OBJECT_STORAGE, MAIL_RELAY, BACKUP, OTHER;

    /** Whether [description] fits this kind: required (non-blank) for [OTHER], absent for every other kind. */
    fun accepts(description: String?): Boolean = (this == OTHER) == !description.isNullOrBlank()
}

/**
 * One recipient the Server Owner named (ADR 0035 decision 8): who, what they do as a [kind], where — [country] an ISO
 * 3166-1 alpha-2 code ([RecipientCountries]), null when not named; [description] an [RecipientKind.OTHER] one's own words.
 */
data class NoticeRecipient(val name: String, val kind: RecipientKind, val description: String?, val country: String?)

/** An outside service a member's browser calls on this host, by its register title and the address it is set to. */
data class BrowserService(val title: SectionTexts, val label: String)

/**
 * The values the privacy notice renders: what the Server Owner typed to be published, and what the running instance
 * does. Each null, blank or empty where none is set.
 */
data class NoticeValues(
    val providerName: String? = null,
    val providerAddress: String? = null,
    val providerEmail: String? = null,
    val providerRepresentative: String? = null,
    /** The address the disclosures name ([ADR 0093] § 11's statement address), else the contact. */
    val privacyContact: String? = null,
    val dataProtectionOfficer: String? = null,
    val supervisoryAuthority: String? = null,
    val recipients: List<NoticeRecipient> = emptyList(),
    /** The guest-write record's N (ADR 0093 § 10). */
    val retentionDays: Int,
    /** B, every capture's longest life (ADR 0056 decision 8). */
    val backupRetentionDays: Int,
    /** The account erasure's window (ADR 0101 decision 3). */
    val erasureWindowDays: Int,
    /** What the breached-password check calls, as [PrivacyNoticeRenderer.serviceLabel] names it; null while it calls nothing. */
    val breachedPasswordService: String? = null,
    val browserServices: List<BrowserService> = emptyList(),
    /** An external object store's host; null for the bundled store. */
    val storeHost: String? = null,
)

data class RenderedPassage(val id: String, val separate: Boolean, val text: SectionTexts)

/** The notice as one host serves it; [draftBanner] null once the text is reviewed. */
data class RenderedNotice(
    val revision: Int,
    val draftBanner: SectionTexts?,
    val passages: List<RenderedPassage>,
)

/**
 * Renders [PrivacyNoticeText] (ADR 0035 decision 8, cr-instance-privacy-notice decision 2) in its one form (ADR 0093
 * § 14). Pure: the values and which capabilities are active on the host come in, markdown passages go out.
 *
 * - A passage renders exactly while its conditions hold ([NoticeConditions]): a passage bound to a switch is there
 *   exactly while [active] says it is on — "it states only what the running instance does".
 * - The placeholder rules are the operator section's ([FixedTextLines]); a list value ({recipients}, {browserServices})
 *   becomes one bullet per item, and an empty one shows the gap.
 * - A passage whose every line was dropped is absent too, rather than a heading over nothing.
 */
object PrivacyNoticeRenderer {

    fun render(text: PrivacyNoticeText, values: NoticeValues, active: (String) -> Boolean): RenderedNotice {
        val passages = text.passages.filter { p ->
            val c = p.conditions
            (c.switch == null || active(c.switch)) &&
                (c.ifValue == null || !empty(c.ifValue, values, text)) &&
                (c.ifNoValue == null || empty(c.ifNoValue, values, text))
        }.mapNotNull { p ->
            val en = renderPassage(text, p, values, en = true)
            val de = renderPassage(text, p, values, en = false)
            if (en.isEmpty() && de.isEmpty()) null else RenderedPassage(p.id, p.separate, SectionTexts(en = en, de = de))
        }
        return RenderedNotice(
            revision = text.revision,
            draftBanner = text.draftBanner.takeIf { text.draft },
            passages = passages,
        )
    }

    /** How the notice names a service at [url]: its known name with the host, or the host alone; null for no host. */
    fun serviceLabel(url: String, known: Map<String, String> = KNOWN): String? {
        val host = runCatching { java.net.URI(url.replace("{", "%7B").replace("}", "%7D")).host }.getOrNull()?.lowercase()
            ?.takeIf { it.isNotBlank() } ?: return null
        return known[host]?.let { "$it ($host)" } ?: host
    }

    /**
     * The services veyq's defaults call, by their own names (the migration that seeds them,
     * `V202610161210__core_external_service.sql`). A mirror the Server Owner sets is named by its host alone, so the
     * notice never names a provider the instance does not call.
     */
    private val KNOWN = mapOf(
        "api.pwnedpasswords.com" to "Have I Been Pwned",
        "nominatim.openstreetmap.org" to "Nominatim, OpenStreetMap Foundation",
        "tile.openstreetmap.org" to "OpenStreetMap Foundation",
    )

    private fun renderPassage(text: PrivacyNoticeText, p: NoticePassage, values: NoticeValues, en: Boolean): String {
        val lines = p.lines.flatMap { line ->
            FixedTextLines.render(line.text.pick(en), line.optional, text.gap.pick(en)) { value(it, values, text, en) }
                ?: emptyList()
        }
        // A passage left with nothing but its heading and blank lines says nothing: drop it whole.
        val said = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        return if (said.isEmpty()) "" else lines.joinToString("\n").trim()
    }

    private fun empty(name: String, values: NoticeValues, text: PrivacyNoticeText): Boolean =
        with(FixedTextLines) { value(name, values, text, en = true).isEmpty() }

    private fun value(name: String, v: NoticeValues, text: PrivacyNoticeText, en: Boolean): FixedValue = when (name) {
        "providerName" -> FixedValue.Text(v.providerName)
        "providerAddress" -> FixedValue.Text(v.providerAddress)
        "providerEmail" -> FixedValue.Text(v.providerEmail)
        "providerRepresentative" -> FixedValue.Text(v.providerRepresentative)
        "privacyContact" -> FixedValue.Text(v.privacyContact)
        "dataProtectionOfficer" -> FixedValue.Text(v.dataProtectionOfficer)
        "supervisoryAuthority" -> FixedValue.Text(v.supervisoryAuthority)
        "recipients" -> FixedValue.Items(v.recipients.map { recipient(it, text, en) })
        "retention" -> FixedValue.Text(text.dayUnit.render(v.retentionDays, en))
        "backupRetention" -> FixedValue.Text(text.dayUnit.render(v.backupRetentionDays, en))
        "erasureWindow" -> FixedValue.Text(text.dayUnit.render(v.erasureWindowDays, en))
        "breachedPasswordService" -> FixedValue.Text(v.breachedPasswordService)
        "browserServices" -> FixedValue.Items(v.browserServices.map { "${it.title.pick(en)}: ${it.label}" })
        "storeHost" -> FixedValue.Text(v.storeHost)
        else -> error("the privacy notice names an unknown value {$name}")
    }

    /**
     * `name: what they do (country)`, what they do being the kind's label in this language — an OTHER one's with the
     * typed description put in, shown as typed on both pages — and the country the code's name in this language too
     * ([RecipientCountries.name]). A country not named shows the gap.
     */
    private fun recipient(r: NoticeRecipient, text: PrivacyNoticeText, en: Boolean): String {
        val name = FixedTextLines.inline(r.name)
        val purpose = text.recipientKinds.getValue(r.kind).pick(en)
            .replace(PrivacyNoticeText.DESCRIPTION, FixedTextLines.inline(r.description.orEmpty()))
        val country = r.country?.let { RecipientCountries.name(it, en) }
        val where = country ?: text.gap.pick(en)
        return "$name: $purpose ($where)"
    }
}
