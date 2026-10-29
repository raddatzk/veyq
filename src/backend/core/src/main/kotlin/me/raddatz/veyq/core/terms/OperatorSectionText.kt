package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** One passage of the section; [record] marks the guest-write record's pointer, served apart. */
data class SectionPassage(val id: String, val record: Boolean, val lines: List<SectionLine>)

/**
 * The operator section's fixed text, as `contracts/terms/operator-section.json` holds it (its `note` says the rules).
 */
data class OperatorSectionText(
    val revision: Int,
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
            gap = SectionTexts.of(root.required("gap")),
            retentionUnit = RetentionUnit.of(root.required("retentionUnit")),
            passages = root.required("passages").map { p ->
                require(!p.has("separate")) { "the section's right to object moved to the privacy notice (Q27): ${p.path("id")}" }
                require(!p.has("part")) { "the section has one form (ADR 0093 § 14), no 'part': ${p.path("id")}" }
                SectionPassage(
                    id = p.required("id").textValue(),
                    record = p.path("record").asBoolean(false),
                    lines = p.required("lines").map { l -> SectionLine(SectionTexts.of(l), l.path("optional").asBoolean(false)) },
                )
            },
        )
    }
}
