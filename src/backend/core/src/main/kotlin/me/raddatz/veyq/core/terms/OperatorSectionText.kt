package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Which variant of the operator section a space's terms carry (ADR 0035 decision 6, ADR 0093 § 14). */
enum class SectionVariant { FULL, REDUCED }

/** Where a passage belongs: both variants, the full one only, or the guest-write record's pointer (either variant). */
enum class SectionPart { BOTH, FULL, RECORD }

/** One passage of the section. */
data class SectionPassage(val id: String, val part: SectionPart, val lines: List<SectionLine>)

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
            draftBanner = SectionTexts.of(root.required("draftBanner")),
            gap = SectionTexts.of(root.required("gap")),
            retentionUnit = RetentionUnit.of(root.required("retentionUnit")),
            passages = root.required("passages").map { p ->
                require(!p.has("separate")) { "the section's right to object moved to the privacy notice (Q27): ${p.path("id")}" }
                SectionPassage(
                    id = p.required("id").textValue(),
                    part = SectionPart.valueOf(p.required("part").textValue().uppercase()),
                    lines = p.required("lines").map { l -> SectionLine(SectionTexts.of(l), l.path("optional").asBoolean(false)) },
                )
            },
        )
    }
}
