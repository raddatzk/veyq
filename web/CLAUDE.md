# web (Vue 3 + PrimeVue)

The web client. Loaded in addition to the root `CLAUDE.md` when working here. All content crypto runs
here; the server is blind.

## Prove your work
- `npm run check` (lint + typecheck + test + build).
- API client is **generated** and **not tracked** (gitignored, mirrors the backend) — `npm run generate:api`
  from the specs under `../contracts/api/` (core's at its top level, the Memories ones in `memories/`, one client
  directory per spec) produces it (and runs automatically on install via `prepare`). Never
  hand-write the client.
- **`npm run check` does NOT regenerate the client — so on its own it proves nothing about a contract change.**
  It runs lint/typecheck/test/build over whatever `src/api/generated/` already holds, which is the *previous*
  spec. Change `contracts/api/` and a green `check` is a green run against stale code: run **`npm run
  generate:api` first**, then `check`. **Authoring the change is not the only trigger — any time the spec moves
  under you, it counts**: merging a contracts-phase CR, switching branches, or checking out a fresh worktree
  (each worktree has its own untracked `src/api/generated/`). Merging `cr-favorites-two-objects` into a main
  tree whose client predated it produced `error TS7006: Parameter 'e' implicitly has an 'any' type` on a
  generated call — which reads as a defect in the merged code and is in fact the generated client being one
  spec behind. (Unlike the backend, where `./gradlew check` generates as part of the
  build — the asymmetry is the trap.) cr-0171 nearly shipped "the markers are inert to both generators" on a
  check that had never seen them.
- **An absence asserted through the generated client proves nothing about the wire.** Its `…FromJSON` keeps only
  the fields the spec names, so a field the server still sends (or sends again) is dropped before the test sees
  it. To assert that a response does *not* carry something, read `…Raw(…)` and `.raw.json()`, as the two sharing
  e2e suites do for the guest name (ADR 0030 point 8). The request side mirrors it: `…ToJSON` drops a field the spec
  does not name, so a bite that adds one to a request body stays green — assert a body at `fetch`, as the
  `*.wire.test.ts` suites do.
- **A test that asserts what a click handler calls has not clicked anything.** The element's own default action
  runs too, and only a real DOM click sees it: a PrimeVue `Breadcrumb` item without a real `url` renders
  `<a href="#">`, whose default ran after the `command` and cleared the `#k=` fragment that carries a share key —
  every guest write after a reload then failed, while the unit test that asserted the `router.push` arguments stayed
  green (found only in a real browser, 2026-10-07). A core page that keeps the share key in the URL fragment
  navigates with `hash: route.hash` and gives every link a real `href`; its test mounts the real component and the
  real router and clicks the rendered element (`ShareTreePage.breadcrumb.test.ts`, `ShareTreePage.test.ts`,
  `FolderShareGuest.test.ts`).
