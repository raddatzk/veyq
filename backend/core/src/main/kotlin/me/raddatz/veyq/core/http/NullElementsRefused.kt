package me.raddatz.veyq.core.http

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/**
 * **A `null` element in a list is a malformed request** — refused while Jackson binds it, as
 * `InvalidNullException`, which [MismatchedInputExceptionMapper] answers with the contract's `400 invalid_request`.
 *
 * No contract declares a nullable list element or map value (measured over `contracts/api` at cr-api-fuzzing: no
 * `items` carries a `null` type, and no schema has `additionalProperties`). Jackson's default binds one anyway, bean
 * validation's `@Valid` cascade skips a null, and the handler's first read of it was a `500` — the API fuzzing's first
 * run found four (`blob-uploads-batch`'s `items`, the moderation epoch's `grants`, the Server Owner's and a registering
 * member's `masterKeyWraps`). Refusing it at the one place every body is bound covers every generated model, core's and
 * each application's, with no annotation per field; `MalformedRequestSliceTest` holds it.
 *
 * It is the mapper's default, so a JSON read that is no request (a stored document, a release manifest) is held to it
 * too — which is the same rule: none of them declares a null element either.
 */
@Singleton
class NullElementsRefused : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        mapper.setDefaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL))
    }
}
