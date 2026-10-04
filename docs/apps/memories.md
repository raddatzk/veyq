# Application: Memories

The first application, and the product center today: photos and media, a journal, places, in **Memories**
nested into **Chapters**. Product language **Memory / Chapter** is unchanged; folders organize only
([CLAUDE.md](../../CLAUDE.md)). The backend half is a Gradle module of its own; the web half is separated by import
direction only (see the end).

## What it owns

- **Node kinds `MEMORY` and `CHAPTER`.** `FOLDER` is the platform's (`enum: [FOLDER, MEMORY, CHAPTER]`, the
  `NodeKind` of `contracts/api/_common.openapi.yaml`, which core's specs alias).
- **Contracts:** `contracts/api/memories/` (`media.openapi.yaml`, `favorites.openapi.yaml`), generated into
  `com.veyq.memories.generated`; the blob transfer an upload runs on is core's `contracts/api/blob.openapi.yaml`.
- **Keys:** `K_mem` per Memory (the share grain) and `K_blob` per blob under it
  ([ADR 0012](../decisions/0012-content-key-hierarchy.md)). These derive as they always have, the documented
  exemption to the application-id context ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decision 9).
- **Domain data:** media and its derivatives, uploader buckets, a Memory's sealed `wrappedMeta` (date, place,
  cover), item favorites. The media tables key on `node_id` (measured:
  `grep -n "node_id" backend/apps/memories/src/main/resources/db/migration/V*__memories_baseline.sql`).
- **Screens and behaviour:** gallery and lightbox, upload with client-generated derivatives, covers, date and
  place curation, the map, live updates. Their state is the use-cases and enablers under
  [`docs/features/memory/`](../features/memory/_index.md); this page points at them and does not restate them.

## What it uses from the platform

| Platform primitive | Where it is described |
|---|---|
| The node tree (create, rename, move, slug uniqueness) | [en.memory.tree](../features/memory/enablers/en.memory.tree.md), today filed under the memory feature but a platform concern |
| Node trash | [ADR 0061](../decisions/0061-node-deletion-reversible-node-trash.md) |
| Scopes, memberships, grants, key epochs | [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md) |
| Public shares (viewer, contributor, uploader) | [`docs/features/sharing/`](../features/sharing/_index.md) |
| Blobs, direct-to-store upload, storage quota | [ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md), [ADR 0073](../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) |
| Seals bound to their object | [ADR 0072](../decisions/0072-a-seal-binds-its-object.md) |
| Concurrent writes to a sealed blob | [ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md) |

## How far it is separated

Measured 2026-10-05 (`ls backend/core/src/main/kotlin/com/veyq/core backend/apps/memories/src/main/kotlin/com/veyq/memories`):
**the backend is.** Memories is the Gradle module `:apps:memories` (`backend/apps/memories`), depending on `:core`
and never the reverse; the root `backend/` composes both into the one fast-jar. Every class lives in its owner's
package — the platform in `com.veyq.core` (the node tree in `core.content`/`core.node`, sharing in `core.sharing`),
Memories in `com.veyq.memories` (`media`, `favorites`, `itemfavorites`, `node`) — and core reaches Memories only
through the interfaces Memories implements: it attaches its media through `UploadCompletion`, measures a link's
contributed blobs through `UploadAdmission`, places its `MEMORY` and `CHAPTER` through `NodeKindRules` (core places
only `FOLDER`) and takes guest writes through `ShareGuestWrites`. Memories' tests run in `:apps:memories`, beside the
core tests that need an application at runtime; `:core`'s own run with no application at all. **The web is separated
by import direction only**: `features/memory` is the application (core imports it nowhere but the composition root
and asks a registry for what to render), though its directory name and five single-claim slots are still
Memories-shaped. What core still knows of Memories, and what is open on both sides,
[ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences lists.
