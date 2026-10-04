# Applications

veyq is a platform; an **application** is a set of node kinds with its own domain data and client screens, built
on it ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) owns the decision and
the rejected alternatives). German: *Anwendung*. Never "plugin" or "app".

**Rules** (each is argued in the ADR, not here):

- **First-party, compiled in.** No runtime loading of application code, server or client.
- **Depends on core, never the reverse, never on another application.** Cross-application references are generic
  core object references (scope id + node id) only.
- **Own tables keyed by `node_id`.** The server side is as thin as possible; domain logic lives in the client.
- **Payloads carry a schema version.** Old versions stay readable; migration happens on write.
- **No per-tenant activation.** Created on demand inside a scope. An operator may gate a node kind through the
  capability mechanism ([ADR 0032](../decisions/0032-capability-discovery-version-gating.md)) as a rollout flag.
- **Crypto unchanged.** New kinds add seal purposes; a new application derives its keys by HKDF from the scope key
  with its application id as context. Memories' existing keys are the exemption.
- **Every concept doc lists the metadata its kinds make operator-visible** (the platform adds only the node
  `kind`, [security-model](../crosscutting/security-model.md)).

| Application | State |
|---|---|
| [Memories](memories.md) | Built. Backend: core (`com.veyq.app.core`) no longer imports it, but it is not yet its own package or module; web: separated from core by import direction (`web/src/features/memory`, a build-time registry the shell asks) but not by directory name or per-kind slots. What is and is not done: [ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences. |
| [Inventory](inventory.md) | Concept only. |
| [Expenses](expenses.md) | Concept only. |
| [Files](files.md) | Concept only. |

A concept doc is **not** state the code answers to: nothing in it is built, refined into use-cases or planned.
