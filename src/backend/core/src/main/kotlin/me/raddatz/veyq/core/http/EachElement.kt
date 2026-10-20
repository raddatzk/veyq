package me.raddatz.veyq.core.http

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * The bounds of every element of a request list of scalars — what a spec's `items: {maxLength, minLength, pattern}`
 * declares, written beside the list as `@field:me.raddatz.veyq.core.http.EachElement(...)` in its
 * `x-field-extra-annotation` (inbox #354).
 *
 * It exists because the field annotation is the only lever the spec has, and the `kotlin` generator types the field
 * `kotlin.collections.List<kotlin.String>` itself: a bound on the type argument (`List<@Size(max = 64) String>`) is
 * not something it can emit. So the list's own annotation carries its elements' bounds, and this validator applies
 * them to each element — every one, so a list is refused by its first bad element as a field would be.
 *
 * A null element is refused (the contract's `items` admits none, and the Kotlin type's non-null element is not
 * checked at runtime); a null list is not this annotation's to judge — the list's presence is its `@NotNull` /
 * `@NotEmpty`. `validate_request_bounds.py` holds the arguments to the schema's `items` value for value, both ways.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [EachElementValidator::class])
annotation class EachElement(
    val minLength: Int = 0,
    val maxLength: Int = Int.MAX_VALUE,
    /** Matched against the whole element, as `@Pattern` matches (anchors optional); empty = no pattern. */
    val pattern: String = "",
    val message: String = "an element is outside its bounds",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class EachElementValidator : ConstraintValidator<EachElement, Collection<*>?> {
    private var minLength = 0
    private var maxLength = Int.MAX_VALUE
    private var pattern: Regex? = null

    override fun initialize(annotation: EachElement) {
        minLength = annotation.minLength
        maxLength = annotation.maxLength
        pattern = annotation.pattern.takeIf { it.isNotEmpty() }?.let(::Regex)
    }

    override fun isValid(value: Collection<*>?, context: ConstraintValidatorContext): Boolean =
        value == null || value.all { element ->
            element is String && element.length in minLength..maxLength && pattern?.matches(element) != false
        }
}
