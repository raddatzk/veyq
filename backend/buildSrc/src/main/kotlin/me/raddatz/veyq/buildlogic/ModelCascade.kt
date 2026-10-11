package me.raddatz.veyq.buildlogic

import org.gradle.api.GradleException
import java.io.File

/**
 * Moves the bean-validation cascade of a generated list from the FIELD onto the list's ELEMENT type — the one place
 * Hibernate Validator 9 accepts it without `HV000271` ("@Valid on a container is deprecated", logged at every boot,
 * and a removal announced).
 *
 * The contracts write `x-field-extra-annotation: '@field:jakarta.validation.Valid'` on a list property so the cascade
 * reaches each element (a nested list needs its own cascade — backend/CLAUDE.md), and `validate_request_bounds.py`
 * reads exactly that text. The `kotlin` generator, however, writes the field's type itself (`kotlin.collections.List<…>`,
 * its `data_class_req_var` / `data_class_opt_var` templates) and an extension annotates only the field, so
 * `List<@Valid Item>` cannot be said in the spec. This post-pass says it on the generated source:
 *
 *     @field:jakarta.validation.Valid
 *     val items: kotlin.collections.List<Item>
 *  →  val items: kotlin.collections.List<@jakarta.validation.Valid Item>
 *
 * (other annotations on the line stay: `@field:…Size(min = 1) @field:…Valid` keeps its `Size`). It needs the Kotlin
 * compiler flag `-Xemit-jvm-type-annotations` (`veyq.module.gradle.kts`) — without it the type-argument annotation is
 * not in the bytecode and the cascade would silently vanish; the nested-element slice tests go red then.
 *
 * Why a pass and not a copied generator template: a template copy drifts from the generator silently at every bump;
 * this matches the generator's OUTPUT shape and **fails the build** the moment a `@Valid`-annotated collection no
 * longer has that shape (the generator changed how it writes a list type), instead of dropping the cascade. A `@Valid`
 * on a non-collection (a nested object) is left alone — HV cascades into that without a warning.
 */
internal object ModelCascade {
    private const val FIELD_VALID = "@field:jakarta.validation.Valid"
    private const val TYPE_VALID = "@jakarta.validation.Valid"
    private const val DROPPED = "\u0000"

    /** `val name: <type>` of a data-class constructor property, with an optional `override`, `= default` and `,`. */
    private val PROPERTY = Regex("""^(\s*(?:override\s+)?va[lr]\s+\w+:\s*)(.+?)(\s*(?:=.*)?,?)$""")
    private val LIST = Regex("""^kotlin\.collections\.(?:Mutable)?(?:List|Set)<(.+)>(\??)$""")
    private val COLLECTION = Regex("""^(?:kotlin\.collections\.|kotlin\.Array|Array|Map|List|Set|Mutable)""")

    /** Rewrites every model source under [modelsDir]; returns how many fields it moved. */
    fun apply(modelsDir: File): Int {
        var moved = 0
        modelsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
            val (text, n) = rewrite(file.readText(), file.path)
            if (n > 0) {
                file.writeText(text)
                moved += n
            }
        }
        return moved
    }

    internal fun rewrite(source: String, where: String): Pair<String, Int> {
        val lines = source.split("\n").toMutableList()
        var moved = 0
        var annotatedAt = -1 // the line of the pending `@field:…Valid`, or -1
        for (i in lines.indices) {
            val line = lines[i]
            if (line.contains(FIELD_VALID)) {
                annotatedAt = i
                continue
            }
            val property = PROPERTY.matchEntire(line)
            if (property == null) {
                // Annotations / comments between the cascade and its property keep it pending; any other line ends it.
                val t = line.trimStart()
                if (annotatedAt >= 0 && t.isNotEmpty() && !(t.startsWith("@") || t.startsWith("/*") || t.startsWith("*"))) annotatedAt = -1
                continue
            }
            if (annotatedAt < 0) continue
            val (head, type, tail) = property.destructured
            val list = LIST.matchEntire(type)
            if (list == null) {
                if (COLLECTION.containsMatchIn(type)) {
                    throw GradleException(
                        "$where: `$FIELD_VALID` on `$type` — a collection the cascade rewrite does not know. The generator " +
                            "wrote a container type this build cannot move the cascade onto (ModelCascade.kt); teach it the new " +
                            "shape rather than keep the deprecated `@Valid`-on-a-container (HV000271).",
                    )
                }
                annotatedAt = -1 // a nested object: the field cascade is the right one
                continue
            }
            val (element, nullable) = list.destructured
            if (element.contains(TYPE_VALID)) throw GradleException("$where: `$type` already carries a type-level @Valid")
            val marked = if (element.endsWith("?")) "$TYPE_VALID ${element.removeSuffix("?")}?" else "$TYPE_VALID $element"
            lines[i] = head + type.replace("<$element>$nullable", "<$marked>$nullable") + tail
            val kept = lines[annotatedAt].replace(FIELD_VALID, "").trim()
            lines[annotatedAt] = if (kept.isEmpty()) DROPPED else lines[annotatedAt].takeWhile { it == ' ' } + kept
            annotatedAt = -1
            moved++
        }
        return lines.filter { it != DROPPED }.joinToString("\n") to moved
    }
}
