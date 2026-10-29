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

/** The section rendered for one language each; [record] is the pointer to the privacy notice, always served. */
data class RenderedSection(
    val revision: Int,
    val text: SectionTexts,
    val record: SectionTexts,
)

/**
 * Renders [OperatorSectionText] (ADR 0035 decision 6, decision 3 of cr-operator-section-of-the-terms). Pure: the values
 * come in, markdown goes out.
 *
 * - There is one form on every instance (ADR 0093 § 14): every passage renders, the `record` one apart as [RenderedSection.record]
 *   — the record is kept wherever a guest may write (§ 10), so its pointer is always there. The record's information itself
 *   is the privacy notice's ([PrivacyNoticeRenderer], Q27 of cr-instance-privacy-notice).
 * - The placeholder rules are the ones both fixed texts share ([FixedTextLines]): a line naming an empty value is
 *   dropped where it is optional, and otherwise shows the visible gap.
 */
object OperatorSectionRenderer {

    fun render(text: OperatorSectionText, values: SectionValues): RenderedSection {
        fun part(record: Boolean): SectionTexts {
            val passages = text.passages.filter { it.record == record }
            check(passages.isNotEmpty()) { "the operator section has no ${if (record) "record" else "main"} passage" }
            return SectionTexts(
                en = renderLang(text, passages, values, en = true),
                de = renderLang(text, passages, values, en = false),
            )
        }
        return RenderedSection(text.revision, part(record = false), part(record = true))
    }

    private fun renderLang(
        text: OperatorSectionText, passages: List<SectionPassage>, values: SectionValues, en: Boolean,
    ): String {
        val blocks = passages.map { p ->
            p.lines.flatMap { line ->
                FixedTextLines.render(line.text.pick(en), line.optional, text.gap.pick(en)) {
                    FixedValue.Text(value(it, values, text, en))
                } ?: emptyList()
            }.joinToString("\n").trim()
        }.filter { it.isNotEmpty() }
        return blocks.joinToString("\n\n")
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
