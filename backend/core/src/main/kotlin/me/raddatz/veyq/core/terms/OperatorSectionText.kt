package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Which variant of the operator section a space's terms carry (ADR 0035 decision 6, ADR 0093 § 14). */
enum class SectionVariant { FULL, REDUCED }

/** Where a passage belongs: both variants, the full one only, or the guest-write record's information (either variant). */
enum class SectionPart { BOTH, FULL, RECORD }

/** One text in each language the section carries. */
data class SectionTexts(val en: String, val de: String)

/** One markdown line; an [optional] line is left out wherever a value it names is empty. */
data class SectionLine(val text: SectionTexts, val optional: Boolean)

/** One passage of the section; a [separate] record passage is the right to object, served apart (GDPR Art. 21(4)). */
data class SectionPassage(val id: String, val part: SectionPart, val separate: Boolean, val lines: List<SectionLine>)

/** N with its unit, per language: the singular for one day, else `{n}` filled in. */
data class RetentionUnit(val one: SectionTexts, val other: SectionTexts)

/**
 * The operator section's fixed text, as `contracts/terms/operator-section.json` holds it (its `note` says the rules).
 * [draft] is true until a qualified legal review; while it is, every rendering starts with [draftBanner].
 */
data class OperatorSectionText(
    val revision: Int,
    val draft: Boolean,
    val draftBanner: SectionTexts,
    val gap: SectionTexts,
    val retentionUnit: RetentionUnit,
    val passages: List<SectionPassage>,
) {
    companion object {
        /** Where the build puts the contract file in the jar (`:core`'s `processResources`). */
        const val RESOURCE = "/veyq/terms/operator-section.json"

        /** The section as the jar carries it; a jar without it is a broken build, so this throws rather than serve none. */
        fun load(mapper: ObjectMapper = ObjectMapper()): OperatorSectionText {
            val stream = OperatorSectionText::class.java.getResourceAsStream(RESOURCE)
                ?: error("the operator section ($RESOURCE) is missing from the build")
            return stream.use { parse(mapper.readTree(it)) }
        }

        fun parse(root: JsonNode): OperatorSectionText = OperatorSectionText(
            revision = root.required("revision").intValue().also { require(it >= 1) { "the section's revision starts at 1" } },
            draft = root.required("draft").booleanValue(),
            draftBanner = texts(root.required("draftBanner")),
            gap = texts(root.required("gap")),
            retentionUnit = root.required("retentionUnit").let {
                RetentionUnit(
                    one = SectionTexts(it.required("en").required("one").textValue(), it.required("de").required("one").textValue()),
                    other = SectionTexts(it.required("en").required("other").textValue(), it.required("de").required("other").textValue()),
                )
            },
            passages = root.required("passages").map { p ->
                SectionPassage(
                    id = p.required("id").textValue(),
                    part = SectionPart.valueOf(p.required("part").textValue().uppercase()),
                    separate = p.path("separate").asBoolean(false),
                    lines = p.required("lines").map { l -> SectionLine(texts(l), l.path("optional").asBoolean(false)) },
                )
            },
        )

        private fun texts(node: JsonNode) = SectionTexts(node.required("en").textValue(), node.required("de").textValue())
    }
}
