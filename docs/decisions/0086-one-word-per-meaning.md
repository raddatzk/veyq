# ADR 0086 — One word per meaning: platform, application, app, capability, rights, revision, format

- Status: Accepted
- Built: yes
- Date: 2026-10-15
- Model: [Applications](../apps/README.md)

## Context

Since veyq became a platform with applications on it
([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)), several words carry more than one
meaning, and a native client will add more:

- **"Capability"** means three things. The instance-wide, pre-auth switch on `/api/setup`
  ([ADR 0032](0032-capability-discovery-version-gating.md)). A worker's capability tags
  ([ADR 0021](0021-operator-blind-background-tasks.md)). And the per-view functions `folderCapabilities`,
  `memoryCapabilities` and `filesCapabilities` (`web/src/core/access.ts:13`), which answer *"may this viewer do X
  here"*. That last one is a permission, and ADR 0032 §3 names exactly that conflation as its main modelling risk.
- **"Version"** means three things. The compatibility major ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)).
  A sealed payload's schema per node kind (ADR 0079 decision 7). And a node's concurrency counter `metaVersion`
  ([ADR 0077](0077-a-write-carries-the-version-it-read.md)), which already sits beside `mediaRevision` and a
  scope's tree revision ([ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)) for the same
  kind of counter.
- **"App"** is banned outright ("never plugin or app"), so that nobody calls an application an app. Once store
  apps exist the word is unavoidable, and a ban that has to be broken teaches nothing.
- **"Files"**, the second application's name, collides with Apple's own *Files* (*Dateien*). "Open it in Files" is
  ambiguous on every iPhone.

## Decision

One meaning per word, in docs, code and the wiki (EN+DE):

