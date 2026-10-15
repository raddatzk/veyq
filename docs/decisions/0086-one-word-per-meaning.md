# ADR 0086 — One word per meaning: platform, application, app, capability, rights, revision, format

- Status: Accepted; the word rules bind now, the renames in code are not built (§ Consequences)
- Date: 2026-10-15

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
| **revision** | a concurrency or change counter: `metaRevision` (was `metaVersion`), `mediaRevision`, a tree revision | compatibility |
| **format** | a sealed payload's schema, per node kind, readable forever (ADR 0079 decision 7) | the platform's major |
| **history** (*Verlauf*) | reserved for a future earlier-contents-of-a-file feature | "versions" of a file |

**Depot is the second application's name** (*Depot* in English and German alike). It says "where I put things
safely" without promising more. Its code id follows the product name (`depot`), so the product never has two
names. The worker's capability tags keep their name: they also mean "what this side can do", they never reach a
client, and they live in the database baseline, so a rename would cost a migration for no reader.

**The shape of the apps** (owner's direction, 2026-10-15; native work is parked until the web V1 stands): the
**veyq app** is core plus Depot, and each further application is its own app, reached from the others by deep
link. Shares open in the browser with an "open in the app" banner. The decisions this needs (one sign-in shared
across apps, how a link's key reaches an app safely) belong to the native client's own ADR, not to this one.

## Consequences

- **The word rules bind now**, and this change updates where they are written: the root `CLAUDE.md` and
  [`docs/apps/README.md`](../apps/README.md).
- **Not built**, open work tracked in the inbox. Pre-V1 there is no released client to stay compatible with, so
  the wire renames cost no major.
  - Files → Depot: the Gradle module `:apps:files` and package `me.raddatz.veyq.files`, `contracts/api/files/`,
    `web/src/features/files`, its route slug, i18n, `docs/apps/files.md`, `docs/features/files/` and the wiki in
    both languages.
  - `…Capabilities` → `…Rights` (web only).
  - `metaVersion` → `metaRevision` (contract → backend → web, a wire rename).
- **The cost:** one more word for a reader to learn ("rights" next to "permission"). It pays for itself where
  ADR 0032 §3 needs it most: in a review, "capability" now always means the operator's switch.

## Alternatives considered

- **Keep "Files".** Rejected for the collision above.
- **"Drive".** Rejected: it promises sync, and Files is built with no sync client on purpose
  ([`docs/apps/files.md`](../apps/files.md)).
- **"Vault" / "Tresor".** Rejected: it reads as a password manager.
- **Rename the instance capability instead of the per-view one.** Rejected: ADR 0032 defined the word, the id is a
  durable wire contract in an append-only catalog, and the per-view functions are web-internal and cheap to rename.
- **"Permissions" for the per-view projection.** Rejected: the server's authorization is the permission and the
  authority. A client's rights only mirror it and fail closed (ADR 0079), and one word for both would hide which
  side decides.
- **Keep "app" banned.** Rejected: a store app is an app, and a ban that has to be broken teaches nothing.
