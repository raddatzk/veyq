# Applications

veyq is a platform; an **application** is a set of node kinds with its own domain data and client screens, built
on it ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) owns the decision and
the rejected alternatives). German: *Anwendung*. Never "plugin", and never "app": an app is an installable native client
([ADR 0086](../decisions/0086-one-word-per-meaning.md), which also names the second application **Depot**).

**What the platform is.** veyq is a platform for sealed, shareable objects. The core provides:
- **who**: tenants, central users, groups and personal scopes, memberships, invitations, keys, devices;
- **what**: nodes as a tree inside a scope, with parents, order, trash and a content key per node, and a member's
  private **favorites** of any node (a sealed set per user and scope, the heart on every row and header); and
  *attachments*, sealed files on a node that can be uploaded and downloaded, with size and storage quota;
- **with whom**: links with `READ`, `READ_WRITE` or `WRITE_ONLY` access, with password, expiry and consent;
- **out and in**: a member - or a `READ` / `READ_WRITE` share guest - **downloads** any node and everything under it as a
  plaintext ZIP that unpacks as the tree, and a member imports such a ZIP (or the same download unpacked) again
  ([ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md),
  format [`contracts/export/veyq-subtree.schema.json`](../../contracts/export/veyq-subtree.schema.json)). Core owns the
  tree, names (`archiveName`) and the archive; a kind supplies and consumes its content through `KindDescriptor`, beside
  `createEntry`: `exportContent` / `importContent` (Files the file bytes, Memories the media originals, each read through
  the downloading session's own source), `contentSchema` (its application id and content version, so an import lists what
  it cannot read), `validateContent` (its fields against the contract, before anything is written) and `downloadsAsFile`
  (a kind that IS one file - Files' - downloads as itself and is one file in a ZIP) - so no application imports another;
- **in the background**: tasks, rate limits, the admin channel and operator-blindness.

An application supplies the **meaning**: which node kinds exist, what the plaintext means, which rules apply to
structure, attachments and guests, and the user interface. Attachments are core's (`veyq_node_attachment`); what a
verified upload becomes is still each application's (`UploadCompletion`) — Files' is one attachment, Memories' its
media tables. What is built and what is open: the ADR's Consequences.

**Rules** (each is argued in the ADR, not here):

- **First-party, compiled in.** No runtime loading of application code, server or client.
- **Depends on core, never the reverse, never on another application.** Cross-application references are generic
  core object references (scope id + node id) only.
- **Claims its URL route slug once.** A node of an application is addressed `#<path>/<slug>/<name>` in the URL
  fragment ([ADR 0015](../decisions/0015-operator-blind-readable-urls.md), amended 2026-10-11): the application
  claims its slug in the web registry (a second claim throws; a platform word is refused), or declares explicitly
  that it has none — Files is the one that does. The slug is reserved among folder names.
- **Own tables keyed by `node_id`.** The server side is as thin as possible; domain logic lives in the client.
- **Payloads carry a schema version.** Old versions stay readable; migration happens on write.
- **No per-tenant activation.** Created on demand inside a scope. An operator may gate a node kind through the
  capability mechanism ([ADR 0032](../decisions/0032-capability-discovery-version-gating.md)) as a rollout flag.
- **Crypto unchanged.** No application shares a key with another by accident: a node's key is random and wrapped
  under its parent's ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)),
  and a blob's key is sealed bound to its node and its application's role token — so Files needed no new seal purpose
  and no HKDF context (ADR 0079 Decision 9). Memories' existing keys are the exemption.
- **Every concept doc lists the metadata its kinds make operator-visible** (the platform adds only the node
  `kind`, [security-model](../crosscutting/security-model.md)).

| Application | State |
|---|---|
| [Memories](memories.md) | Built. Backend: its own Gradle module (`:apps:memories`, packages `me.raddatz.veyq.memories`), depending on `:core` and never the reverse; web: separated from core by import direction (`web/src/features/memory`, a build-time registry the shell asks, per-kind row factories and dialogs) but not by directory name. What is and is not done: [ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences. |
| [Inventory](inventory.md) | Concept only. |
| [Expenses](expenses.md) | Concept only. |
| [Files](files.md) | Built. Backend `:apps:files` (`me.raddatz.veyq.files`, depending on `:core` only), contract `contracts/api/files/`, web `web/src/features/files`: a member's files (upload, download, replace, rename, delete), files on a shared folder (read, contribute) and the drop box; use-cases under [features/files](../features/files/_index.md). |

## How an application publishes a revisioned list

A list of **records** inside a node (Memories' media; later Expenses' entries, Inventory's items) that an open view
keeps live uses the platform's primitive ([ADR 0033](../decisions/0033-live-media-updates-revision-polling.md), inbox
#410): a per-node revision, a conditional GET, a keyset delta and a delete-gated id set. Core owns the counters
(`veyq_node_items_revision`), the bump, the conditional read and the client loop; the application owns its records
and one query. The steps:

1. **Give each record an `updated_at` (`TIMESTAMP(3)`) and an id.** `updated_at` is set at creation and on every edit;
   the pair `(updated_at, id)` is the keyset cursor. Index what the delta query filters on.
2. **Call core's bump from every writer, in the writer's transaction**: `items.bump(scopeId, nodeId)` for an add, an
   edit or a restore, `items.bump(scopeId, nodeId, deleted = true)` for a removal (it moves both counters). A writer that
   forgets is a live view that never learns of the change, so add the application's writers to a source guard like
   `NodeItemsRevisionBumpsTest`. Take the record rows' locks first, then call `bump` (it locks the node row on a node's
   first change only); never take a lock after it. Erasure needs nothing from the application: core deletes the
   counter row with the node.
3. **Serve the list from a `read`.** `NodeItemsRevisions.read(nodeId, ifNoneMatch, sinceDelete, delta, ids)` answers
   `NotModified` (a `304`, neither lambda runs) or `Changed(revisions, delta, ids)`; `delta` is the application's keyset
   query past `ItemsCursor.parse(since)` and `ids` the node's current record ids (run only when a removal happened
   since). `ItemsETag.of(revision)` is the response's `ETag`, `ItemsETag.parse(header)` the request's.
4. **`$ref` the shared contract pieces** in the list operation: parameters `ItemsIfNoneMatch`, `ItemsSince`,
   `ItemsSinceDelete`, header `ItemsETag`, response `ItemsNotModified` (all in `_common.openapi.yaml`). The response body
   is the application's own schema; by convention `<kind>Revision` (the ETag's number), `deleteRevision` and
   `<kind>Ids` (null unless a removal happened) beside the delta list.
