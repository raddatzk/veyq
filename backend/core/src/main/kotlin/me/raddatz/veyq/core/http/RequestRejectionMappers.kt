package me.raddatz.veyq.core.http

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.validation.ConstraintViolationException
import jakarta.validation.ElementKind
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * The single home of the boundary's own refusals (inbox #339, #355): a request whose shape the contract refuses
 * answers the contract's `400 ErrorResponse{code: invalid_request}` — the body a handler's [Failures.invalidRequest]
 * writes — whichever layer refused it. Before these, Jackson's refusals answered a bare 400 (Quarkus'
 * `BuiltinMismatchedInputExceptionMapper`: no body in prod, the field and the refused VALUE in dev/test) and bean
 * validation's Quarkus' `Constraint Violation` report (the method, argument and field path, the rule's message): a
 * client reading `code` saw two shapes for one status, and the refused value — often ciphertext or an opaque id —
 * came back in the body.
 *
 * **One mapper per exception type, and one filter for the framework's empty 400s, registered in core** (`@Provider`,
 * discovered from every module's bean archive), so every generated resource — core's and each application's — answers
 * the same with no code of its own. The mappers win over
 * Quarkus' built-ins: the validator's mapper is for the supertype `ValidationException`, so the more specific one here
 * answers first, and for `MismatchedInputException`, the very type of Quarkus' own mapper, an application's mapper
 * takes precedence (measured by `MalformedRequestSliceTest`, Quarkus 3.36.0).
 *
 * **What they never carry:** the refused value, the field's name or path, the rule, a class name or the accepted
 * values of an enum — only the code (no `message`). The contract already tells a client what it may send; the
 * response tells it only that it did not. Keep it that way: an error body is outside the operator-blind boundary's
 * ciphertext and must not become a copy of it (styleguide §7).
 */
private fun invalidRequest(): Response = httpError(Failures.invalidRequest)

/**
 * Jackson refused to bind the body to the generated model with a value it could read but not bind: an unknown enum
 * value (`InvalidFormatException`), a value of the wrong type (a non-uuid for a `format: uuid`, an object for a list).
 * Quarkus' body reader rethrows these as they are; its own mapper for them is the one this replaces.
 */
@Provider
class MismatchedInputExceptionMapper : ExceptionMapper<MismatchedInputException> {
    override fun toResponse(exception: MismatchedInputException): Response = invalidRequest()
}

/**
 * Every `400` the framework answers with no body of its own. Quarkus' body reader wraps every Jackson refusal but the
 * one above in a bare `WebApplicationException(400)` — a body that is no JSON at all (`StreamReadException`), and a
 * missing or null field the Kotlin model holds non-null: no Jackson Kotlin module is on the classpath, so Jackson
 * passes the null and the generated constructor's own null check refuses it (`ValueInstantiationException`) — and
 * so does a parameter the framework cannot convert where JAX-RS says 400 (a header). Every 400 of ours carries an
 * `ErrorResponse`, so an empty one is always the boundary's: this filter gives it the contract's body.
 *
 * **A response filter, deliberately not an `ExceptionMapper<WebApplicationException>`:** a mapper for that type
 * (or any supertype of `NotFoundException`) makes Quarkus REST answer an unmatched route itself instead of passing
 * it on, and the app shell's SPA fallback and the admin channel's unknown-route refusal both live behind that pass
 * (measured: `AppShellSliceTest` and `AdminChannelGateSliceTest` went red with one).
 */
@Provider
class EmptyBadRequestFilter : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (response.status != FailureStatus.BAD_REQUEST.httpStatus || response.hasEntity()) return
        // The body `httpError` writes (the one place an `ErrorResponse` is built, ADR 0042), moved onto this response.
        response.setEntity(invalidRequest().entity, emptyArray(), MediaType.APPLICATION_JSON_TYPE)
    }
}

/**
 * Every `404` the framework answers with no body of its own, inside a matched route: a path parameter that does not
 * convert (a segment that is no UUID — `IdBoundarySliceTest`, [StrictParamConverters]). The contract declares every 404
 * an `ErrorResponse`, so an empty one gets `{code: not_found}` — the generic code, because a segment of the wrong
 * shape names nothing (a handler's own 404 names what it did not find, `scope_not_found` and its siblings, and has a
 * body already). The API fuzzing's first run found 86 operations answering it bare (cr-api-fuzzing, mechanism B).
 *
 * A response filter for the same reason as [EmptyBadRequestFilter]: a request no route matches never reaches JAX-RS,
 * so it stays the app shell's fallback or Vert.x' own 404 — which is why no `/api` route miss carries this body.
 */
@Provider
class EmptyNotFoundFilter : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (response.status != FailureStatus.NOT_FOUND.httpStatus || response.hasEntity()) return
        response.setEntity(httpError(notFound).entity, emptyArray(), MediaType.APPLICATION_JSON_TYPE)
    }

    private companion object {
        val notFound = httpFailure("not_found", FailureStatus.NOT_FOUND)
    }
}

/**
 * Bean validation refused what the spec's `x-field-extra-annotation` declares (`@NotBlank`, `@Size`, `@Pattern`, the
 * `@Valid` cascade, [EachElement]) — the request is malformed → `400 invalid_request`.
 *
 * Only a violation of the **request** is the caller's: one whose path passes through a method parameter (the body,
 * a path or query parameter — `claimTask.arg0.capabilityTags`). A violation with no parameter on its path is the
 * server's own — a return value, or an entity validated as it is persisted — and answers `500 internal_error`
 * without its report, as a defect, never as the caller's fault.
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {
    override fun toResponse(exception: ConstraintViolationException): Response {
        val violations = exception.constraintViolations.orEmpty()
        val ofTheRequest = violations.isNotEmpty() && violations.all { v ->
            v.propertyPath.any { it.kind == ElementKind.PARAMETER || it.kind == ElementKind.CROSS_PARAMETER }
        }
        return if (ofTheRequest) invalidRequest() else httpError(internalError)
    }

    private companion object {
        val internalError = httpFailure("internal_error", FailureStatus.SERVER_ERROR)
    }
}