- **A guest-mode mount proves it calls no authenticated client** (inbox #401): mock each client module through
  `src/test/guestMode.ts`'s `guardedClients` (`vi.mock('…/api/client', async (o) => (await import('…/test/guestMode'))
  .guardedClients(await o()))` — the public factories become `vi.fn`s to stub, every other one throws and is recorded)
  and assert `authenticatedCalls` stayed empty; a call is then red even where the view swallowed the error.
- **`vi.mock` resolves by path, so moving a module silently disarms every mock of it.** A mock left on the old
  path stubs nothing and the real module runs — and a passthrough mock (`withRefresh: (f) => f()`) is green
  either way, so no red announces it. After a move, prove the new mocks bite by sabotaging the *real* module
  (make the export throw): every suite that mocks it must stay green, and the module's own test must go red.
- **Dev credentials come from the seeder — never hardcode or guess them.**
  - **`npm run bootstrap:user` (the local principal).** Inputs live in **`<repo-root>/.run/.env`**
    (gitignored — note it is at the **repo root**, not `web/.run/`; template `<repo-root>/.run/.env.example`):
    `VEYQ_EMAIL` / `VEYQ_PASSWORD` / `VEYQ_TENANT` / `VEYQ_TENANT_DOMAIN`, and the seeder writes back
    `VEYQ_SECRET_KEY` + `VEYQ_WRAPPED_MASTERKEY`. It creates ONE user that is Server Owner AND tenant owner
    (holds the **TENANT grant** + a personal scope). This is the current dev instance (IntelliJ "Bootstrap User"
    run config) — the tenant is **`veyq.localhost`** (e.g. "Familie Raddatz"), so **live-verify browses
    `veyq.localhost:<viteport>`** and logs in with the `.run/.env` creds/secret key. **Cold start (fresh/empty
    DB):** the `.run/.env` key material is stale after a `docker compose … down -v` (it was written against the
    old DB), so **re-run `npm run bootstrap:user`** against the fresh backend first — it reseeds
    `veyq.localhost`, points the tenant at Mailpit so member invites can actually be sent, and rewrites
    `WRAPPED_MASTERKEY` — then browse `veyq.localhost:<viteport>`. **`SECRET_KEY` is re-used, not re-minted**,
    when `.run/.env` already holds a usable one, so the kit you copied into a browser survives a re-seed
    (`buildAccount(..., { secretKey })` — dev bootstrap only; every real birth mints fresh entropy). Against a stale
    or wrong DB, `POST /api/login/verify` answers **401** (password-derived auth doesn't match the registered
    identity) — not a crypto bug; re-seed a fresh instance.
    - **It is an API client like any other** and calls the backend through the generated `…Api` clients, built in
      `scripts/apiTransport.mts` — the one file under `scripts/` that may use `fetch` or `node:http` (lint:
      `veyq/seeders-use-the-generated-client`), and `npm run typecheck` covers `scripts/` (`tsconfig.scripts.json`).
      When it hand-wrote its bodies, login verify gained `nonce`, the seeder kept the old shape and failed its own
      login with 400. Tenant-scoped calls go over `node:http` as the client's `fetchApi`, because the backend
      resolves the tenant from `Host` and fetch drops a hand-set one; that works for any domain, resolvable or not.
    - **It writes the owner's material as soon as the owner is complete**, before the optional second user — a
      failure in an optional step warns and leaves a usable owner behind. It used to write last, so a failing
      user2 threw away the Secret Key of an owner that already existed in the DB (irrecoverable: a wipe + re-seed
      was the only way out).
    - **DEV auto-unlock (cr-dev-auto-unlock) — reach the unlocked dev app without typing the password.** Browse
      `veyq.localhost:<viteport>/?devlogin` and `boot()` logs in + unlocks from `.run/.env` via the app's real
      `login`+`unlockMasterKey` (then enrols the device key, so later loads restore without the param). For a phone,
      or an agent barred from entering credentials into a field. **Strictly dev-only:** the creds come from an
      `apply:'serve'` Vite middleware (`/__dev/seed-credentials`, never built) and the consumer is
      `import.meta.env.DEV`-gated (tree-shaken); `npm run verify:no-backdoor` fails the build if either sentinel
      reaches `dist/`. A plain `npm run dev` (no `?devlogin`) is unchanged. **Never remove the DEV gate.**
- **Run the Memory shell locally (cr-0030):** `npm run dev` (Vite proxies `/api` → `127.0.0.1:8080`). The active
  tenant is resolved server-side from the **Host** (port stripped); the signed-in user must belong to it. The
  default flow: browse `veyq.localhost:<viteport>` → the tenant `bootstrap:user` created → login → unlock and the
  browse renders. If no tenant claims the Host you get the *“no space”* state (expected).
- **Multiple workspaces in parallel from one dev server (cr-0051):** the dev proxy forwards the **real browser
  Host** (`changeOrigin: false`), so register each tenant under its own `*.localhost` hostname (e.g.
  `workspace-a.localhost`) and browse `workspace-a.localhost:5173` vs `workspace-b.localhost:5173` — each resolves
  its own tenant. `*.localhost` resolves to loopback in browsers without `/etc/hosts` (prefer it over `*.local`,
  which is mDNS on macOS); for another suffix, add it to `allowedHosts` in `vite.config.ts`. `VEYQ_SMOKE_HOST=<host>`
  pins one fixed tenant regardless of the browser URL (headless smoke). **Footgun:** with the real Host forwarded,
  `localhost:5173`, `127.0.0.1:5173` and `veyq.localhost:5173` are three different tenants to the backend — browse
  the one `.run/.env` names. A dev tenant is never an IP literal: the contract's hostname pattern refuses one, so
  `bootstrap:user` refuses an all-digits `VEYQ_TENANT_DOMAIN` before it births anyone, and no seeder writes to the
  database to get around the pattern.

## Conventions
- **Reuse the existing UI, swap only the data layer — never invent new UI.** **Which reference tree, and which
  one to never copy, is the project-wide rule: [`docs/ai/README.md`](../docs/ai/README.md) § Working rules.**
  Read it before porting a screen — pointing at the wrong tree is the mistake it exists to prevent, and the one
  this file used to make.
  The stylesheets are already here — `src/styles/` came from that tree and was then brought under the
  [web UI styleguide](../docs/crosscutting/ui-web-styleguide.md) (raw colours → `--app-*`, dark-mode-aware
  values), so a ported component should *look* like the original but is not a byte-for-byte copy. If a ported
  view lacks a field the contract requires, add it **in-style** rather than hardcode a default.
- **Crypto lives in `../packages/crypto` (`@veyq/crypto`), not here** — one copy, shared with `admin-web/`
  through the workspace ([ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)). `v1` is the
  **conformance-pinned** suite (keep it byte-identical to `../contracts/crypto/vectors/v1.json`; the
  conformance test must stay green), the Model B auth derivation (`deriveAuthSalt` / `deriveAuthKeypair`,
  Ed25519 from Argon2id — ADR 0003) and the cold `accountBirth` live there too, so does the Emergency Kit's
  `veyqkit:` codec (`emergencyKit` — both packages write kits, and two copies drifted apart unseen; the PDF
  writer and the file/QR import stay here), and so does `base64` —
  transport encoding, but `accountBirth` needs it, and a second copy of the encoder is exactly the drift its
  own header warns about. Import via the package (`@veyq/crypto/v1`, `/accountBirth`, `/emergencyKit`,
  `/base64`, `/argonWorker`, `/testArgon`) — never by a relative path back into it. Its check is its own:
  `cd ../packages/crypto && npm run check`, and `npm ci` runs from the **repo root**, not from `web/`.
  **`npm run mutation`** is the assertion-strength gate over the curated crypto-logic scope (StrykerJS, [ADR 0045](../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md))
  — a heavier PR-only crypto job, **not** part of `npm run check`; scope (the single source of truth, an explicit
  per-file list) + config in `stryker.mutate.json` + `stryker.config.mjs`, gated in CI by
  [`mutation.yml`](../.github/workflows/mutation.yml). **There are TWO of those pairs since the package split**
  — `web/`'s (the six crypto-logic files that stayed) and `packages/crypto/`'s (the shared suite);
  run both, because Stryker's runner is the project's own vitest and neither project sees the other's suites.
  With no remote, that CI job has never run, so **a commit that touches `packages/crypto/src` records the
  package gate's exit code** (`npm run mutation -- --force`) in its message. **Footgun: it mutates the real source files _in place_**
  (originals backed up to `.stryker-tmp/backup-*`, restored on exit), so mid-run `git status` shows hundreds of
  transient `M` files + stray `stryker-setup-*.js` — never run other `web/` work (git ops, `npm run check`, an
  edit) against the tree while a mutation run is live; wait for it to finish and restore.
- **Core and application ([ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).**
  The applications are `src/features/memory/` (Memories) and `src/features/files/` (Files: the `FILE` and `FILES_DROPBOX` kinds, their rows in
  the level list, upload, download, the drop box — [docs/apps/files.md](../docs/apps/files.md)) — the guard's `APPLICATIONS`, keyed by owner;
  **core is everything else** under `src/` —
  `app/`, `shared/`, `sw/`, `views/`, `components/app/`, every other `features/*`, and `core/`: `core/node/` (the
  node tree: `tree`, `treeStore`, `location`, `browse`, the delete/trash dialogs, the sealed-meta write loop
  `sealedMetaWrite`), `core/applications/registry.ts`
  (the registry) and `core/sharing/` (the kind-independent half of a public share, the subtree walk and the share management — mint, list, revoke, re-issue, recovery — and the drop key with its guests' identity keys, `dropKey.ts`, since cr-files-application F3: Memories' uploader media and Files' drop-box folders both open through it). Dependencies run application →
  core, never back and never application → application, and `scripts/validate_web_core_imports_no_applications.py`
  (no allowlist) is red on a core file that imports an application or one of its generated clients, and on an
  application file that imports another application or its clients (every `generate:api` output whose `-i` lies in an
  application's directory `contracts/api/<app>/`, read from `package.json` — today `generated/media`, `generated/favorites`,
  `generated/share-media` (Memories) and `generated/files` (Files); so their
  factories are each application's `api.ts`, not `api/client.ts`'s, and a core helper types from core's own client or
  from a literal union of its own, e.g. `mediaDeriver`'s tiers, since core's `BlobRole` is an open token), uses `import.meta.glob`, or names `NodeKind.Memory|Chapter|File` / `'MEMORY'` /
  `'CHAPTER'` / `'FILE'` / `'FILES_DROPBOX'` (a hand list: a new kind is added to the guard's `KIND` with its application),
  or reads an application's i18n namespace — `'memory.…'` / `'media.…'` / `'files.…'` (the guard's `I18N_NAMESPACES`;
  core serves every application's nodes, so its strings are `node.*`/`common.*` and a kind's noun comes from its descriptor). The one exempt file is `src/applications.ts`, the composition root (by exact path, never a
  directory); `main.ts` calls its `registerApplications()` before `boot()`. Build-time only: no glob, no computed
  `import()`, no loading after the fact (ADR 0079 Decision 5, ADR 0026) — registered code runs with every unlocked key.
  **One header for every node (inbox #406).** `core/node/components/NodeHeader.vue` is the header of a folder's level, a file, a drop box and a
  Memory: a leading visual (the kind's `icon`, or the application's `leading` slot — Memories' cover button), the title (`level` 1 on a
  node's own page), an optional `subtitle` and `meta` slot, and the actions on the right. The application only feeds data: its action list
  (`NodeHeaderAction` in `core/node/nodeHeader.ts`: id, label = aria-label + tooltip, icon, handler, `severity` primary|danger, `shown`,
  `disabled`, `pressed`), each `shown` by its capability function — fail closed, a share guest gets this very header with fewer actions
  (inbox #401), never a guest-only one; `standardNodeActions` builds the share/rename/move/delete four. Core owns the look, the wrap
  (the title row wraps, so every action is visible at every width), the keyboard (real labelled buttons) and the dark mode (tokens). Core's
  own actions (a future favorite heart for every node) plug in as `coreActions` (`CoreHeaderAction`, `placement` before|after,
  merged by `mergeHeaderActions`), gated by a core capability function, with no change to any application.

  **One list for the level (inbox #407).** The folder level (`core/node/LevelView.vue`) is ONE list - or one set of tiles - of
  everything at the level: the folders, the Memories, the files, the drop boxes, every kind, with no section headers. **Core
  draws every row and tile** (`core/node/components/LevelRowItem.vue`); an application only supplies ROW DATA. **The order**
  (`core/node/levelList.ts`, a pure function): folders first, then ALL other nodes mixed by name - natural (`Foto 2` before
  `Foto 10`), locale-aware and case-insensitive (`Intl.Collator`), equal names in `compareByCreation` order; the Memory's date
  no longer sorts. A row is `{ icon, leading?, title, meta, primary, actions }` (`LevelRow`, `core/node/levelRow.ts`): the
  leading visual (the kind's icon, or an application component with props - Memories' cover), the name, ONE meta line ("File
  - 157 B - Modified ...", "Memory - date range", "Drop box - 3 people"), the PRIMARY click (what a plain click does: it opens
  the node) and the actions. An action is the header's model (`NodeHeaderAction`: id, label = aria-label, icon, onClick,
  severity, shown, disabled, pressed) plus `first` and `loading`: AT MOST TWO actions marked `first` are real buttons on the
  row (Files: download, Memories: the heart), every other shown action sits in ONE "more" menu (a PrimeVue `Menu`, entries
  `veyq-row-action-<id>`), `shown` by the application's fail-closed capability function - a share guest gets this very row with
  fewer actions, never a guest-only one (inbox #401). Core's own actions (the favourite heart of inbox #409) plug in as the
  level's `coreRowActions`, merged around the application's by `mergeRowActions`, with no change to any application.
  **The tabs are a pure FILTER** (inbox #348, `core/node/levelTabs.ts`): `All | one tab per application`, the tab named by
  its kinds' `list.tabLabel` (kinds sharing a label share a tab, its id the first kind - what `shared/state/useLevelTab`
  stores, per account, one for every share-guest page, never one for the browser, no value written while the account id is
  unknown), over the CURRENT folder's children only; the FOLDERS stay in every tab; a tab is offered where its kinds have a
  node here or the viewer may add one (a kind with an allowed "New" entry), the bar only with two or more tabs; a stored tab
  the level lacks shows as `All` without rewriting it. A tab with nothing says so in its application's own words
  (`list.emptyText`, an i18n key function over the level context), and a level with nothing at all says so ONCE in plain text
  composed from what the viewer may create (`node.levelEmptyNew`), or `node.levelEmpty` - no create tile.

  **What an application registers** (its `register.ts`; the registry is write-once, a second registration throws):
  `registerKind` per node kind (detail component, icon, shareable, `deleteTitle` - the i18n key core's delete dialog
  titles a node of the kind with, else a neutral "Delete", and `deleteCopy`, its sentence - `nounWithArticle`, the noun core's
  create and rename dialogs name a colliding sibling of the kind with ("There is already a file named ...", else "an item") - `emptyInTrash`, the note core's
  trash shows on a node of the kind that holds no attachment - `renameSelectionEnd`, where core's rename dialog ends the
  selection on a name, e.g. a file's before its extension - `placement`, where a node of the kind may be moved (`{ root,
  parents }`, a hint mirroring the backend's `NodeKindRules`; core's move dialog offers nothing for a kind without one,
  inbox #358) - and its `list` of the level list - `{ rows, tabLabel?, views?, emptyText? }`. `rows` is a FACTORY
  (`LevelRowsFactory`): the level calls it ONCE per view, in its setup, with the level's reactive context (`LevelRowsContext`:
  `folder`, `nodes` of every kind that names the factory, `epochs`, `access`, `tree`, the clamped `viewMode`, `liveTick`) and a
  host (`LevelRowsHost`: `open`, `rename`, `move`, `registerAction`), and it returns a `LevelRowSource`: `row(node)` (a row,
  or `null` for a node it cannot draw - core then shows the explicit unsupported row), `notices` (what only the application
  can explain, drawn above the list by `LevelNotices`; Files: why an upload failed) and `presentation(mode)` (the
  application's own presentation of a mode core does not draw, in place of the list: Memories' map - `views` names it, so the
  toggle offers it exactly where a shown tab includes the kind). The factory owns the DATA a row needs and the flows behind its
  actions (Files: the blob listing, re-read on `liveTick`, the upload and replace flows with their confirmation and the
  picker it builds; Memories: covers, dates, the heart), so none of that depends on which tab is shown. Kinds naming the
  same `rows` function share one row source and must agree on `views` (else `registerKind` throws). A kind registered
  without a `list`, or not registered at all, is an unsupported row. For the level's "New" menu (inbox #406) a kind brings
  `createEntry` (`{ label, icon, order, allowed(ctx), enabled?(ctx) }`: the kind's menu entry, its label an i18n key of the
  application's own namespace, `allowed` its fail-closed capability function, and it needs a `createDialog`, which
  `registerKind` checks) and `levelActions` (entries that are no create dialog - Files' "Upload files"; ids write-once,
  `create:` reserved; `acceptsDrop` makes the level hand it the files dropped onto it). The factory registers the action's
  handler with `host.registerAction(id, { enabled, run, drop? })` (`core/node/levelActions.ts`) for the level's life; an entry
  whose handler is not `enabled` is disabled, and a DROP reaches `drop` only while the entry is `allowed` AND its handler
  `enabled` (a drop is never more than the menu entry). The level's toolbar is core's: the "New" menu
  (`registry.levelMenu()` plus core's own folder entry, one PrimeVue button + popup `Menu`, each entry only where its own
  capability function says yes, so a READ guest has none; a create entry emits `create(kind)` and the host mounts the
  `createDialog`), the space's trash, and the view switch over the union of the presentations on screen - core's grid and
  list for every row, plus the shown kinds' `views` (`core/node/levelViews.ts`, inbox #370); the level shows the stored mode
  only where that union offers it, else the grid, without rewriting the stored preference, and hands that mode to every
  factory as `LevelRowsContext.viewMode` - a factory never reads `useViewMode` (inbox #404), `useViewMode` is keyed like the
  tab (`shared/state/scopedPreference.ts`; per browser, not synced). The LIVE REFRESH (inbox #408: `core/node/useLevelLive.ts` over `shared/state/livePoller.ts`, started by the two hosts
  while a folder level is on screen - the member's `MemoryHomeView` through `treeStore.pollTree`, the guest's `ShareView`
  through `GuestAccess.poll` - about every 5 s, only while the tab is visible, paused while a dialog is open, one request
  per level, and a CONDITIONAL one, [ADR 0081](../docs/decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md):
  the member's `fetchTree` (`listNodesRaw`, the scope's tree revision as `ETag`/`If-None-Match`) and the guest's
  `pollPublicShare` (`resolveShareRaw`, a digest of what the link serves - never the scope revision, which would tell a guest
  when the rest of the space changes) read a `304` as "nothing moved": no parse, no decryption, no tick. `read` resolves whether
  the state MOVED and the host's `useLevelLive` hands every row factory `LevelRowsContext.liveTick` only then, which Files'
  factory answers with a blob re-read - an upload completing moves the revision and changes no row; the tree itself is
  replaced only when the rows' fingerprint differs. A guest poll that meets the link gone (`404`) flips `ShareView` to its
  unavailable state, a changed password to the gate; a network error, `429` or `5xx` only backs the loop off), the folder's
  header (core's `NodeHeader`, which every node page opens with too - see above). Its `createDialog` - props `NodeCreateDialogProps` `{kind, scopeId, epochs, parentId,
  parentKey}`, mounted by the shell when the "New" menu or a detail `createChild` emits that KIND; and its
  `shareDialog` - props `NodeShareDialogProps` `{scopeId, nodeId, nodeKey, epochs, nodeName, nodeKind, uploader?}`; a
  kind without one offers no share. There is no guest-only list or detail: core's page of a `READ`/`READ_WRITE` public
  share (`core/sharing/ShareTreePage.vue`) is core's `LevelView` at a folder and the kind's `detail` at any other node -
  the link's root included - handed the guest's access and tree (inbox #401). Core owns the rest: a folder's create and every node's rename are
  `core/node/FolderNameDialog.vue`, which derives the slug by the renamed node's kind; every node's move is
  `core/node/MoveNodeDialog.vue` (inbox #358), mounted by both hosts when a row factory or a detail asks the host to move
  the node, moving through the session's one door (`core/node/move.ts` `nodeMover`: the member's `moveNode`, a `READ_WRITE`
  guest's `placeShareNodeAsGuest`, none otherwise) and explaining each server refusal by its code; and FOLDER's share dialog
  `core/sharing/FolderShareDialog.vue` is pre-registered); `addSlotItem(slot, id, component, order?)` for the two list slots `topbarTray`
  and `sidebarPins` (an id namespaced by the application, e.g. `memories.pinned`; write-once per (slot, id); the shell
  renders `slotItems(slot)` sorted by `order`, ties in registration order — the top bar after core's own upload status
  menu, `core/blob/UploadStatusMenu.vue`, which lists every application's batches of the one core queue and is no
  application's item, so today no application adds to `topbarTray`); `claimRouteSlug(slug, kinds)` — the application's segment in a node's URL fragment path (`#foo/memories/rom`; Memories claims
  `memories`, a second claim of a slug or a kind throws, `RESERVED_ROUTE_SEGMENTS` are refused) — or `claimNoRouteSegment(kinds)`,
  the explicit "no segment" option only Files takes (its nodes are plain names: `#foo/report`; one application at most). The grammar
  — a folder ends in a slash, a claimed slug is reserved among folder and file names (`memories-2`), folders and files are numbered
  apart, no slash resolves to the file before the folder — lives in `core/node/location.ts` (ADR 0015, amended 2026-10-11) and
  nowhere else: `scripts/validate_web_fragment_paths_via_location.py` is red on a file that splits or hand-builds a fragment path;
  `claimShareBody(rootKind, body)` for the drop page of
  a `WRITE_ONLY` link rooted at that kind (`PublicShare.rootKind`) — the one page with no member equivalent, its guest
  holding no key: Memories' photo drop (`MemoryShareBody`), Files' drop box (`DropBoxShareBody`); `FOLDER` is claimed by
  nobody and refused. Inside a guest's identity folder — the served `shareIdentityId`, core's column — no view offers any
  guest a write;
  `registerLifecycle(id, hooks)` with `onScopeReset` / `onScopeEntered` and the **required**
  `onMasterKeyRotated` — `rotateKeys` refuses to start when none is registered, because material sealed under the
  Master Key (Memories' pins) is otherwise undecryptable after a rotation. A kind or share nobody registered renders
  an explicit unsupported/unavailable state, never an empty region. **Who looks** (inbox #401, `core/access.ts`): a
  `detail` (`NodeDetailProps`) and a row factory (`LevelRowsContext`, which also carries the level's `viewMode`) are handed `access` — `Access`, a member
  (`scopeId`, `epochs`, `readonly` = pending deletion, the instance's `publicLinks`/`uploaderLinks`) or a share guest
  (`shareId`, `role`, `head`, `pwToken`, `fragmentKey`, `requestGuest`, `reload` — `guestAccess`, built by
  `views/ShareView.vue`, which owns the re-resolve after a guest's write and the page's one naming flow, core's `core/sharing/GuestNaming.vue` with its "Not you?"; every naming place uses that one
  component) — and `tree`, a `TreeSource` (`node`,
  `children`, `keyOf`, `nameOf`, `inIdentityFolder`, `linkTo`: every navigation goes through it, so a guest's link keeps
  `#k=`); the member's come from `memberAccess`/`memberTreeSource`, built by the host, the guest's tree from
  `guestTreeSource` (`core/sharing/guestAccess.ts`: a served `ShareNode` as a `TreeNode` — `createdAt` 0, so equal
  names number by node id; `metaVersion` 0; `wrappedMeta` as served). A row factory built for a guest reads nothing of
  the member's world (`treeStore`, `activeSpace`, the cover and pin stores) — Memories' rows read their dates, covers
  and map from the guest's tree (`features/memory/guestCover.ts`), Files' rows read and write through Files' data
  source (`features/files/source.ts`: the member's or the guest's, fixed at mount; a write is offered where the matrix
  grants it AND the source carries it — `sourceCapabilities` — and the guest's source has no delete, no rename), and so
  does Memories' detail (`features/memory/source.ts`, picked by `MemoryDetailHost`: the detail, its composables, the
  lightbox's loaders and — by injection, `useMemoryView`, which throws outside a detail — the organizer touch no API but
  through it; the guest's has no trash, node meta, cover, marks, buckets, live poll or EXIF, and its delete is a purge of
  its own). A drop page (`ShareBodyProps`) is handed the
  guest's `access` too and names its uploader on the page (the identity key is its application's). What a view offers is its
  application's pure, fail-closed capability function over `access` and `targetOf(tree, node)` — `memoryCapabilities`
  (`features/memory/capabilities.ts`), `filesCapabilities` (`features/files/capabilities.ts`), core's
  `folderCapabilities` for the level view; each matrix is its `docs/apps/<app>.md` table — never a `readonly`,
  `shareable` or `hasCapability` read inline; `targetOf` carries the kind's descriptor (`shareable` AND a `shareDialog`),
  so no share button appears whose dialog the host cannot mount.
  **A share view is the member view with a source and capabilities — never add a guest-only UI** (owner principle
  2026-10-09, inbox #401). A guest's function goes into the member's component as a door of the application's data
  source and a right of its capability function; a member-only function must not leak into share mode (no door on the
  guest's source, false in the guest's column, and the guest-mode mount test of that view records it). The guest-only
  exceptions are exactly the ones with no member equivalent: the shell's gates (password, terms), core's one naming
  component with "Not you?" (`GuestNaming`, the shell's dialog behind `access.requestGuest`), the curator-note banner, the
  role badge and the breadcrumb up to the link's root (`ShareTreePage`), and the two `WRITE_ONLY` drop pages
  (`*ShareBody.vue`, claimed — `scripts/validate_web_share_bodies_are_drop_pages.py`). Every other separate guest body
  drifted from its member twin (#356, #361, #363) and was deleted. **Limits, stated:** no slot is single-claim any
  more (the dialogs are per kind since cr-files-application P4c; the two slots `topbarTray`, `sidebarPins` are ordered
  lists); the level view (its list items `veyq-level-item`) and core's `FolderNameDialog` carry core `veyq-*` classes (`styles/ui.css`, dark twins in
  `dark.css`) and core `node.*` i18n keys since cr-files-application P4d, which Memories' own dialogs and detail read
  too for the shared labels; the kind is an open string token (the generated client has no `NodeKind` enum), so core names its one
  `FOLDER_KIND` (`core/node/kind.ts`), each application its own (`features/memory/kinds.ts`, `features/files/kinds.ts`), and no compiler lists the
  kinds — the registry's `{ supported: false }` answer and its tests do; the directory is still `features/memory`, the i18n catalog
  is one file and `styles/memory.css` is global.
  **Testing against it:** `src/test/setup.ts` fills the registry in a `beforeAll` *dynamic* import (a static one
  evaluates the application before the test file's `vi.mock`s and disarms them — 119 files went red). A
  node-environment suite gets no registry, so one that exercises a hook registers the real `memoriesLifecycle`
  itself (`activeSpace`, `coverStore`, `rotateKeys` tests). A suite that stubs the registry delegates through a
  `Proxy` onto a fresh `createRegistry()` (`ShareView.registry.test.ts`, `MemoryHomeView.registry.test.ts`): a
  hand-listed set of methods silently misses the next one the registry gains.
- **Wire encoding:** opaque fields are **standard base64** (`b64std`), not base64url — the contract pins
  `[A-Za-z0-9+/]*={0,2}`, so base64url is rejected with 400 at the boundary.
- **Operator-blind:** seal/derive everything client-side; never send the password, plaintext or raw keys.
  See [security model](../docs/crosscutting/security-model.md) and
  [account & key model](../docs/crosscutting/account-key-model.md).
- **`jsqr` is a knowingly-stale dependency — don't "fix" it, don't widen it.** The root `CLAUDE.md` forbids
  *keeping* a dep abandoned by its maintainers; this is the one granted exception, so its reason lives here
  and not in a commit nobody reads. *Measured 2026-08-13:* `cozmo/jsQR` is neither archived nor
  npm-deprecated, but its default branch's HEAD is from **2021-08-24**, its last release from **2021-04-24**,
  and its newest issue is an unanswered decoder correctness bug. **Its `pushed_at` reads 2026 and is a false
  liveness signal** — that field fires on a push to *any* ref, so measure a default branch's HEAD, never
  `pushed_at`; the same trap voids any replacement candidate checked the lazy way. Every alternative is worse
  *here*: `qr-scanner` is the **same** Cosmo-Wolfe ZXing port with a 2022 HEAD; `barcode-detector` is
  genuinely maintained but ships a ~1 MiB ZXing-C++ wasm **and fetches it from a jsDelivr CDN by default**,
  which breaks a self-hosted instance and hands a third party the timing of every scan — a real
  self-hostability regression traded for a theoretical decoder one; and the native `BarcodeDetector` is absent
  from Firefox entirely and from desktop Chrome on Linux/Windows, so it removes no dependency at all. What
  bounds the risk is the input class: we only ever decode QR codes **our own client produced** — the three
  markers `veyqkit:`, `veyqpair:` and `veyqsignin:`, all of which stay far below the known version-23 defect
  (*measured:* a 44-char secret gives **version 3** under `veyqpair:` and **version 4** under the two-character-
  longer `veyqsignin:`). **The bound is a property of what the payloads CARRY, not of how many markers there
  are:** a marker plus a 32-byte secret is ~55 characters, while the transport key `veyqsignin:` deliberately does not
  carry would be 1260 bytes and **version 28** — so the thing that would void this exception is a payload
  growing a key, not a fourth marker. Pure JS is memory-safe
  so a decoder bug is a wrong string rather than an exploit, and the decoded value is validated immediately
  against the contract-pinned `[A-Za-z0-9+/]*={0,2}`. **Revisit on any of three triggers:** we need to decode
  a *foreign* QR code, jsQR gets a CVE, or `BarcodeDetector` reaches Firefox and desktop Chrome.

Stack: Vue 3, PrimeVue, vue-router. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
