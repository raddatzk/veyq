# Feature: Platform

The **cross-feature substrate** every application stands on, as far as it is a feature of its own: what this instance
serves and which version it speaks, the capability register its operator and tenant owners switch, the node tree, and
the node features core owns for every kind (a cover, export and import, a copy). The platform as a whole — what core owns, what
an application owns and how the two meet — is [Applications](../../apps/README.md).

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the pre-auth manifest is identity-free and
  the same for everyone — operator-blind-safe). What each client surface has is [`docs/coverage/`](../../coverage/README.md).
- **Decisions:** [ADR 0032](../../decisions/0032-capability-discovery-version-gating.md) (capability discovery, the
  `major` gate, capability ≠ permission, advertised and enforced),
  [ADR 0088](../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) (the register as
  a tree, switched by the operator and a tenant owner; an application switched off is read-only, decision 5),
  [ADR 0079](../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (veyq is a platform; what core
  and an application each own).

## Use-cases

- [uc.platform.capability-discovery](use-cases/uc.platform.capability-discovery.md) — **built (web)** (a client
  discovers the instance's `version` + active `capabilities` from the pre-auth `/api/setup`,
  **hard-gates** on a `major` mismatch (forced "update app / update server"), **soft-gates** each feature on its
  capability's presence (graceful hide, never a crash), and an operator can **turn a capability off instance-wide**
  — omitted from the manifest **and** rejected at its endpoint; after sign-in it reads the tenant's effective set.
  [ADR 0032](../../decisions/0032-capability-discovery-version-gating.md))
- [uc.platform.node-cover](use-cases/uc.platform.node-cover.md) — **built (web, backend, contracts)** (inbox #411: any node
  of a kind that opts in — a folder, a drop box, a Memory — carries a **cover**, an image its owner uploads as the node's own
  blob in core's `COVER` slot, written together with the sealed meta that names it in one transaction; core's action, one dialog (choose,
  crop, remove), derive, upload and display; a guest sees it and never sets it.)

- [uc.platform.export-import](use-cases/uc.platform.export-import.md) — **built (web)** (ADR 0082: a member takes any node and
  everything under it out as one plain ZIP, written on the device from decrypted data, and brings such a file back in as new
  content that belongs to the importer; Depot and Memories supply their content through the registry.)
- [uc.platform.copy-node](use-cases/uc.platform.copy-node.md) — **built (web)** (inbox #423, ADR 0082 decision 11: a
  member copies a node and its subtree within a space; the client re-creates it through the export/import hooks and uploads
  every file again, so the server never learns it is a copy.)
- [uc.platform.search](use-cases/uc.platform.search.md) — **built (web)** ([ADR 0125](../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md):
  one search field across every space of the tenant, chips for space, date, place, person and kind, results grouped by
  the open Memory, this space and other spaces; built in the client over the trees and listings it already reads.)
- [uc.platform.keyboard-shortcuts](use-cases/uc.platform.keyboard-shortcuts.md) — **built (web)** (`?` opens an overview of
  every shortcut, core's and each application's, built from what they register.)
- [uc.platform.switch-application-off](use-cases/uc.platform.switch-application-off.md) — **built**
  (an application switched off for the instance or a tenant is
  read-only — every write refused, every read, download and removal kept.)
- [uc.platform.erase-switched-off-application](use-cases/uc.platform.erase-switched-off-application.md) — **built**
  (an application left off for the window, `veyq.application.off.retention`, is erased in the tenant on a day every member is told by banner and mail;
  switched on before the day, nothing is lost.)
- [uc.platform.support-the-project](use-cases/uc.platform.support-the-project.md) — **built (web, backend, contracts)**
  (ADR 0124: a member in the signed-in app and a guest on a share page see a donation banner once, dismissed forever —
  in the browser, and on the account for a member; every page's footer links "Why is veyq secure?" and "Donate".)

## Enablers

- [en.platform.node-tree](enablers/en.platform.node-tree.md) — **built (web + backend)** (core's encrypted node tree,
  for every kind — core's FOLDER and each application's kinds: encrypted names, blinded slugs, a random content key on
  every node wrapped under its parent's, the address, sealed-meta writes, the trash, the guests' tree operations, the
  live refresh and the download; [ADR 0012](../../decisions/0012-content-key-hierarchy.md),
  [ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md))
- [en.platform.capability-register](enablers/en.platform.capability-register.md) — **built** (ADR 0032, ADR 0088: the
  catalog of ids as a tree, the AND along the path and across the instance and tenant levels, the pre-auth and the
  tenant delivery, what each kind of switch does to what is new and what exists, requirements, external services, and
  an application's switch with its erasure window.)
- [en.platform.field-editor](enablers/en.platform.field-editor.md) — **built** (ADR 0093 § 8: the server stores the
  last editor of every sealed field group — name, meta, note, icon — as an opaque `user:{id}` / `share:{identityId}`,
  from the authenticated caller, served to no client; a guest's take-back erases only a placeholder its own identity
  named and no link is rooted on, ADR 0080 decision 6.)
- [en.platform.audio-player](enablers/en.platform.audio-player.md) — **planned** (ADR 0135 decisions 6–8: core's
  one audio player — an application hands it a queue, core plays it shuffled, endless and crossfading in a bar below the
  shell's footer that plays on across pages; it is handed URLs, never a key)
- [en.platform.field-key](enablers/en.platform.field-key.md) — **built** (ADR 0093 § 9: every text or icon field — a
  node's name and meta, a medium's metadata, a bucket's, a guest's or a share's name, a share's note, a member's name
  and picture — seals under `K_field`, derived per write from the key it belongs under, so one key opens one revision
  of one field: what a report will hand over.)

## Deferred

The deprecation lifecycle, "new feature" notices, audience filtering, folding `suites` into the manifest and client
device self-capabilities: [en.platform.capability-register § Decided, not built](enablers/en.platform.capability-register.md#decided-not-built)
and [ADR 0032](../../decisions/0032-capability-discovery-version-gating.md) Consequences.
