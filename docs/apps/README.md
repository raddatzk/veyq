# Applications

veyq is a platform; an **application** is a set of node kinds with its own domain data and client screens, built
on it ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) owns the decision and
the rejected alternatives). German: *Anwendung*. Never "plugin" or "app".

**What the platform is.** veyq is a platform for sealed, shareable objects. The core provides:
- **who**: tenants, central users, groups and personal scopes, memberships, invitations, keys, devices;
- **what**: nodes as a tree inside a scope, with parents, order, trash and a content key per node; and
  *attachments*, sealed files on a node that can be uploaded and downloaded, with size and storage quota;
- **with whom**: links with `READ`, `READ_WRITE` or `WRITE_ONLY` access, with password, expiry and consent;
- **in the background**: tasks, rate limits, the admin channel and operator-blindness.

An application supplies the **meaning**: which node kinds exist, what the plaintext means, which rules apply to
structure, attachments and guests, and the user interface. This is the decided target, not all of it the code:
attachments are not yet a core spec (inbox #327) — what a verified upload becomes is application work today
(`UploadCompletion`). A Files or Storage product is an application on core's attachments, never a dependency of
another application. What is built and what is open: the ADR's Consequences.

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
| [Memories](memories.md) | Built. Backend: its own Gradle module (`:apps:memories`, packages `me.raddatz.veyq.memories`), depending on `:core` and never the reverse; web: separated from core by import direction (`web/src/features/memory`, a build-time registry the shell asks) but not by directory name or per-kind slots. What is and is not done: [ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences. |
| [Inventory](inventory.md) | Concept only. |
| [Expenses](expenses.md) | Concept only. |
| [Files](files.md) | Being built (`cr-files-application`): a skeleton — the Gradle module `:apps:files` (package `me.raddatz.veyq.files`, depending on `:core` only) placing the node kind `FILE` at the root or in a `FOLDER`, and `web/src/features/files` registering the kind; no contract, upload or UI yet. |

A concept doc is **not** state the code answers to: nothing in it is built, refined into use-cases or planned.
