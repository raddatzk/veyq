package me.raddatz.veyq.core.http

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ext.ParamConverter
import jakarta.ws.rs.ext.ParamConverterProvider
import jakarta.ws.rs.ext.Provider
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager
import java.lang.reflect.Type
import java.util.UUID

/**
 * **A parameter of the wrong type or format is refused where the contract says**: a query or header parameter with
 * `400` (which [EmptyBadRequestFilter] gives the contract's `ErrorResponse{code: invalid_request}`), a path segment with
 * the `404` of an id that matches nothing (`IdBoundarySliceTest`; [EmptyNotFoundFilter] gives it a body).
 *
 * Without these the framework converted leniently or refused with the wrong status: a query parameter it could not
 * convert answered an empty `404` (JAX-RS' rule for a query parameter), `java.util.UUID.fromString` accepts
 * `1-1-1-1-1` and versions the id columns refuse ([StorableUuid]), `Boolean.valueOf` reads `revokeLinks=5` as `false`, and an integer took a `+` — the API fuzzing's first
 * run (ADR 0091 decision 9). A converter that throws a `WebApplicationException` has it propagated as it
 * is, so the refusal is the status the contract declares. `MalformedRequestSliceTest` holds the query half.
 *
 * **Where a value came from is read off the request, not off the parameter.** The framework hands a converter the
 * annotations of the implementing method's parameter, and the generated resources carry `@QueryParam`/`@PathParam` on
 * the interface only — measured: every UUID parameter arrived with none. So a value found among the request's query
 * parameters is a query value (`400`), any other a path segment (`404`); a path segment spelled exactly like one of the
 * same request's query values reads as the query's `400`, which a path refusal may also be (owner decision Q1).
 */
@Provider
class StrictParamConverters : ParamConverterProvider {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getConverter(rawType: Class<T>, genericType: Type?, annotations: Array<out Annotation>?): ParamConverter<T>? {
        val parse: ((String) -> Any?)? = when {
            rawType == UUID::class.java -> { s -> StorableUuid.parse(s) }
            rawType == Boolean::class.javaObjectType || rawType == Boolean::class.javaPrimitiveType -> { s ->
                when (s) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            rawType == Int::class.javaObjectType || rawType == Int::class.javaPrimitiveType ->
                { s -> if (INTEGER_SHAPE.matches(s)) s.toIntOrNull() else null }
            rawType == Long::class.javaObjectType || rawType == Long::class.javaPrimitiveType ->
                { s -> if (INTEGER_SHAPE.matches(s)) s.toLongOrNull() else null }
            rawType.isEnum -> { s -> rawType.enumConstants.firstOrNull { (it as Enum<*>).name == s } }
            else -> null
        }
        return parse?.let { p ->
            object : ParamConverter<T> {
                override fun fromString(value: String?): T? = value?.let { (p(it) ?: refuse(it)) as T }
                override fun toString(value: T?): String? = value?.let { if (it is Enum<*>) it.name else it.toString() }
            }
        }
    }

    private companion object {
        /** A JSON integer as text: no `+`, no leading zeros, no whitespace. */
        val INTEGER_SHAPE = Regex("-?(0|[1-9][0-9]*)")

        fun refuse(value: String): Nothing {
            val query = CurrentRequestManager.get()?.serverRequest()?.queryParamsMap.orEmpty()
            throw if (query.values.any { value in it }) BadRequestException() else NotFoundException()
        }
    }
}
