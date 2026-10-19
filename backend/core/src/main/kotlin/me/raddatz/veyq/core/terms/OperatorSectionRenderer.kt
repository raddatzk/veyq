package me.raddatz.veyq.core.terms

/**
 * The values the operator section renders (ADR 0035 decision 6): what the Server Owner typed to be published, and N.
 * Each null or blank where none is set.
 */
data class SectionValues(
    val providerName: String? = null,
    val providerAddress: String? = null,
    val providerEmail: String? = null,
    val providerRepresentative: String? = null,
    val supervisoryAuthority: String? = null,
    val contactEmail: String? = null,
    val contactLanguages: List<String> = emptyList(),
    /** The address the disclosures name ([ADR 0093] § 11's statement address), else the contact. */
    val privacyContact: String? = null,
    val retentionDays: Int,
)

/** The section rendered for one language each; [record] — the pointer to the privacy notice — is null while the record is off. */
data class RenderedSection(
    val revision: Int,
    val variant: SectionVariant,
    val text: SectionTexts,
    val record: SectionTexts?,
)

/**
 * Renders [OperatorSectionText] (ADR 0035 decision 6, decision 3 of cr-operator-section-of-the-terms). Pure: the
 * variant, the values and whether the guest-write record is on come in, markdown goes out.
 *
 * - A `full` passage renders only in [SectionVariant.FULL]; a `record` passage exactly while [recordOn], in either
 *   variant — the record's pointer follows its switch, never the mode. The record's information itself is the privacy
 *   notice's ([PrivacyNoticeRenderer], Q27 of cr-instance-privacy-notice).
 * - The placeholder rules are the ones both fixed texts share ([FixedTextLines]): a line naming an empty value is
 *   dropped where it is optional and in [SectionVariant.REDUCED], and otherwise shows the visible gap.
 */
object OperatorSectionRenderer {

    fun render(text: OperatorSectionText, variant: SectionVariant, values: SectionValues, recordOn: Boolean): RenderedSection {
        fun part(filter: (SectionPassage) -> Boolean, banner: Boolean): SectionTexts? {
            val passages = text.passages.filter(filter)
            if (passages.isEmpty()) return null
            return SectionTexts(
                en = renderLang(text, passages, variant, values, en = true, banner = banner && text.draft),
                de = renderLang(text, passages, variant, values, en = false, banner = banner && text.draft),
            )
        }
        val main = part({ p -> p.part == SectionPart.BOTH || (p.part == SectionPart.FULL && variant == SectionVariant.FULL) }, banner = true)
            ?: error("the operator section has no passage for every variant")
        val record = if (recordOn) part({ it.part == SectionPart.RECORD }, banner = false) else null
        return RenderedSection(text.revision, variant, main, record)
    }

    private fun renderLang(
        text: OperatorSectionText, passages: List<SectionPassage>, variant: SectionVariant, values: SectionValues,
        en: Boolean, banner: Boolean,
    ): String {
        val blocks = passages.map { p ->
            p.lines.flatMap { line ->
                FixedTextLines.render(line.text.pick(en), line.optional, variant == SectionVariant.REDUCED, text.gap.pick(en)) {
                    FixedValue.Text(value(it, values, text, en))
                } ?: emptyList()
            }.joinToString("\n").trim()
        }.filter { it.isNotEmpty() }
        val body = blocks.joinToString("\n\n")
        return if (banner) text.draftBanner.pick(en) + "\n\n" + body else body
    }

    private fun value(name: String, v: SectionValues, text: OperatorSectionText, en: Boolean): String? = when (name) {
        "providerName" -> v.providerName
        "providerAddress" -> v.providerAddress
        "providerEmail" -> v.providerEmail
        "providerRepresentative" -> v.providerRepresentative
        "supervisoryAuthority" -> v.supervisoryAuthority
        "contactEmail" -> v.contactEmail
        "contactLanguages" -> v.contactLanguages.filter { it.isNotBlank() }.joinToString(", ")
        "privacyContact" -> v.privacyContact
        "retention" -> text.retentionUnit.render(v.retentionDays, en)
        else -> error("the operator section names an unknown value {$name}")
    }
}
