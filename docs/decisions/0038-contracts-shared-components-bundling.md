# ADR 0038 — A schema the specs share lives once in `_common`, aliased by name; what stays copied is guarded

- Status: Accepted
- Built: yes
- Date: 2026-07-12
- Model: [developer-styleguide](../crosscutting/developer-styleguide.md)

## Context

The specs under `contracts/api/` are independent documents: each generator target consumes one spec and the
models it reaches, and component names collide across specs, so they cannot share one generator module. Left
alone, every spec carries its own copy of what the others carry too — `ErrorResponse` was once in all nine, and
the same enums, path parameters and security scheme repeated across them — and a copy drifts the day one spec
learns a field the others do not.

**Both generators resolve a cross-file `$ref` in place**, measured:

| Consumer | Resolves cross-file `$ref`? | Note |
|---|---|---|
| web (openapi-generator typescript-fetch) | **yes** | swagger-parser resolves relative refs in place |
| backend (openapi-generator kotlin + jaxrs-spec, Quarkus) | **yes** | reads specs in place |

So a shared file costs no bundler and no committed, generated spec. What it can cost is the generated type: how a
`$ref` is written decides what name the generator gives the model, and an inline enum referenced from elsewhere
becomes a named import instead of a nested type. The split below follows that cost, measured against the generated
output, not assumed.

## Decision

**A schema two specs (or two owners, ADR 0079) share is single-sourced in `contracts/api/_common.openapi.yaml`; what
stays copied is held equal by a guard.**

1. **A shared schema lives once in `_common`, aliased by name in each spec that uses it.** A response that `$ref`s
   the shared schema **directly** makes openapi-generator invent a per-operation model name
   (`GetAccountKeyMaterial401Response`), losing the type. So each spec keeps a **local alias** —
   `components.schemas.ErrorResponse: {$ref: '_common…'}` — and every operation references the local name. The
   backend maps every `_common` schema to core's one class (`schemaMappings`, `buildSrc`'s `SpecSets`), so a shared
   schema has one generated type, core's; web additionally emits one unreferenced, unexported inline clone per module
   in the gitignored generated tree, harmless and not worth fighting. The same holds for the shared `parameters`,
   `headers` and `responses` (the revisioned item list of ADR 0033, the scope-access gate's answers). `_common` is a
   `$ref` target, not a spec: the backend's spec glob excludes it and web's `generate:api` does not list it. Its own
   header states the layout and lists what it holds.
2. **`bearerAuth`, a path parameter more than one spec defines, and the shared inline enums stay copied**
   (derivative-kind, membership-role, credential-method, suite-version). Extraction there is not free:
   `bearerAuth`'s `description`s carry real per-domain authorization nuance, and the enums are inline properties,
   so `$ref`-ing them would change the generated type (a nested `Parent.MethodEnum` → a named import) on both
   surfaces. A shared enum that is its own schema, not an inline property (`NodeKind`, `TenantStatus`), is a shared
   schema and lives in `_common` by point 1.
3. **`scripts/validate_contracts_common.py` holds the copies equal** — the single-source guarantee for what has no
   single-source file. An enum that shares **≥1** value with a canonical family but is not exactly it (nor a declared
   intentional variant) is drift; a *full rename* (0 shared values) is indistinguishable from a new domain enum, so no
   value-based check can see it. Its docstring is the list of what it asserts.
4. **Collection-wrapping is left alone** — the bare-array / `{results}` / `{items}` / domain-key mix encodes an
   intentional batch-vs-list distinction, so it is a design choice, not drift; the guard does not police it.

## Consequences

- **What can drift has either one file or one guard.** A shared schema is one definition every spec aliases; a copy
  that stays copied fails the guard until all copies agree.
- **On-disk duplication remains only where extraction costs more than it saves.** A reviewer still sees
  `bearerAuth` in every spec and the enums inline; editing one means editing all copies. Accepted: those change
  rarely, and extracting them changes the generated types.
- **Rejected: one bundled spec per surface.** It was the original alternative when a consumer could not resolve
  external references at all — a bundler plus a committed, generated spec in the tree. With both generators
  resolving refs in place, it buys nothing `_common` does not.
- **Rejected: extract everything.** The inline enums' generated-type change and `bearerAuth`'s lost per-domain
  nuance are the measured reasons. The split re-opens for the rest if a generator gains clean external-enum
  handling.
