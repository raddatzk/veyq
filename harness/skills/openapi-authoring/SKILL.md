---
name: openapi-authoring
description: 'Create or update OpenAPI YAML spec (spec-first, before implementation). Use when an openAPI spec as contract for a HTTP interface has to be written or updated.'
---

## OpenAPI YAML — spec-first

- **AR-OA-01** The OpenAPI YAML is the contract for every HTTP interface. It is written **before**
  any implementation code — never extracted from code after the fact.

- **AR-OA-02** File layout — one file per concern, all using the same `<suffix>` as spec and arch:
    - `openapi/<suffix>-internal-api.yaml` — interfaces this component **provides** to internal callers
    - `openapi/<suffix>-external-api.yaml` — interfaces this component **provides** to external
      callers, if different from the internal API
    - `openapi/<provider-suffix>-schemas.yaml` — shared domain object schemas per provider;
      included via `$ref` from both the internal and external API files; one file per
      domain-providing service so that consumers can reference only what they need
    - `openapi/consumed-<other-suffix>-api.yaml` — one file per external service this component
      **consumes**; documents what this component expects from that service

  Consumed interface files are the basis for conversation with the teams owning those services.
  If the spec defines convenience APIs (aggregated endpoints, batch operations), generate those
  too — both for provided and consumed interfaces.

- **AR-OA-03** Every path and operation must reference the spec ID that requires it, either in
  the `description` field or as an `x-traces` extension.

- **AR-OA-04** Define pre- and postconditions in the `description` fields: what the caller must
  provide, what the service guarantees in return, and what error responses mean.

- **AR-OA-05** Use `$ref` for shared schemas — no inline schema duplication. Domain object
  schemas live in `<provider-suffix>-schemas.yaml` and are `$ref`-ed from API files.

- **AR-OA-06** Follow REST best practices:
    - API versions go into `Accept` headers — not into the URL path.
    - URI path structure: name the domain object before its identifier — never concatenate
      unidentified IDs. Example: `../vehicle/{vehicleId}/user/{userId}/claims`, not `../v1/{id1}/{id2}/claims`.
    - Use standard HTTP methods and status codes; document error responses explicitly.

- **AR-OA-07** After writing or updating a YAML file, confirm the contract aligns with §3
  (context/interfaces) and the relevant ADRs. If they diverge, the OpenAPI YAML wins — update
  the architecture document to match.

- **AR-OA-08** Before declaring any interface file complete, re-read each acceptance criterion
  of the corresponding spec requirement and verify that every field, header, and parameter
  mentioned in an AC is present in the YAML. Cross-cutting headers expected on many interfaces
  (e.g. vehicle metadata headers, correlation IDs) must be checked by default. A missing field
  that is required by a spec AC is a silent contract gap — it is not caught by build or codegen.

- **AR-OA-09** Before writing any `$ref`, read `aeb-api-specs/README.adoc §Schema references`
  for the correct relative path patterns for same-component, cross-component same-subsystem, and
  cross-subsystem references. Use the `claims-calculation` files as the reference example. Verify
  the resolved path actually exists before declaring the file complete.

- **AR-OA-10** Every OpenAPI YAML file must include a `servers:` block with entries for all
  deployment stages. Read `aeb-infra` for the canonical stage URLs. At minimum: `localhost:{port}`
  for local development, plus the stage-specific URLs. A `servers:` block with only `localhost`
  prevents generated clients from targeting deployed instances and makes staging invisible in
  Swagger UI.

- **AR-OA-11** Every `*-external-api.yaml` must include a `securitySchemes` section declaring the
  GAuth bearer token mechanism, and a `security:` block applied to all external paths. Read the
  CCS architecture §8 or the platform security guidelines for the exact header name and token
  format. A missing security declaration means generated server stubs have no authentication
  filter and API consumers cannot discover the required token header.

- **AR-OA-12** **API versioning via Accept header.** All operations that return a response body
  must declare the Accept header parameter using the AEB custom media type pattern:
  ```
  application/com.aeb.<ResourceName>_<version>+json
  ```
    - `com.aeb` is the fixed vendor prefix.
    - `<ResourceName>` is the camelCase name of the domain object being returned
      (e.g. `deviceClaimsRequest`, `claimsCalculation`).
    - `<version>` is a semver string (e.g. `1.0.0`). Start at `1.0.0` for new resources.
    - Suffix is always `+json`.
    - Example: `application/com.aeb.deviceClaimsRequest_1.0.0+json`
      No version segments in URL paths (see AR-OA-06). Confirm the resource name with the user
      before writing — it must match the domain model agreed in §8.

- **AR-OA-13** **OpenAPI spec ownership and canonical location.** Provided API specs — specs
  that this component *offers to callers* — must live exclusively in
  `aeb-api-specs/openapi/<subsystem>/<component>/`. The component project's `application/openapi/`
  directory must not contain provided API specs. If a copy exists there, remove it or replace it
  with a README pointing to `aeb-api-specs`. Consumed specs — specs owned by other services that
  this component depends on — may be kept locally as a deliberate version pin for WireMock stub
  generation. A local consumed-spec copy is a pinned snapshot, not a canonical source; the
  canonical version lives in the providing service's `aeb-api-specs` entry. At every OpenAPI work
  item, verify this rule is respected. If a provided-spec copy is found in `application/openapi/`,
  flag it and add a sub-task to move and remove the copy.

## Inline completion checklist

- [ ] OpenAPI YAML (if touched): provided, domain, and consumed files present; spec IDs cited; aligns with §3
- [ ] OpenAPI: provided specs confirmed to live in `aeb-api-specs/openapi/<subsystem>/<component>/` only; no copy in
  `application/openapi/` (AR-OA-13)
- [ ] OpenAPI: every spec AC field/header/parameter verified present in YAML (AR-OA-08)
- [ ] OpenAPI: `$ref` patterns verified against `aeb-api-specs/README.adoc` (AR-OA-09)
- [ ] OpenAPI: `servers:` block includes all deployment stages from `aeb-infra` (AR-OA-10)
- [ ] OpenAPI external API: `securitySchemes` + `security:` block declared (AR-OA-11)
- [ ] OpenAPI: Accept header uses `application/com.aeb.<ResourceName>_<version>+json` pattern; resource name confirmed with user;
  no URL version segments (AR-OA-12)
