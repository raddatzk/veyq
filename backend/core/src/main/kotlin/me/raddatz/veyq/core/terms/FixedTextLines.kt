package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.JsonNode

/** One text in each language a fixed text carries. */
data class SectionTexts(val en: String, val de: String) {
    fun pick(en: Boolean): String = if (en) this.en else de

    companion object {
        fun of(node: JsonNode) = SectionTexts(node.required("en").textValue(), node.required("de").textValue())
    }
}

/** One markdown line; an [optional] line is left out wherever a value it names is empty. */
data class SectionLine(val text: SectionTexts, val optional: Boolean)

/** A count of days with its unit, per language: the singular for one day, else `{n}` filled in. */
data class RetentionUnit(val one: SectionTexts, val other: SectionTexts) {
    fun render(days: Int, en: Boolean): String = (if (days == 1) one else other).pick(en).replace("{n}", days.toString())

    companion object {
        fun of(node: JsonNode) = RetentionUnit(
            one = SectionTexts(node.required("en").required("one").textValue(), node.required("de").required("one").textValue()),
            other = SectionTexts(node.required("en").required("other").textValue(), node.required("de").required("other").textValue()),
        )
    }
}

/** A value a placeholder names: one line's worth of text, or a list that a line of its own turns into bullets. */
sealed interface FixedValue {
    data class Text(val value: String?) : FixedValue
    data class Items(val items: List<String>) : FixedValue
}

/**
 * The placeholder rules veyq's two fixed texts share — the operator section of the terms ([OperatorSectionRenderer],
 * ADR 0035 decision 6) and the privacy notice ([PrivacyNoticeRenderer], decision 8):
 *
 * - `{name}` is filled from the value of that name; a value is empty when null, blank, or a list with no item.
 * - A line naming an empty value is dropped where it is [SectionLine.optional] and wherever [reduced] (the private
 *   mode's variant: "an unset value is simply absent", owner Q26); otherwise the value renders as the visible gap ("not
 *   stated by this operator").
 * - A list value stands alone on its line and becomes one `- item` bullet per item.
 * - A newline inside a typed value renders as ", ", so a value cannot start a markdown heading or list of its own.
 */
internal object FixedTextLines {
    private val PLACEHOLDER = Regex("""\{(\w+)}""")

    /** The names [line] carries. */
    fun names(line: String): List<String> = PLACEHOLDER.findAll(line).map { it.groupValues[1] }.toList()

    /** [line] rendered, as the markdown lines it becomes; null where it is dropped. */
    fun render(line: String, optional: Boolean, reduced: Boolean, gap: String, value: (String) -> FixedValue): List<String>? {
        val names = names(line)
        val values = names.associateWith { value(it) }
        val missing = values.values.any { it.isEmpty() }
        if (missing && (optional || reduced)) return null
        val items = values.values.filterIsInstance<FixedValue.Items>()
        if (items.isNotEmpty()) {
            require(names.size == 1 && line.trim() == "{${names[0]}}") { "a list value stands alone on its line: $line" }
            val list = items.single().items.map { inline(it) }.filter { it.isNotEmpty() }
            return if (list.isEmpty()) listOf(gap) else list.map { "- $it" }
        }
        return listOf(PLACEHOLDER.replace(line) { m -> (values.getValue(m.groupValues[1]) as FixedValue.Text).value?.let(::inline)?.ifEmpty { null } ?: gap })
    }

    /** Whether [v] counts as unset. */
    fun FixedValue.isEmpty(): Boolean = when (this) {
        is FixedValue.Text -> value?.let(::inline).isNullOrEmpty()
        is FixedValue.Items -> items.none { inline(it).isNotEmpty() }
    }

    fun inline(value: String): String = value.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
}
