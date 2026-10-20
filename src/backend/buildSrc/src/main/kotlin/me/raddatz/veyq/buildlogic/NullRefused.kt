package me.raddatz.veyq.buildlogic

import org.gradle.api.GradleException
import java.io.File

/**
 * A JSON `null` binds only to a property whose contract allows one. The `kotlin` generator types every optional
 * property nullable (`val x: T? = null`), so Jackson bound an explicit `null` where the contract's type has none —
 * `"overContent": null`, `"sealedName": null`, a `kdfParams` of `null` — and the request went on as if the field were
 * absent (the API fuzzing's first run, cr-api-fuzzing Q5). This pass gives each optional property whose schema allows
 * no `null` (its type, a `oneOf`/`anyOf` branch, the scalar component it `$ref`s; read by
 * [SpecSets.nonNullableOptionals]) core's `NullRefusing` deserializer: an explicit `null` is refused while binding
 * (`InvalidNullException`, a `MismatchedInputException`, so core's mapper answers `400 invalid_request`), an ABSENT
 * property stays absent. A property already trimmed (`TrimmingStringDeserializer`) gets its null-refusing variant — a
 * field carries one `@JsonDeserialize`. The generated clients never send `null` for such a property: their type has
 * none either.
 *
 * Like [ModelCascade] a pass over the generator's OUTPUT, failing the build when a property it must mark is not where
 * the generator writes one. A schema whose class this set does not generate (a `_common` one an application maps to
 * core's) is core's to mark. `MalformedRequestSliceTest.aNullWhereTheContractAllowsNoneIsInvalidRequest` holds it.
 */
internal object NullRefused {
    private const val PACKAGE = "me.raddatz.veyq.core.validation"
    const val ANNOTATION = "@field:com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = $PACKAGE.NullRefusing::class)"
    private const val TRIMMING = "$PACKAGE.TrimmingStringDeserializer::class"
    private const val TRIMMING_REFUSING = "$PACKAGE.NullRefusingTrimmingStringDeserializer::class"

    fun apply(modelsDir: File, wanted: List<Pair<String, String>>): Int {
        val files = modelsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.associateBy { it.name.removeSuffix(".kt") }
        var marked = 0
        wanted.groupBy({ it.first }, { it.second }).forEach { (schema, properties) ->
            val file = files[schema] ?: return@forEach
            val lines = file.readText().split("\n").toMutableList()
            for (property in properties.distinct()) {
                val declaration = Regex("""^\s*(?:override\s+)?va[lr]\s+`?$property`?:\s""")
                val at = lines.indexOfFirst { declaration.containsMatchIn(it) }
                if (at < 0) throw GradleException("${file.path}: no `val $property:` to mark as null-refusing (NullRefused.kt)")
                // The annotation lines of this property: back to the previous property, comment or blank line.
                var top = at
                while (top > 0 && lines[top - 1].trimStart().startsWith("@")) top--
                val trimmed = (top until at).firstOrNull { lines[it].contains(TRIMMING) }
                when {
                    (top until at).any { lines[it].contains(ANNOTATION) || lines[it].contains(TRIMMING_REFUSING) } -> continue
                    trimmed != null -> lines[trimmed] = lines[trimmed].replace(TRIMMING, TRIMMING_REFUSING)
                    (top until at).any { lines[it].contains("JsonDeserialize(") } ->
                        throw GradleException("${file.path}: `$property` has a @JsonDeserialize NullRefused does not know (NullRefused.kt)")
                    else -> lines.add(at, lines[at].takeWhile { it == ' ' } + ANNOTATION)
                }
                marked++
            }
            file.writeText(lines.joinToString("\n"))
        }
        return marked
    }
}
