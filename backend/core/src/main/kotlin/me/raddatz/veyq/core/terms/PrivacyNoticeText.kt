package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * When a passage of the privacy notice renders (the file's `note` says the rules): every condition set must hold.
 * [fullOnly] — the club, organisation or company mode only; [switch] — a capability active for the host's space;
 * [ifValue] / [ifNoValue] — a value set / not set; [about] — a value whose absence drops the passage in the reduced
 * variant only (the full one shows its gap).
 */
data class NoticeConditions(
    val fullOnly: Boolean = false,
    val switch: String? = null,
    val ifValue: String? = null,
    val ifNoValue: String? = null,
    val about: String? = null,
)

/** One passage of the notice; [id] is its anchor, a [separate] one a right to object (GDPR Art. 21(4)). */
data class NoticePassage(val id: String, val separate: Boolean, val conditions: NoticeConditions, val lines: List<SectionLine>)

/**
 * The privacy notice's fixed text, as `contracts/terms/privacy-notice.json` holds it (ADR 0035 decision 8). [draft] is
 * true until a qualified legal review; while it is, the rendering carries [draftBanner].
 */
data class PrivacyNoticeText(
    val revision: Int,
    val draft: Boolean,
    val draftBanner: SectionTexts,
    val gap: SectionTexts,
    val dayUnit: RetentionUnit,
    val passages: List<NoticePassage>,
) {
    companion object {
        /** Where the build puts the contract file in the jar (`:core`'s `processResources`). */
        const val RESOURCE = "/veyq/terms/privacy-notice.json"

        /** The notice as the jar carries it; a jar without it is a broken build, so this throws rather than serve none. */
        fun load(mapper: ObjectMapper = ObjectMapper()): PrivacyNoticeText {
            val stream = PrivacyNoticeText::class.java.getResourceAsStream(RESOURCE)
                ?: error("the privacy notice ($RESOURCE) is missing from the build")
            return stream.use { parse(mapper.readTree(it)) }
        }

        fun parse(root: JsonNode): PrivacyNoticeText {
            val passages = root.required("passages").map { p ->
                val part = p.required("part").textValue()
                require(part == "both" || part == "full") { "a notice passage is 'both' or 'full', not '$part'" }
                NoticePassage(
                    id = p.required("id").textValue().also { require(ANCHOR.matches(it)) { "a passage id is an anchor: $it" } },
                    separate = p.path("separate").asBoolean(false),
                    conditions = NoticeConditions(
                        fullOnly = part == "full",
                        switch = p.path("switch").textValue(),
                        ifValue = p.path("ifValue").textValue(),
                        ifNoValue = p.path("ifNoValue").textValue(),
                        about = p.path("about").textValue(),
                    ),
                    lines = p.required("lines").map { l -> SectionLine(SectionTexts.of(l), l.path("optional").asBoolean(false)) },
                )
            }
            require(passages.map { it.id }.toSet().size == passages.size) { "every passage of the notice has its own id" }
            return PrivacyNoticeText(
                revision = root.required("revision").intValue().also { require(it >= 1) { "the notice's revision starts at 1" } },
                draft = root.required("draft").booleanValue(),
                draftBanner = SectionTexts.of(root.required("draftBanner")),
                gap = SectionTexts.of(root.required("gap")),
                dayUnit = RetentionUnit.of(root.required("dayUnit")),
                passages = passages,
            )
        }

        private val ANCHOR = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}
