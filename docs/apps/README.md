# Applications

veyq is a platform; an **application** is a set of node kinds with its own domain data and client screens, built
on it ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) owns the decision and
the rejected alternatives). German: *Anwendung*. Never "plugin", and never "app": an app is an installable native client
([ADR 0086](../decisions/0086-one-word-per-meaning.md), which also names the second application **Depot**).

This page is the current picture of the platform: what core owns, what an application owns, how the two meet in the
build, on the wire and in the web client, and which guards hold the line. The capability register an application is
switched by has its own mechanism doc, [en.platform.capability-register](../features/platform/enablers/en.platform.capability-register.md).

**What the platform is.** veyq is a platform for sealed, shareable objects. The core provides:
- **who**: tenants, central users, groups and personal scopes, memberships, invitations, keys, devices;
- **what**: nodes as a tree inside a scope, with parents, order, trash and a content key per node, and a member's
  private **favorites** of any node (a sealed set per user and scope, the heart on every row and header); and
  *attachments*, sealed files on a node that can be uploaded and downloaded, with size and storage quota, among them
  a node's **cover**;
- **with whom**: links with `READ`, `READ_WRITE` or `WRITE_ONLY` access, with password, expiry and consent;
- **out and in**: a member - or a `READ` / `READ_WRITE` share guest - **downloads** any node and everything under it as a
  plaintext ZIP that unpacks as the tree, and a member imports such a ZIP (or the same download unpacked) again
  ([ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md),
  format [`contracts/export/veyq-subtree.schema.json`](../../contracts/export/veyq-subtree.schema.json), core's envelope,
  which leaves a node's `content` opaque; each application's content is its own
  `contracts/export/<application>/content.schema.json`). Core owns the tree, names (`archiveName`) and the archive; a
  kind supplies and consumes its content through `KindDescriptor`, beside `createEntry`: `exportContent` /
  `importContent` (Depot the file bytes, Memories the media originals, each read through the downloading session's own
  source), `contentSchema` (its application id and content version, so an import lists what it cannot read),
  `validateContent` (its fields against its application's content schema, before anything is written) and
  `downloadsAsFile` (a kind that IS one file - Depot's - downloads as itself and is one file in a ZIP) - so no
  application imports another;
- **in the background**: tasks, rate limits, the admin channel, the capability register and operator-blindness.

An application supplies the **meaning**: which node kinds exist, what the plaintext means, which rules apply to
structure, attachments and guests, and the user interface. What a verified upload becomes is each application's
(`UploadCompletion`): Depot's is one attachment, Memories' its media tables.

**Rules** (each is argued in the ADR, not here):

- **First-party, compiled in.** No runtime loading of application code, server or client.
- **Depends on core, never the reverse, never on another application.** Cross-application references are generic
  core object references (scope id + node id) only.
- **Claims its URL route slug once.** A node of an application is addressed `#<path>/<slug>/<name>` in the URL
  fragment ([ADR 0015](../decisions/0015-operator-blind-readable-urls.md)): the application decides its route once in
  the web registry, for every kind it owns — its slug (`app.route(slug)`; a second decision throws, so does a slug
  another claimed; a platform word, `RESERVED_ROUTE_SEGMENTS`, is refused), or that it has none (`app.route(null)`) —
  Depot is the one that does. A claimed slug is reserved among
  folder and file names, which are numbered around it (`memories-2`; the module is `core/node/location.ts`,
  [en.platform.node-tree § Addressing](../features/platform/enablers/en.platform.node-tree.md#addressing-urls)).
- **Own tables keyed by `node_id`.** The server side is as thin as possible; domain logic lives in the client.
- **Payloads carry a schema version.** Old versions stay readable; migration happens on write.
- **No activation step, but a switch to turn it off.** Created on demand inside a scope. The operator, and a tenant
  owner for their tenant, may switch an application or one of its functions off through the capability register
  ([ADR 0088](../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)). A function
  off: none of it new. The application off: read-only — every write of it refused, every read, download and removal
  kept — and, left off past the instance's window, erased in that tenant
  ([uc.platform.switch-application-off](../features/platform/use-cases/uc.platform.switch-application-off.md),
  [uc.platform.erase-switched-off-application](../features/platform/use-cases/uc.platform.erase-switched-off-application.md);
  the mechanism: [en.platform.capability-register](../features/platform/enablers/en.platform.capability-register.md)).
  A new application takes part by: its own entry in `contracts/platform/capabilities.json`, its name as the id
  (`validate_capabilities.py` is red without one); its kinds' `NodeKindRules.applicationOf` and its member roles'
  `MemberBlobRoles.application`, so core refuses its node writes by kind; each of its write handlers calling
  `ApplicationSwitch.requireWritable` right after its gate (`ApplicationSwitchSweepTest` is red on a write operation that
  neither answers `capability_disabled` off nor is exempt with its reason); and on the web
  `registry.registerApplication({ id, name })`, through whose registrar it registers its kinds, and its rights function
  reading `access.applicationsOff`. Nothing names the kinds' application for `applicationOf` but the application itself:
  on the web a kind belongs to the application whose registrar registered it, so none can be left out.
- **Wired in one step, on both sides.** An application's spec directory `contracts/api/<application>/` lands with its
  Gradle module `:apps:<application>`: buildSrc's `SpecSets.requireNoStray` refuses a spec no module generates while
  the build is configured, so `./gradlew check` runs no task at all (`./gradlew help` shows it at refine). On the web,
  its `generate:api` line in `src/web/package.json`, its directory in `OWNERS`, its kinds in `KIND` and its i18n
  namespace in `I18N_NAMESPACES` (`scripts/validate_web_core_imports_no_applications.py`, which derives every
  application's client from that script) land together with a catalog for the namespace, or that guard is red. Its
  kinds file `contracts/platform/kinds/<application>.json` lands with its first kind: the backend's `KindsContractTest`
  is red on a kind constant no file states, and the web's `kindsContract.test.ts` on a registered kind no file states
  (and on an application without its rights function in the test's `RIGHTS`).
- **Crypto unchanged.** No application shares a key with another by accident: a node's key is random and wrapped
  under its parent's ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)),
  and a blob's key is sealed bound to its node and its application's role token — so no application so far needed a
  seal purpose or an HKDF context of its own (ADR 0079 decision 9). Memories' existing keys are the exemption.
- **Every concept doc lists the metadata its kinds make operator-visible** (the platform adds only the node
  `kind`, [security-model](../crosscutting/security-model.md)).

| Application | State |
|---|---|
| [Memories](memories.md) | Built. Backend `:apps:memories` (`me.raddatz.veyq.memories`), contract `contracts/api/memories/`, web `src/web/src/features/memories`. |
| [Depot](depot.md) | Built. Backend `:apps:depot` (`me.raddatz.veyq.depot`), contract `contracts/api/depot/`, web `src/web/src/features/depot`: a member's files (upload, download, replace, rename, delete), files on a shared folder (read, contribute) and the drop box; use-cases under [features/depot](../features/depot/_index.md). |
| [Expenses](expenses.md) | Built ([ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)). Backend `:apps:expenses` (`me.raddatz.veyq.expenses`), contract `contracts/api/expenses/` and the entry format `contracts/expenses/`, web `src/web/src/features/expenses`: a Pot is a node kind, its people and entries sealed revisions in its own table, appended and never overwritten; balances, settle-up and overviews in the client; a `READ` or `READ_WRITE` link; receipts (images and PDFs, blobs of core's blob service held by their entries, [ADR 0112](../decisions/0112-an-applications-stored-blobs-are-managed-by-a-core-service.md)); the day's exchange rates (the ECB's euro reference rates, fetched by the device once a day with the date alone, the tenant's switch reaching a link guest through its Pot read). |
| [Inventory](inventory.md) | Concept only. |

## How core and an application meet

**The rule: around a node is core's, behind it the application's** ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
decision 3). Core owns what every node has — create, rename, move, delete, trash and purge, its key, rights, links,
cover, favorite, report, the export envelope, the live tree; an application owns what is behind its node — its data
model, tables, formats, records, operations, screens, validation and arithmetic — and shares it with no other, not even
as a core template. The exception is data management: the blobs an application stores are managed by a core service
that carries the platform's duties over them, and the application registers its blobs with it
([ADR 0112](../decisions/0112-an-applications-stored-blobs-are-managed-by-a-core-service.md), proposed). A shared helper
library that owns nothing is allowed, never required; an application's rights answer only narrows core's.

### The build

- **Backend modules.** Core and each application are Gradle modules below `src/backend/`: `:core` (`src/backend/core`)
  and `:apps:<application>` (`src/backend/apps/<application>`). The test helpers they share are `:core`'s
  `java-test-fixtures` source set, on no runtime classpath. The root `src/backend/` holds no main code; it composes the
  modules into the one fast-jar, each module's jar `lib/main/me.raddatz.veyq.<module>.jar`. The direction is the
  build's: `:core` has no edge to an application, so a core file importing one does not compile.
- **Named for the owner.** The platform's classes are `me.raddatz.veyq.core.<feature>`, an application's
  `me.raddatz.veyq.<application>.<feature>`, the generated API `me.raddatz.veyq.core.generated` /
  `me.raddatz.veyq.<application>.generated`. Core's specs sit in `contracts/api/` (`_common.openapi.yaml`, where every
  shared schema is defined once, is core's), an application's in `contracts/api/<application>/`. The build derives the
  owner from that place, never from a list: a directory below `contracts/api/` holding a spec is the module
  `:apps:<directory>`; a spec in a directory with no such module, or nested deeper, fails the build while it configures.
- **Contracts by owner.** A core spec describes what core does: where a value's meaning belongs to an application (a
  node's sealed payload, a blob role, a kind), its description says whose it is and names no application's plaintext,
  operation or key. An operation whose behaviour is an application's lives in that application's directory even when
  its path sits under a core route (`/api/shares/{shareId}/media` is Memories').
- **Migrations and configuration.** Each module owns its migrations under one global version scheme
  (`V<yyyymmddHHMM>__<module>_<slug>.sql`, [src/backend/CLAUDE.md](../../src/backend/CLAUDE.md) § Migrations): a core
  migration names no application table, an application's references only core's and its own. Core's schema and config
  name no application concept: an application's configuration keys, should it have any, are its own `@ConfigMapping`
  (none has one today), whose `@WithDefault`s are their only defaults, since SmallRye refuses a `veyq.*` key no mapping
  claims and a line in core's base config would fail core's boot without that application.

### The seams core asks

Core reaches application code only through interfaces each application implements, collected by CDI; the list is
`ModuleCompositionTest.seams()` (the root's test, which pins the composed sets). They fall into four groups:

- **Erasure and accounts** — `ScopeEraser`, `NodeSubtreeEraser` (phase-ordered), `MembershipEraser` (one user loses
  access to one scope; nothing of the scope is erased), `AccountEraser`, `GuestIdentityUsage`, `ApplicationErasures`
  (the erasure-ledger kinds an application writes and their re-applier, which core runs at start after a restore, ADR
  0056 decision 8).
- **Kinds and uploads** — `NodeKindRules` (where a node of the kind may live, whether it takes a cover, which
  application owns it; core answers for `FOLDER`), `UploadCompletion` (what a verified upload becomes; its item is
  generic — an `ownerId`, role-tagged derivatives and the application's sealed payloads as opaque values core carries to
  the one implementation that accepts the kind), `UploadAdmission` (what a link's guests contributed to the
  application's own rows, for the per-link limit; core sums open sessions and attachments itself), `MemberBlobRoles`
  (which blob roles a member's upload start may carry: a union, since a member's start names no node),
  `ApplicationBlobOwners` (the application's half of core's blob service, [below](#cores-blob-service): its roles with
  their count per owner and whether a guest adds one, and whether an owner may hold a blob of a node),
  `NodeRekeyParticipant` (the application's rows sealed under a node's key, when that key is replaced),
  `ScopeRekeyParticipant` (the application's rows sealed under the scope key itself — Memories' item marks — which a
  rotation's commit re-seals, fail closed, [ADR 0115](../decisions/0115-a-scope-key-is-replaced-never-chained.md)).
- **Links and guests** — `ShareRoots` (which roles a node of the kind may be the root of a link with),
  `GuestNodeRules` (which tree operations a `READ_WRITE` guest may do inside a container of the kind; core allows them
  in a `FOLDER`, Memories none in a Memory or chapter), `WriteOnlyGuestKinds` (what a `WRITE_ONLY` guest may create in
  its identity folder: Depot's `FILE` in its drop box), `GuestCreatableKinds` (which kinds a `READ_WRITE` guest may
  create inside a `FOLDER` besides a folder: Depot's `FILE`; a kind two applications declare is created by nobody and
  fails startup), `ShareGuestWrites` (what "write" means on the application's node, asked for the kind written into),
  `SharedSubtreeContributor`, `GuestPlaceholders`.
- **Moderation** — `ReportableItems` (where an application's item lies, for a report's reach),
  `HoldSnapshotContributor` (copies the application's rows into a hold), `HeldUploaders` (the member accounts among
  the uploaders of the application's held rows, which a moderator may propose to block, ADR 0093 § 6) and
  `UploaderContent` (what one uploader put into a space, for "everything from this uploader", ADR 0093 §§ 1, 6).

**They fail closed.** A role no application declares opens no member upload (`400 invalid_request`, before any quota
is reserved); a kind no application (or two) claims takes no uploads (`illegal_target`), is placed nowhere
(`illegal_structure`), is shared by no one, takes no guest tree operation, lets a link's guest create nothing
(`illegal_scope`); a link whose usage no single application measures is refused (`share_not_contributable`).
`:core`'s test application is core with no application at all, and `CoreAloneSliceTest` pins what that means: every
seam holds exactly core's own implementations and every refusal above holds. Hand-written core code names the node
kind `FOLDER` only.

### Nodes, payloads, attachments and covers

- **Node kinds are open.** The node `kind` is an open, bounded token on the wire (`NodeKind`,
  `^[A-Z][A-Z0-9_]{0,15}$`); core names `FOLDER` only and each application brings its kinds and their rules
  (`NodeKindRules`, the web registry). The backend carries a kind as a string and checks a request's shape at the
  boundary (400); the web types it as a string with core's one `FOLDER` constant. No compiler lists the kinds: the
  registry's fail-closed answer, its tests and the kind guards carry what a closed enum's exhaustiveness did.
- **A kind's facts are written once, in its owner's kinds file.** `contracts/platform/kinds/<owner>.json` (`core.json`
  for `FOLDER`, one per application, schema `kinds.schema.json`) states per kind where it may live, whether it takes a
  cover, what its creation needs, the roles a link rooted at it may have, and what a link's guest may do around it — the
  facts the backend answers through its seams (`NodeKindRules`, `ShareRoots`, `GuestNodeRules`, `GuestCreatableKinds`,
  `WriteOnlyGuestKinds`) and the web through its registry (`placement`, `cover`, `shareDialog`, `app.shareBody`) and its
  rights functions. Each side keeps its own code and is held to the file by a test: the backend's `KindsContractTest`
  (the root's, where every module runs together: every bean answers the file, and every kind constant of the compiled
  code is in one) and the web's `src/web/src/kindsContract.test.ts` (every registered kind is in a file and answers it).
  A new kind is written there first; a fact changed on one side only is red on that side.
  `PublicShare.rootKind` is the root row's kind as stored, served for every unlocked role (a `WRITE_ONLY` guest, served
  no node, learns this one structural fact) and omitted on a locked response and while the root is in the trash.
- **The payload slot and the content key are core's.** Every node has one sealed, versioned payload (`wrappedMeta`,
  `metaRevision`) that core stores, compare-and-sets ([ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md))
  and lists with the tree, and never opens; its plaintext format is the kind's application's. `wrappedContentKey` is
  every node's content key, chained so a link's guest derives every descendant's key (ADR 0080).
- **Attachments are core's.** `veyq_node_attachment` holds one blob per node slot, the slot being the application's
  role token, written only by `AttachmentStore` (`attach`, `detach`); its foreign keys (scope, node, blob, guest
  identity) do not cascade, so a row goes only through core's erasers. `BlobRole` is an open token like `NodeKind`;
  core stores and compares it and names none of an application's. `validate_blob_reference_arity` pins the `veyq_blob`
  arms.
- **A cover is a node feature, core's.** Any node whose kind opts in (`NodeKindRules.takesCover`: core's `FOLDER`,
  Depot's drop box, Memories' two kinds) carries one uploaded image in its own `COVER` slot, written by core's
  `setNodeCover` / `removeNodeCover` together with the re-sealed `wrappedMeta` that names it, in one transaction. The web
  half (`core/node/cover/`) serves every kind; a cover is set only by an upload in core's dialog, and an application adds
  only what is its own (Memories' `cover.findStored`: an upload equal to a gallery photo shares that medium's stored
  PREVIEW, [ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md) § 5). A stored reference of any other
  source reads as no cover. Residual, accepted: a shared cover row names the medium's PREVIEW blob, so the operator can
  tell which medium is the cover.
- **The drop key is core's link mechanism.** A `WRITE_ONLY` link's drop key pair: the public half sealed under the
  link's `K_share` on the share row, the private half the root node's (`veyq_node.sealed_drop_private_key`), sealed under
  its content key and set once by core inside the share creation (409 `drop_key_exists` / `drop_key_missing`). Depot's
  drop box and Memories' uploader links both seal to it.
- **A record list inside a node is core's primitive**:
  [How an application publishes a revisioned list](#how-an-application-publishes-a-revisioned-list).

### Core's blob service

The blobs an application stores — any number per node, unlike an attachment's one per slot — are managed by core
([ADR 0112](../decisions/0112-an-applications-stored-blobs-are-managed-by-a-core-service.md), ADR 0079 decision 3's one
exception: data management is core's). Built, with Expenses' receipts and Memories' media its applications
([ADR 0113](../decisions/0113-memories-media-are-blobs-of-cores-blob-service.md)).

- **Two arms.** `veyq_blob` has two arms, one on each side of the line: core's node attachments (around a node — a
  cover, a banner, a file's content, one blob per slot) and the service (behind it); `validate_blob_reference_arity`
  pins them.
- **The table is core's**: `veyq_application_blob` (`core/applicationblob/`), the service's `veyq_blob` arm — one row per
  blob: its client-minted id, scope, node, **owner** (the application's record, an id core stores and compares and
  never resolves), role, blob, writer, created, kept and released. Every platform duty is core's implementation of the
  seams above: the completion (`UploadCompletion`), a guest's upload (`ShareGuestWrites`), the link's usage
  (`UploadAdmission`), the share's blob predicate (`SharedSubtreeContributor`), a blob as an `ITEM` of a report and a
  block (`ReportableItems`), the hold (`HoldSnapshotContributor`), "everything from this uploader" (`UploaderContent`,
  `HeldUploaders`), the re-key of every wrap (`NodeRekeyParticipant`: the wraps are core blob wraps, read page by page
  by `listApplicationBlobRekey` with the id each is bound to and staged by `stageNodeRekey`; a `WRITE_ONLY` guest's,
  under its own `K_up`, are left out), the erasers (subtree, scope, account, guest identity), the retention purge
  (`ApplicationBlobPurge`), an application's erase at once of an owner's blobs (`ApplicationBlobErasure.eraseOwners`)
  and the reclaim of the objects. Its wire is core's (`blob.openapi.yaml`, tags `application-blobs`,
  `share-application-blobs`, `case-application-blobs`), and so is the web's transport (`core/blob/applicationBlobs.ts`).
- **A role has one of two forms** (`ApplicationBlobRole.ownItem`), each with five rules the service reads from it. **An
  item of its own** (Expenses' receipt): its wrap is bound to its node (`blob-key`'s `ownerId`), its owner's sealed
  record naming it; it is completed through the service's routes one blob per call, is an `ITEM` of a report and a
  block on its own, still reaches a guest once released (an application's history), and the service's node, link and
  case reads list it. **Its owner's content** (a medium's `ORIGINAL`, `THUMBNAIL`, `PREVIEW`): its wrap is bound to its
  owner; it is completed only in its owner's write, through `ApplicationBlobs.attachIn` in the application's own
  completion (a medium is born with its blobs); it is reported, blocked and held with its owner; released with its
  owner's end it is in no share's reach; and its application's record reads serve it from `ApplicationBlobs.ofOwners`.
  The service's own completion and guest route accept only a kind that stores an item of its own.
- **What an application implements** is `ApplicationBlobOwners`: which kinds' blobs are its own, its roles (with
  `MemberBlobRoles` for their size caps) each with a count per owner and whether a link guest may add one, and at a
  completion whether the owner may hold a blob in that node and where it stands (`NEW`, `KEEPING`, `ENDED`). In every
  write of its own that changes what a record keeps, in that write's transaction, it calls `ApplicationBlobs.keep` (the
  owners keep their blobs; a blob released with its owner's end is kept again), `releaseOwners` (the owners ended) or
  `release` (blobs released by name, for good), after locking the service's rows with `ApplicationBlobs.lock` before its
  node's lock. A report of its own record asks `blobIdsOfOwner` / `heldObjectsOfOwner`.
- **The purge reads the service's rows only**: a blob goes once the node trash retention has passed since its release,
  or since its completion while nothing ever kept it — so it takes no application lock.

### Links and guest operations

A link grants one of three access modes, `ShareRole`'s wire values `READ`, `READ_WRITE`, `WRITE_ONLY`; core enforces the
mode, and the kind's application decides which roles its nodes may be shared with (`ShareRoots`). Core owns the public
link and its guest gate (`ShareGuestGate`: active link, password, the mode's capability, the guest identity's token,
the per-link upload budget), exposed as a service an application calls before its own guest read or write. Core's
share contract keeps only kind-neutral guest operations: resolve (the link and the shared nodes, their names and keys,
no application data), terms, blob transport and download, guest identity, and the tree operations
(`createShareNode`, `updateShareNode`). An application serves its rows and writes on its own route under the share,
checks that the node it writes into is its own kind, and carries its rows' privacy rules with it (Memories'
`listShareMedia` nulls every identity id but the uploader's own, [ADR 0030](../decisions/0030-contributor-public-share.md)
point 8).

### The web client

- **Import direction, not modules.** `src/web/src/features/<owner>` is an application's directory, named for its owner;
  no core file imports one except the composition root `src/web/src/applications.ts`. The shell asks a build-time
  registry (`src/web/src/core/applications/registry.ts`) what to render for a node kind, which page a `WRITE_ONLY`
  link shows, and what runs when a scope is entered or reset and when the Master Key rotates (the Master-Key hook is
  required of every hook set, while a build with no hooks rotates, core re-sealing its own pins). The shell's top bar
  and sidebar are core's alone: no application adds to them.
- **One registrar per application.** An application names itself once, `const app = registry.registerApplication({ id,
  name })`, and registers everything else through the scoped registrar it is answered: `app.kind(kind, descriptor)` per
  node kind, `app.route(slug | null)` once for all its kinds, `app.lifecycle(hooks)`, `app.shareBody(rootKind, body)` (a
  kind of its own only), `app.personSection(component)`, `app.itemReview(component)`. Ownership is the registration —
  there is no list of an application's kinds beside it — and `app.id` is the one place its id is written on the web
  side: a kind's `contentSchema` names its version, the registry fills the application in. Every call is write-once and
  refuses at runtime what would be ambiguous; there are no free register functions. A kind may be registered
  `listed: false`: core's level draws no row for its nodes, not even the unsupported one (the seam for a kind reached
  another way, Inventory's pool, see [Decided, not built](#decided-not-built)).
- **One host contract.** Whatever draws a node - a kind's page, a row factory, core's level - asks the page around it
  through ONE set of doors (`core/node/nodeHost.ts`, `NodeHost`: open, rename, move, delete, share, create a child,
  download, copy, import, the trash), injected by a kind's page and handed to a row factory as its `LevelRowsHost`. Every
  door may be absent, and an absent door is an action not offered, whatever a rights function says. The dialogs behind
  them are core's one set (`core/node/components/NodeDialogs.vue`), mounted by both pages: the member's space page
  hands on every door, a share guest's page `open`, the download, the create and - a `READ_WRITE` link - the move.
- **Core draws the level.** The folder level view is core's (`core/node/LevelView.vue`): one list of the folders and
  every kind's rows, each from its application's row factory (below), and the presentations it switches between (core's
  grid and list, an application's own such as Memories' map) in core's toolbar. A folder's create, every node's rename,
  move (`core/node/MoveNodeDialog.vue`, mirroring the server's `NodeKindRules` through each kind's `placement`) and a
  `FOLDER`'s share dialog are core's; an application's create and share dialogs ride on its kinds. The top-bar upload
  status is core's and lists every application's uploads.
- **Core's own actions come centrally.** The heart, the cover, "Copy to…", the ZIP download and Report are core's for
  every node (`core/node/coreNodeActions.ts`), each gated by its core rights function: the level adds them to every row
  and to the folder's header, and core's node page (`core/node/components/NodePage.vue`, which both hosts mount around a
  kind's `detail`) supplies them to the header of the node on screen. A kind's page only names the node its `NodeHeader`
  heads; it builds and forwards none of them, so Report - a legal duty on everything a person sees - is on every page
  ([ADR 0110](../decisions/0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md) decision 9).
- **Rename, move and delete are core's default functions.** Every node gets them from core - on its row
  (`core/node/standardRowActions.ts`) and in its header (`standardNodeActions`) - through core's dialogs (the name
  dialog, the move dialog, the delete question with its Undo, the same for a row's node and a page's own). The application decides only WHETHER: its rights
  function answers each for the node (a row's `standard`, the header's flags), and no answer is no action. An
  application that needs its own implementation supplies an action of the same id, which replaces core's; none does
  today (Depot's file and drop box use core's three, with their kinds' own delete sentence). A share guest's
  page hands on core's move door alone (a `READ` link's not even that); no guest renames or deletes a node core's way.
- **One view per kind, for a member and a share guest alike.** A kind's `detail` and its row factory receive `access`
  (a member of the unlocked scope, or a guest of a link) and `tree` (the member's decrypted scope tree or the guest's
  opened subtree, every navigation through its `linkTo`, so a guest keeps its `#k=` fragment); core mounts them in both
  modes (`core/access.ts`, `core/sharing/guestAccess.ts`, `core/sharing/ShareTreePage.vue`). What a view offers is its
  application's **rights function**: pure and fail-closed — every right false unless granted, an unknown mode, role or
  kind grants nothing — the role matrix of ADR 0080 decision 6 in code, written down in `docs/apps/<application>.md`;
  core has its own for `FOLDER`. **Core owns what every node may do** — share it, rename, move and delete it: core's
  `nodeRights` answers it once from who looks, a pending deletion, the application's switch, the instance's share
  leaves, the kind's share dialog, a guest's identity folder and the scope root, and every rights function (core's
  `FOLDER` one too) starts from that answer and may only narrow it, never grant what it denies — each one's
  `rights.test.ts` walks every access shape to prove it. The row's `standard` and `selection` and the header's flags
  read that one answer under one name per right. A pending deletion keeps every kind's share dialog to see its links,
  and the host tells the dialog to mint and change none but revoke the caller's own live link
  (`NodeShareDialogProps.readonly`) — the one link write the server takes then, since it only closes exposure
  (uc.spaces.scope-access [A1](../features/spaces/use-cases/uc.spaces.scope-access.md)). A right holds only where the
  matrix allows it **and** the session's data source
  (`features/<owner>/source.ts`, the member's or the guest's, fixed at mount) implements the operation. Member-only
  state — pins under the Master Key, item marks, a node's sealed meta, the scope's member list — is granted to no
  guest role. The one guest-only page is a `WRITE_ONLY` link's drop (`app.shareBody`), whose guest holds no key; the
  guest shell (password and terms gates, one naming flow with "Not you?", the curator's note, the role badge) is core's.
- **Strings and styles follow the directories.** Each application's i18n namespaces are its own catalog,
  `features/<owner>/i18n.ts`, which the composition root's data-only half (`src/web/src/applicationMessages.ts`) adds to
  core's one vue-i18n instance at build time, write-once; core's catalog holds core's alone. An application's global
  classes are its own stylesheet, imported by its `register.ts` and so loaded before core's `style.css`: a rule of it
  that styles a core class's element compounds with that class and wins by specificity, never by source order.

### Guards

- **Backend:** core → application is the compiler's (no edge). `validate_backend_module_graph.py` holds what Gradle
  allows: no application depends on another, no package is declared in two modules, and every module is included,
  indexed (an unindexed module boots green with its routes answering 404 and its seams missing), copied by the
  Dockerfile and named. Inside core, `validate_kernel_imports_no_slices.py` keeps the kernel → slice rule and the
  node-kind rule (core names no application's kind or blob role), which no import check can see.
- **Web:** `validate_web_core_imports_no_applications.py`: core imports no application's directory or a client
  generated from its specs (derived from `generate:api`), discovers no code by glob, names no application's node kinds
  and reads no application's i18n namespace; no application imports another or reads another's namespace; each catalog
  sits with its owner. `validate_web_share_bodies_are_drop_pages.py` keeps `ShareBody` meaning a claimed drop page. The
  guest-mode mounts through the real shell (`ShareView.memory.test.ts`, `MemoryDetail.guest.test.ts`,
  `DropBoxDetail.guest.test.ts`, `FolderShareGuest.test.ts`) assert that a guest's page reads nothing of the member's
  world.
- **Both kind rules read literals**, from lists a new application extends by hand: a kind missing there is guarded by
  nothing. (The kinds files of `contracts/platform/kinds/` hold the kinds' facts, not these guards' lists.)

## How an application publishes a revisioned list

A list of **records** inside a node (Memories' media, Expenses' entries, later Inventory's items) that an open view
keeps live uses the platform's primitive ([ADR 0033](../decisions/0033-live-media-updates-revision-polling.md)): a
per-node revision, a conditional GET, a keyset delta and a delete-gated id set. Core owns the counters
(`veyq_node_items_revision`, erased with the node), the bump, the conditional read and the client loop; the application
owns its records and one query. The steps:

1. **Give each record a position and an id.** The position (an `INT`, Memories' `veyq_media.item_position`) is the
   revision core's bump returned for the record's last change, stamped by every writer that moves the record to the end
   of the list, in its transaction, after the bump — the bump holds the revision row's lock to the commit, so positions
   become visible in commit order and the keyset `(position, id)` never lets a record land behind a client's cursor (a
   timestamp taken before that lock did: ADR 0033 decision 3). Index `(node_id, position, id)`.
2. **Call core's bump from every writer, in the writer's transaction**: `items.bump(scopeId, nodeId)` for an add, an
   edit or a restore, `items.bump(scopeId, nodeId, deleted = true)` for a removal (it moves both counters); it returns
   the revision it wrote — the record's new position. A writer that
   forgets is a live view that never learns of the change, so add the application's writers to a source guard like
   `NodeItemsRevisionBumpsTest`. Take the record rows' locks first, then call `bump` (it locks the node row on a node's
   first change only); never take a lock after it. Erasure needs nothing from the application: core deletes the
   counter row with the node.
3. **Serve the list from a `read`, one page at a time.** `NodeItemsRevisions.read(nodeId, ifNoneMatch, sinceDelete, delta, ids)`
   answers `NotModified` (a `304`, neither lambda runs) or `Changed(revisions, delta, ids)`; `delta` is one page of the
   application's keyset query past `ItemsCursor.parse(since)` — read `limit + 1` rows (`ItemsPage.limitOf(limit)`) and
   answer `ItemsPage.of(rows, limit)` with the last row read as its `ItemsCursor` — and `ids` the node's current record ids
   (run only when a removal happened since). `ItemsETag.of(revision)` is the response's `ETag`, `ItemsETag.parse(header)`
   the request's. Map a page with a constant number of queries (Memories' `toRows`).
4. **`$ref` the shared contract pieces** in the list operation: parameters `ItemsIfNoneMatch`, `ItemsSince`,
   `ItemsSinceDelete`, `ItemsLimit`, header `ItemsETag`, response `ItemsNotModified` (all in `_common.openapi.yaml`). The
   response body is the application's own schema; by convention `<kind>Revision` (the ETag's number), `deleteRevision`,
   `<kind>Ids` (null unless a removal happened), `more` and `cursor` beside the delta list, which declares
   `maxItems: 1000` (the cap, `ItemsPage.CAP`).
5. **Build the view on `core/items/itemsLive.ts`.** `readItemPages(first, next, idOf)` is the one page loop of a full read
   (a first load and a poll alike): it keeps the first page's revisions and the last page's cursor (ADR 0033 decision 6).
   `itemsPollTick({ cursor, fetch, apply })` is one tick for `createLivePoller(ITEMS_POLL)`: `cursor` reads the view's
   revisions and the cursor the last read answered, `fetch` runs the page loop (a `304` is `{ notModified: true }`),
   `apply` merges with `reconcileItems`, keeps the answered cursor and decrypts. A failed fetch rejects so the loop backs
   off.

The scope's *node list* is not this: it has its own revision ([ADR 0081](../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)).

## How an application shows facts in a level's rows and tiles

Core draws every row (a list item) and tile of a folder level, and of the profile page's Memories list, from the
`LevelRow` its application's `list.rows` factory answers per node (`src/web/src/core/node/levelRow.ts`). Under the title
sits **one meta line**: the kind's noun (`meta`: "Memory", "File") followed by the node's **details** (`details`) - the
facts only the application can read: Memories' **date and place**, Depot's **size and modified time**, a drop box's
people count. The hook:

1. **Answer `details` in the row**: an ordered list of `{ id, text }`, already translated and formatted for the reader's
   locale. The application owns what the facts are, where they are read (the node's unsealed meta, Depot's blob listing)
   and how they read; core owns the line - the " · " between them, the wrapping (two lines, then an ellipsis), the
   colour tokens and the reading order (it is plain text inside the item's button, no icon-only content).
2. **Leave out what the node does not have.** A Memory without a place has no `place` entry; a file whose blob carries no
   modified time has no `modified` entry. Never a placeholder ("unknown", "-", "no date yet"); core drops a blank
   entry as well (`levelRowDetails`), so a hole cannot reach the line.
3. **No application is named in core.** The ids are the application's own tokens and only a styling and testing hook
   (`data-detail`). Another surface that lists an application's nodes the same way (the profile page's Memories) builds the
   same details with the application's one builder (`features/memories/memoryRowDetails.ts`), so the two cannot drift.
4. **A share view shows the same details** where the served data carries them (a guest holds the node's key and gets its
   sealed meta unchanged): rights remove functions, not facts, so no guest-only row exists.

The layout is core's and shared by every kind: a list row's visual is a 4.5 x 3 rem tile (6.5 x 4 rem from 521 px), a grid
tile a 16:9 cover over text, and the same item component is drawn on the profile page, whose rows/tiles switch is the level's
own (`ViewModeToggle` over the per-user `useViewMode` preference). A map is not part of this hook: it is an application's own
presentation (`presentation(mode)` beside `row`, named in its kinds' `list.views`), and the profile page offers Memories'
map the same way the level does, the same component over the person's Memories that have a place.

## Decided, not built

- **An application's own entry in core's navigation** (for [Inventory](inventory.md)'s one pool per space, created on
  first open and no row of a level). Built: the kind half, `listed: false` (above). Next, with Inventory: an
  `app.entry({ label, icon, open(scope) })` on the registrar - core draws each registered entry in the space's
  navigation, hidden while no application registers one, and `open` is the application's (it finds or creates the
  space's pool and opens it). Its place (the sidebar or the space's header) is decided against the styleguide then,
  with the pool's create flow (who creates it, under which key) beside it.

- **The capability register's open entries and requirements** (ADR 0088):
  [en.platform.capability-register § Decided, not built](../features/platform/enablers/en.platform.capability-register.md#decided-not-built).
- **Place names and the map** ([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md)): a space owner's enabling
  notice to its members and the tenant language for shared names. What is built:
  [Memories § Places and the map](memories.md#places-and-the-map).

A concept doc is **not** state the code answers to: nothing in it is built, refined into use-cases or planned.

- End-user view (docs site): [Applications](../../site/src/content/docs/applications/index.mdx)
- End-user view (docs site): [What veyq is](../../site/src/content/docs/getting-started/index.mdx)
