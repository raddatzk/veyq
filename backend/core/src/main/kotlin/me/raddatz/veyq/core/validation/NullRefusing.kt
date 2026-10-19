package me.raddatz.veyq.core.validation

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.PropertyName
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.exc.InvalidNullException

/**
 * Refuses an explicit JSON `null` for a property whose contract allows none, and leaves an ABSENT one absent. The build
 * puts it on every optional property of that kind (`buildSrc`'s `NullRefused`, wired as
 * `@field:JsonDeserialize(using = NullRefusing::class)`); the value itself is read by the deserializer Jackson would
 * have used. Jackson's own `@JsonSetter(nulls = FAIL)` cannot do it here: a generated model binds through its
 * constructor, and Jackson asks a missing constructor property for the same "null value" an explicit `null` gets — so
 * it refused the absent property too. `getAbsentValue` is what tells the two apart.
 *
 * The refusal is an `InvalidNullException` (a `MismatchedInputException`), which core's mapper answers with the
 * contract's `400 invalid_request` (cr-api-fuzzing Q5).
 */
class NullRefusing(
    private val delegate: JsonDeserializer<Any>? = null,
    private val name: PropertyName? = null,
    private val type: JavaType? = null,
) : JsonDeserializer<Any?>(), ContextualDeserializer {

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): JsonDeserializer<*> {
        val type = property?.type ?: ctxt.contextualType
        return NullRefusing(ctxt.findContextualValueDeserializer(type, property), property?.fullName, type)
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Any? =
        checkNotNull(delegate) { "NullRefusing used without its property" }.deserialize(p, ctxt)

    override fun getNullValue(ctxt: DeserializationContext): Any? = throw InvalidNullException.from(ctxt, name, type)

    override fun getAbsentValue(ctxt: DeserializationContext): Any? = null
}

/** [TrimmingStringDeserializer] for a property that also refuses `null` (one `@JsonDeserialize` per field). */
class NullRefusingTrimmingStringDeserializer : TrimmingStringDeserializer() {
    override fun getNullValue(ctxt: DeserializationContext): String =
        throw InvalidNullException.from(ctxt, null, ctxt.constructType(String::class.java))

    override fun getAbsentValue(ctxt: DeserializationContext): String? = null
}