5. **Build the view on `core/items/itemsLive.ts`.** `itemsPollTick({ cursor, fetch, apply })` is one tick for
   `createLivePoller(ITEMS_POLL)`: `cursor` reads the view's revisions and `newestItemsCursor`, `fetch` calls the
   generated client (a `304` is `{ notModified: true }`), `apply` merges with `reconcileItems` and decrypts. A failed
   fetch rejects so the loop backs off.

The scope's *node list* is not this: it has its own revision ([ADR 0081](../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)).

## How an application shows facts in a level's rows and tiles

Core draws every row (a list item) and tile of a folder level, and of the profile page's Memories list, from the
`LevelRow` its application's `list.rows` factory answers per node (`web/src/core/node/levelRow.ts`; ADR 0079 Decision 5,
inbox #407). Under the title sits **one meta line**: the kind's noun (`meta`: "Memory", "File") followed by the node's
**details** (`details`, inbox #418) - the facts only the application can read: Memories' **date and place**, Files'
**size and modified time**, a drop box's people count. The hook:

1. **Answer `details` in the row**: an ordered list of `{ id, text }`, already translated and formatted for the reader's
   locale. The application owns what the facts are, where they are read (the node's unsealed meta, Files' blob listing)
   and how they read; core owns the line - the " · " between them, the wrapping (two lines, then an ellipsis), the
   colour tokens and the reading order (it is plain text inside the item's button, no icon-only content).
2. **Leave out what the node does not have.** A Memory without a place has no `place` entry; a file whose blob carries no
   modified time has no `modified` entry. Never a placeholder ("unknown", "-", "no date yet"); core drops a blank
   entry as well (`levelRowDetails`), so a hole cannot reach the line.
3. **No application is named in core.** The ids are the application's own tokens and only a styling and testing hook
   (`data-detail`). Another surface that lists an application's nodes the same way (the profile page's Memories) builds the
   same details with the application's one builder (`features/memory/memoryRowDetails.ts`), so the two cannot drift.
4. **A share view shows the same details** where the served data carries them (a guest holds the node's key and gets its
   sealed meta unchanged): rights remove functions, not facts, so no guest-only row exists.

The layout is core's and shared by every kind: a list row's visual is a 4.5 x 3 rem tile (6.5 x 4 rem from 521 px), a grid
tile a 16:9 cover over text, and the same item component is drawn on the profile page, whose rows/tiles switch is the level's
own (`ViewModeToggle` over the per-user `useViewMode` preference). A map is not part of this hook: it is an application's own
presentation (`presentation(mode)` beside `row`, named in its kinds' `list.views`), and the profile page offers Memories'
map the same way the level does, the same component over the person's Memories that have a place ([inbox #417](../inbox.md)).

A concept doc is **not** state the code answers to: nothing in it is built, refined into use-cases or planned.
