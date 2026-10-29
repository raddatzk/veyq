package me.raddatz.veyq.buildlogic

import org.gradle.api.GradleException
import java.io.File

/**
 * Restores the `x-field-extra-annotation` the `kotlin` generator drops on a property spelled `oneOf` [a schema, `null`]
 * — a nullable nested object. The generator simplifies that `oneOf` into the referenced type (or a generated copy of it,
 * `ServerOwnerStatementInputFactsForLog`) and loses the property's vendor extensions with it, so the contract's
 * `@field:jakarta.validation.Valid` never reached the field and validation never descended into the object: every bound
 * of a moderation note, a statement's facts and deferral, a Server Owner's statement — eleven properties — went
 * unenforced (the API fuzzing's first run accepted a `sealedFacts` past its `maxLength`; ADR 0091 decision 9).
 * `validate_request_bounds.py` reads the contract, where the annotation is written, so it could not see the loss.
 *
 * Like [ModelCascade] a pass over the generator's OUTPUT, not a copied template, and it **fails the build** when a
 * property it must annotate is not where the generator writes one (`val <name>:` in `<Schema>.kt`), instead of letting
 * the cascade vanish again. A schema whose class this set does not generate (a `_common` schema an application maps to
 * core's) is core's to annotate.
 */
internal object NullableObjectCascade {
    fun apply(modelsDir: File, wanted: List<Triple<String, String, String>>): Int {
        var added = 0
        for ((schema, property, annotation) in wanted) {
            val file = modelsDir.walkTopDown().firstOrNull { it.isFile && it.name == "$schema.kt" } ?: continue
            val lines = file.readText().split("\n").toMutableList()
            val declaration = Regex("""^\s*(?:override\s+)?va[lr]\s+$property:\s""")
            val at = lines.indexOfFirst { declaration.containsMatchIn(it) }
            if (at < 0) throw GradleException("${file.path}: no `val $property:` to carry `$annotation` (NullableObjectCascade.kt)")
            // Already there (a generator that keeps the extension): leave it.
            if ((maxOf(0, at - 3) until at).any { lines[it].contains(annotation) }) continue
            lines.add(at, lines[at].takeWhile { it == ' ' } + annotation)
            file.writeText(lines.joinToString("\n"))
            added++
        }
        return added
    }
}
