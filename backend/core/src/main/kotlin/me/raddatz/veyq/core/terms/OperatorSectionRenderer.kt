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

/** The section rendered for one language each; [record] and [objection] are null while the record is off. */
data class RenderedSection(
    val revision: Int,
    val variant: SectionVariant,
    val text: SectionTexts,
    val record: SectionTexts?,
    val objection: SectionTexts?,
)

/**
 * Renders [OperatorSectionText] (ADR 0035 decision 6, decision 3 of cr-operator-section-of-the-terms). Pure: the
 * variant, the values and whether the guest-write record is on come in, markdown goes out.
 *
 * - A `full` passage renders only in [SectionVariant.FULL]; a `record` passage exactly while [recordOn], in either
 *   variant — the record's information follows its switch, never the mode.
 * - A line naming an empty value: dropped where it is optional; dropped in [SectionVariant.REDUCED] outside a record
 *   passage ("one left empty is simply absent"); otherwise the value renders as the visible gap ("not stated by this
 *   operator"). A record passage shows the gap in either variant: GDPR Art. 13 asks the controller and its contact of
 *   whoever records, whatever the mode, so an empty one is shown as missing rather than hidden.
 * - A newline inside a value renders as ", ", so a typed address cannot start a markdown heading or list.
 */
object OperatorSectionRenderer {

    private val PLACEHOLDER = Regex("""\{(\w+)}""")

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
        val record = if (recordOn) part({ it.part == SectionPart.RECORD && !it.separate }, banner = false) else null
        val objection = if (recordOn) part({ it.part == SectionPart.RECORD && it.separate }, banner = false) else null
        return RenderedSection(text.revision, variant, main, record, objection)
    }

    private fun renderLang(
        text: OperatorSectionText, passages: List<SectionPassage>, variant: SectionVariant, values: SectionValues,
        en: Boolean, banner: Boolean,
    ): String {
        val lang = { t: SectionTexts -> if (en) t.en else t.de }
        val blocks = passages.map { p ->
            p.lines.mapNotNull { line -> renderLine(text, lang(line.text), line.optional, p.part, variant, values, en) }
                .joinToString("\n").trim()
        }.filter { it.isNotEmpty() }
        val body = blocks.joinToString("\n\n")
        return if (banner) lang(text.draftBanner) + "\n\n" + body else body
    }

    private fun renderLine(
        text: OperatorSectionText, line: String, optional: Boolean, part: SectionPart, variant: SectionVariant,
        values: SectionValues, en: Boolean,
    ): String? {
        val names = PLACEHOLDER.findAll(line).map { it.groupValues[1] }.toList()
        val filled = names.associateWith { value(it, values, text, en) }
        val missing = filled.values.any { it == null }
        if (missing && (optional || (variant == SectionVariant.REDUCED && part != SectionPart.RECORD))) return null
        val gap = if (en) text.gap.en else text.gap.de
        return PLACEHOLDER.replace(line) { m -> filled.getValue(m.groupValues[1]) ?: gap }
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
        "retention" -> {
            val unit = if (v.retentionDays == 1) text.retentionUnit.one else text.retentionUnit.other
            (if (en) unit.en else unit.de).replace("{n}", v.retentionDays.toString())
        }
        else -> error("the operator section names an unknown value {$name}")
    }?.let { inline(it) }?.takeIf { it.isNotEmpty() }

    private fun inline(value: String): String =
        value.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
}
