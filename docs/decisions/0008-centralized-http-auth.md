# ADR 0008 — Centralized HTTP authentication & annotation-driven authorization

- Status: Accepted
- Date: 2026-06-26

## Context

Through cr-0009 the backend authenticated **per resource, by hand**: each `…Resource` read the
`Authorization` header (`@Context HttpHeaders` + `bearerToken(...)`), passed the raw token to its handler,
and carried a private `unauthorized()` (and `forbidden()`); the handler resolved the token
(`SessionService.resolveUserId`, [ADR 0004](0004-session-tokens.md)) and threaded `Unauthenticated`/`NotOwner`
variants through its sealed outcomes. The Server-Owner check was a separate `ServerOwnerGuard`. Forces that
made this worth changing:

- **No central decision.** Whether a URL needed a token was *implicit* — an endpoint was "public" only
  because its method happened not to call `bearerToken(...)`. Quarkus permits any unsecured endpoint by
  default, so forgetting that call on a new endpoint silently shipped it **open**: the wrong failure direction.
- **Duplication.** Token → userId resolution lived in every handler **and** in `ServerOwnerGuard`; the
  401/403 response body was re-written in every resource.
- **Operator-blind ([invariant]).** Any identity the server builds may carry only userId + role metadata —
  **never** a content key. A management role gates *management* actions; it never confers content readability
  (that stays the client-side scope-key grant).
- **Custom token, not OIDC.** veyq's bearer is an opaque, server-stored session token (ADR 0004), not a JWT —
  so any framework integration must wrap the *existing* resolution, introducing no new trust assumptions.
- **API-first, but authz is enforcement.** The contract (`contracts/api/`) is the source of truth for *shape*;
  *who may call* is a backend policy whose role names should not leak into the published spec.

## Decision

Centralize authentication and authorization on the **Quarkus security SPI**; resources declare intent with
standard annotations.

- **One authentication front door.** A custom `BearerAuthenticationMechanism` (`HttpAuthenticationMechanism`)
  extracts the bearer token; a `SessionTokenIdentityProvider` resolves it **once** into a `SecurityIdentity`
  (principal = `userId`, roles from `veyq_user.server_role`) by wrapping the existing `resolveUserId`
  authenticated-read guard. Resolution runs off the IO thread (blocking JPA).
- **Authorization is an annotation at the resource boundary**, not a hand-written guard: `@PermitAll`
  (public — setup, login, refresh, logout), `@Authenticated` (any valid session — account key-material /
  wraps), `@RolesAllowed(SERVER_OWNER)` (tenant admin). The role constant lives in code (`shared/identity/
  ServerRoles`), shared by the annotation and the IdentityProvider.
- **Default-deny.** `quarkus.security.jaxrs.deny-unannotated-endpoints=true` — an endpoint with no security
  annotation is denied. The annotation is now the **single mandatory place** the token-or-not decision is
  taken, and a future endpoint added without one **fails closed**, not open.
- **Lazy authentication.** `quarkus.http.auth.proactive=false` — authentication runs only when an annotation
  demands it. This keeps `logout` a lenient `@PermitAll` 204-no-op for *any* token (it still reads the header
  manually — the one endpoint that does); a present-but-invalid token must not 401 a public call.
- **One home for the failure body.** Two JAX-RS `ExceptionMapper`s map the Quarkus security exceptions to the
  contract `ErrorResponse` (401 `authentication_failed`, 403 `not_server_owner`) — replacing every
  per-resource `unauthorized()`/`forbidden()`.
- **Handlers are purely fachlich.** They receive an already-resolved `userId` (or, where the caller's identity
  is irrelevant — e.g. tenant admin records no `created_by` — nothing at all) and no longer carry auth
  outcome variants.

**Rejected — declaring authz in the OpenAPI spec** (a `security:` block / generating `@RolesAllowed`): it
would leak the backend role string into the published contract and force a `jaxrs-spec` template fork.
`@RolesAllowed` is a *policy binding*, not a DTO, so hand-placing it on the concrete resource override does
not violate "never hand-write a generated DTO."

**Rejected — keeping per-resource manual guards** (the status quo): fail-open by default, duplicated
resolution, no single place to reason about coverage.

**Rejected — a lightweight `ContainerRequestFilter`**: smaller, but bypasses `SecurityIdentity`/`@RolesAllowed`
and the default-deny machinery — re-inventing what the SPI gives for free.

## Consequences

- **One boundary.** `@Context HttpHeaders`, the per-resource `unauthorized()`/`forbidden()`, and
  `ServerOwnerGuard` are gone; token resolution exists once.
- **Fails closed.** With default-deny, an unannotated endpoint is a 401, never an accidental open door — a
  test (`DefaultDenyTest`) pins this with an unannotated probe.
- **Operator stays blind.** The `SecurityIdentity` carries only userId + role + the opaque token as a
  credential — no content key. The role gates *management*, not content readability.
- **One intentional behaviour change.** Eager `@RolesAllowed`/`@Authenticated` runs **before** `@Valid`, so an
  anonymous or bogus-token request with a malformed body now returns a uniform **401** instead of **400** — a
  strict improvement (no validation detail leaks pre-auth). The malformed-body **400** contract is retained
  for *authenticated* callers (asserted with a real token in the slice tests).
- **Two authz layers, by design.** The annotation is the **coarse HTTP gate** (authenticated? which server
  role?). **Fine-grained, content-level authorization stays a small domain guard** in the handler (scope access —
  the membership boundary first, then the grant / key-holder check ([ADR 0042](0042-http-failure-projection.md));
  containment; tenant-admin over a specific tenant) — those were never about an HTTP role and remain. The map of
  both layers is [cc.security.authorization](../crosscutting/cc.security.authorization.md); see also
  [developer-styleguide](../crosscutting/developer-styleguide.md) §2.
- **Cost.** A custom mechanism + identity provider, a binding to the Quarkus security SPI, and the role name
  living in code rather than the contract. Accepted for the single-boundary, fail-closed posture.
