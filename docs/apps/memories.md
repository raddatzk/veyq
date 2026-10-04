# Application: Memories

The first application, and the product center today: photos and media, a journal, places, in **Memories**
nested into **Chapters**. Product language **Memory / Chapter** is unchanged; folders organize only
([CLAUDE.md](../../CLAUDE.md)). The code still lives per feature, not as a separable module (see the end).

## What it owns

- **Node kinds `MEMORY` and `CHAPTER`.** `FOLDER` is the platform's (`enum: [FOLDER, MEMORY, CHAPTER]` in
  `contracts/api/content.openapi.yaml`).
- **Keys:** `K_mem` per Memory (the share grain) and `K_blob` per blob under it
  ([ADR 0012](../decisions/0012-content-key-hierarchy.md)). These derive as they always have, the documented
  exemption to the application-id context ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decision 9).
- **Domain data:** media and its derivatives, uploader buckets, a Memory's sealed `wrappedMeta` (date, place,
  cover), item favorites. The media tables key on `node_id` (measured:
  `grep -n "node_id" backend/src/main/resources/db/migration/V11__media.sql backend/src/main/resources/db/migration/V35__media_buckets.sql`).
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

## Separated only in part

Measured 2026-10-04 (`ls backend/src/main/kotlin/com/veyq/app/core backend/src/main/kotlin/com/veyq/app/features`):
the backend's `core` packages hold the node, blob, upload-session and share entities and storage usage, and import
no Memories code; core reaches media and favorites through interfaces they implement. But Memories is not its own
package: the node tree's handler and store (`features.content`), the share handler and store
(`features.sharing`), and `media` are still feature packages, the node `kind` is still the closed
`FOLDER`/`MEMORY`/`CHAPTER` set, and on the web `features/memory` is the application (core imports it nowhere but the composition root and asks a
registry for what to render), though its directory name and five single-claim slots are still Memories-shaped. The
rest of the series is not done; [ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
Consequences lists it.
