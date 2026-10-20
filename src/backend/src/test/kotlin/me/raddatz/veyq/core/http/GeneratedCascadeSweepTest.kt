package me.raddatz.veyq.core.http

import jakarta.validation.Validation
import me.raddatz.veyq.core.testing.MainOutputs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A generated request list cascades into its elements **from the element type** (`List<@Valid Item>`), never from the
 * field (`@Valid` on the `List` itself — Hibernate Validator's `HV000271`, logged at every boot and announced for
 * removal). `buildSrc`'s `ModelCascade` moves the spec's field-level `@Valid` there after generation; this sweep reads
 * the result through Hibernate Validator's own metadata, so it holds the two ways it can go wrong:
 *
 *  - a collection field still carrying the cascade itself (the deprecated form came back);
 *  - the element-type cascades gone (the rewrite, or the compiler's `-Xemit-jvm-type-annotations`, was lost — the type
 *    argument's annotation is then silently not in the bytecode and an invalid element is accepted). The floor below is
 *    the number of spec lists that cascade today; a new one only raises it, so raise the floor with it.
 *
 * Which list OUGHT to cascade is the spec's and `validate_request_bounds.py`'s call (a constrained element type needs
 * the `@Valid` in its `x-field-extra-annotation`); the behavioural proof that an invalid element is refused with
 * `400 invalid_request` is the slice tests' (e.g. `MediaCoreSliceTest.startBlobUploadsValidatesInsideEachItem`).
 */
class GeneratedCascadeSweepTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val models: List<Class<*>> = MainOutputs.classNames()
        .filter { it.contains(".generated.model.") && !it.contains('$') }
        .map { Class.forName(it) }

    @Test
    fun listsCascadeFromTheirElementTypeAndNoCollectionCascadesFromTheField() {
        val onTheField = mutableListOf<String>()
        var cascadedFromElement = 0
        for (model in models) {
            val bean = validator.getConstraintsForClass(model)
            for (field in model.declaredFields) {
                if (!Collection::class.java.isAssignableFrom(field.type)) continue
                val property = bean.getConstraintsForProperty(field.name) ?: continue
                if (property.isCascaded) onTheField += "${model.simpleName}.${field.name}"
                if (property.constrainedContainerElementTypes.any { it.isCascaded }) cascadedFromElement++
            }
        }
        assertEquals(emptyList(), onTheField, "collections carrying @Valid on the field itself (HV000271)")
        assertTrue(
            cascadedFromElement >= 8,
            "only $cascadedFromElement lists cascade from their element type — the generated models were not found, " +
                "or the type-argument annotations are not in the bytecode",
        )
    }
}