| Word (de) | Means | Never for |
|---|---|---|
| **veyq**, the **platform** (*Plattform*) | core: who, what, with whom (ADR 0079) | an application |
| **application** (*Anwendung*) | a set of node kinds with its own data and screens on the platform: Memories, Depot | "plugin"; an installable client |
| **app** (*App*) | an installable native client only. The web client is the **web client** | an application |
| **capability** | an instance-wide, pre-auth switch (ADR 0032) | anything per viewer |
| **rights** (*Rechte*) | what a view offers this viewer here, the client's projection of a permission: `memoryRights`, `folderRights`, `depotRights` | an instance switch |
| **version** | compatibility only: the major (ADR 0085), a crypto suite | a concurrency counter |
| **revision** | a concurrency or change counter: `metaRevision` (was `metaVersion`), `mediaRevision`, a tree revision, the edition of a text that changes (the terms' `termsRevision`) | compatibility |
| **format** | a sealed payload's schema, per node kind, readable forever (ADR 0079 decision 7) | the platform's major |
| **history** (*Verlauf*) | reserved for a future earlier-contents-of-a-file feature | "versions" of a file |
| **`K_node`** (identifier `kNode`) | the random content key every node carries — a Memory, a Chapter, a folder, a Depot file or drop box — wrapped under its parent's key (ADR 0012, ADR 0080) | `K_mem`, which named it when only a Memory had a key; the wire labels (seal purposes, derivation info strings) never carried the name and do not change |

**Depot is the second application's name** (*Depot* in English and German alike). It says "where I put things
safely" without promising more. Its code id follows the product name (`depot`), so the product never has two
names. The worker's capability tags keep their name: they also mean "what this side can do", they never reach a
client, and they live in the database baseline, so a rename would cost a migration for no reader.

**Expenses is the third application's name** (*Ausgaben*), code id `expenses`; what a person creates in it is a
**Pot** (*Kasse*). Never "group", which is the `GROUP` scope, and never "tab", which is a UI tab
([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 1).

Where the name *Files* sits today decides what moves, and the test is Memories' precedent: the application's id
names its directories, module, package, i18n namespace and app-prefixed seams (`MemoriesNodeKinds`), while its API
is named by **resource** (`media`, `/api/scopes/{scopeId}/media`). So for Depot:
- **Moves to `depot`:** `contracts/api/files/` and the Gradle module `:apps:files` with its package
  `me.raddatz.veyq.files` (one commit — the build derives each from the other), `src/web/src/features/files`, the
  i18n namespace, every `Files…` seam that names the application, the stored node kind `FILES_DROPBOX` →
  `DEPOT_DROPBOX` (it carries the application's prefix and the operator reads it in `veyq_node.kind`), and the
  export ids (`files.file` / `files.drop-box` → `depot.file` / `depot.drop-box`, application id `files` → `depot`),
  with no alias for old archives: before V1 there are none to read. The application's tab in a level is labelled
  *Depot*.
- **Stays, because it is the generic word *file*:** the node kind `FILE`, the blob role `FILE_CONTENT` (it is
  bound into every sealed wrap's context), the API's tags, paths and schema names (`files`, `/files`, `File…`),
  and the UI's *file* / *Datei* wherever it means a file rather than the application.
- **Has nothing to move:** Depot claims no route segment (ADR 0015), so there is no slug.

**The shape of the apps** (owner's direction, 2026-10-15; native work is parked until the web V1 stands): the
**veyq app** is core plus Depot, and each further application is its own app, reached from the others by deep
link. Shares open in the browser with an "open in the app" banner. The decisions this needs (one sign-in shared
across apps, how a link's key reaches an app safely) belong to the native client's own ADR, not to this one.

## Consequences

- **The word rules bind now**, and this change updates where they are written: the root `CLAUDE.md` and
  [`docs/apps/README.md`](../apps/README.md).
- **Built (2026-10-15):** Files → Depot, as the Depot paragraph above sorts it.
- **Built (2026-10-15, inbox #437):** `…Capabilities` → `…Rights`, web only: `folderRights`, `memoryRights`,
  `depotRights`, `favoriteRights`, `coverRights`, `sourceRights`, their types, the `RightsTarget` they are asked
  about, and the files that hold them (`rights.ts`). The instance switch keeps its name everywhere (`hasCapability`,
  `useCapabilities`, the `CORE_*` ids, the catalog). Two other senses of the word stay too, because neither is a
  per-viewer function: a **capability token** (a bearer secret such as a contributor's, `cc.security.wire-assertion`)
  and the worker's capability tags above.
- **Built (2026-10-16, inbox #437):** the node counters are revisions, on the wire, in the database and in the
  clients: `metaRevision` (was `metaVersion`, on a node and on a media) and `placementRevision` (was
  `placementVersion`, ADR 0080 Decision 3), the columns `meta_revision` / `placement_revision`, and the
  `x-veyq-concurrency` mode `revision`. `placementVersion` was not named above; it is the same kind of counter on the
  same row and in the same `updateNode` request, so it moved too. Pre-V1, so no major and no alias. No sealed byte
  changed: the `node-name` and `content-key` seals bind the placement revision by position, never by name, and the
  conformance vectors only renamed their key.
- **Built (2026-10-16, inbox #452):** the rest of "version" that this table makes a revision. The conflict code
  `stale_revision` (was `stale_version`, every revision-checked write, the pins, the register and a rotation); the
  identity pins' and the device register's `revision` (was `version`: request, response, column, and the binding field
  of the `identity-pins` / `device-register` seals); `avatarRevision` (was `avatarVersion`, ADR 0084, the avatar read's
  ETag); and the terms, `termsRevision` / `acceptedRevision`, the accept's `revision` and `409 terms_revision_changed`
  (were `termsVersion` / `acceptedVersion` / `version` / `terms_version_changed`, ADR 0035). The terms were the open
  question, revision or edition: a revision, because the number has no compatibility meaning, moves on every change
  of the text and guards the accept with a 409 exactly as `metaRevision` guards a meta write; "edition" would be a
  second word for a meaning the table already names. A user reads it as *Fassung* in German copy. Pre-V1, so no major
  and no alias; the columns moved in the core baseline with its pin line. No sealed byte changed: the two account
  seals bind the revision by position, and their vectors only renamed the key (`context_bytes` unchanged). The vector's
  sample plaintext `{"version":1,…}` stays: it is opaque test bytes, and changing it would move `blob`.
  `validate_write_concurrency.py` now refuses a declared revision field named as a version.
- **Stays "version", because it is compatibility:** `/api/setup`'s `version: { major, build }` (ADR 0032), the
  release version (ADR 0057), an export's `appVersion` and its manifest's format `version`, the Emergency Kit's and a
  sealed payload's format version (`v`), and every spec's `info.version`.
- **The cost:** one more word for a reader to learn ("rights" next to "permission"). It pays for itself where
  ADR 0032 §3 needs it most: in a review, "capability" now always means the operator's switch.

## Alternatives considered

- **Keep "Files".** Rejected for the collision above.
- **"Drive".** Rejected: it promises sync, and Depot is built with no sync client on purpose
  ([`docs/apps/depot.md`](../apps/depot.md)).
- **"Vault" / "Tresor".** Rejected: it reads as a password manager.
- **Rename the instance capability instead of the per-view one.** Rejected: ADR 0032 defined the word, the id is a
  durable wire contract in an append-only catalog, and the per-view functions are web-internal and cheap to rename.
- **"Permissions" for the per-view projection.** Rejected: the server's authorization is the permission and the
  authority. A client's rights only mirror it and fail closed (ADR 0079), and one word for both would hide which
  side decides.
- **Keep "app" banned.** Rejected: a store app is an app, and a ban that has to be broken teaches nothing.
