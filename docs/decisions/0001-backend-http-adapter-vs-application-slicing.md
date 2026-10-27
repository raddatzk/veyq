# ADR 0001 — Backend: resource-shaped HTTP adapters, use-case-sliced application services

- Status: Accepted
- Built: yes
- Date: 2026-06-25
- Model: [developer-styleguide](../crosscutting/developer-styleguide.md)

## Context

veyq is feature/use-case-sliced for cohesion and for the documentation trust chain (use-case →
acceptance → tests). The initial convention paired a thin `…Resource` with a `…Handler` **per
use-case**, and assumed that shape carried everywhere.

That conflates two axes that are genuinely different on the backend:

- **REST endpoints are resource/path-shaped, not use-case-shaped.** A single endpoint can serve several
  use-cases — e.g. `POST /scopes/{id}/nodes` backs "create folder", "create memory" and "create
  chapter".
- **The generated `…Api` interface bundles all operations of an OpenAPI tag.** A JAX-RS resource must
  implement that interface as a unit, so it cannot live inside a single use-case folder.

Forcing such an endpoint into one use-case's `Resource` is wrong, and was the trigger for this ADR.

## Decision

Separate the two axes:

1. **Application/business layer = use-case-sliced.** `<area>/<use-case>/…Handler` under the owner's package
   (`me.raddatz.veyq.core`, or an application's such as `me.raddatz.veyq.memories`). A use-case
   may have several handlers. This is where cohesion and the trust chain live.
2. **HTTP adapter layer = resource-shaped.** One `…Resource` per generated `…Api` interface (OpenAPI
   tag / API resource), deliberately thin. Each operation is a **delegate to the relevant use-case
   handler**; an operation serving N use-cases dispatches to N handlers (or a small dispatcher).

```
core/content/
  ContentResource.kt        # implements ContentApi (generated); thin; delegates per operation
  NodesHandler.kt           # the node-tree use-cases (create/browse/rename/delete)
  NodesStore.kt
memories/itemfavorites/
  ItemFavoritesResource.kt → ItemFavoritesHandler.kt  # an application's use-case, same shape
```

*(Illustrative — the actual content feature is flatter than one folder per use-case; the rule is the
two-layer split, not a fixed sub-folder depth.)*

## Consequences

- **Cross-stack symmetry holds at the use-case layer only.** The adapter shape is necessarily per-stack
  (HTTP resource on the backend, route/view on the web client). This is honest rather than forced.
- **The trust chain maps use-case → handler (+ tests), not → resource.** Resources are wiring and need
  no own acceptance coverage.
- **One indirection** (resource → handler) — which is the intended thin-adapter design anyway.
- **Alternatives rejected:** (a) per-use-case resources implementing split interfaces — contorts API
  tagging into use-cases and still fails the multi-use-case endpoint; (b) pure domain slicing with no
  use-case folders — loses the trust-chain cohesion we rely on.
