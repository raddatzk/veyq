package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * When a passage of the privacy notice renders (the file's `note` says the rules): every condition set must hold.
 * [switch] — a capability active for the host's space; [ifValue] / [ifNoValue] — a value set / not set. There is one
 * form of the notice (ADR 0093 § 14), so no condition reads a mode.
 */
data class NoticeConditions(
    val switch: String? = null,
    val ifValue: String? = null,
    val ifNoValue: String? = null,
)

/** One passage of the notice; [id] is its anchor, a [separate] one a right to object (GDPR Art. 21(4)). */
data class NoticePassage(val id: String, val separate: Boolean, val conditions: NoticeConditions, val lines: List<SectionLine>)

/**
 * The privacy notice's fixed text, as `contracts/terms/privacy-notice.json` holds it (ADR 0035 decision 8).
 */
data class PrivacyNoticeText(
    val revision: Int,
    val gap: SectionTexts,
    val dayUnit: RetentionUnit,
    /** What each [RecipientKind] does, per language; [RecipientKind.OTHER]'s carries [DESCRIPTION], no other one does. */
    val recipientKinds: Map<RecipientKind, SectionTexts>,
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
                require(!p.has("part") && !p.has("about")) { "the notice has one form (ADR 0093 § 14): ${p.path("id")}" }
                NoticePassage(
                    id = p.required("id").textValue().also { require(ANCHOR.matches(it)) { "a passage id is an anchor: $it" } },
                    separate = p.path("separate").asBoolean(false),
                    conditions = NoticeConditions(
                        switch = p.path("switch").textValue(),
                        ifValue = p.path("ifValue").textValue(),
                        ifNoValue = p.path("ifNoValue").textValue(),
                    ),
                    lines = p.required("lines").map { l -> SectionLine(SectionTexts.of(l), l.path("optional").asBoolean(false)) },
                )
            }
            require(passages.map { it.id }.toSet().size == passages.size) { "every passage of the notice has its own id" }
            return PrivacyNoticeText(
                revision = root.required("revision").intValue().also { require(it >= 1) { "the notice's revision starts at 1" } },
                gap = SectionTexts.of(root.required("gap")),
                dayUnit = RetentionUnit.of(root.required("dayUnit")),
                recipientKinds = recipientKinds(root.required("recipientKinds")),
                passages = passages,
            )
        }

        /** Where an OTHER recipient's typed description goes in its kind's label. */
        const val DESCRIPTION = "{description}"

        /** One label per [RecipientKind], no more and no fewer; [DESCRIPTION] in OTHER's, in both languages, and only there. */
        private fun recipientKinds(node: JsonNode): Map<RecipientKind, SectionTexts> {
            val named = node.fieldNames().asSequence().toSet()
            val kinds = RecipientKind.entries.map { it.name }.toSet()
            require(named == kinds) { "the notice names a label for each recipient kind $kinds, not $named" }
            return RecipientKind.entries.associateWith { kind ->
                SectionTexts.of(node.required(kind.name)).also { t ->
                    listOf(t.en, t.de).forEach {
                        require(it.contains(DESCRIPTION) == (kind == RecipientKind.OTHER)) {
                            "only the OTHER recipient kind's label carries $DESCRIPTION: $kind '$it'"
                        }
                    }
                }
            }
        }

        private val ANCHOR = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}
