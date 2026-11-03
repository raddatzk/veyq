# web (Vue 3 + its own UI layer on Reka UI)

The web client. Loaded in addition to the root `CLAUDE.md` when working here. All content crypto runs
here; the server is blind.

## Prove your work
- `pnpm run check` (lint + typecheck + test + build).
- API client is **generated** and **not tracked** (gitignored, mirrors the backend) — `pnpm run generate:api`
  from the specs under `../../contracts/api/` (core's at its top level, the Memories ones in `memories/`, one client
  directory per spec) produces it (and runs automatically on install via `prepare`). Never
  hand-write the client.
- **`pnpm run check` does NOT regenerate the client — so on its own it proves nothing about a contract change.**
  It runs lint/typecheck/test/build over whatever `src/api/generated/` already holds, which is the *previous*
  spec. Change `contracts/api/` and a green `check` is a green run against stale code: run **`pnpm run
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
  runs too, and only a real DOM click sees it: the component library's breadcrumb (before ADR 0107) rendered an item
  without a real `url` as `<a href="#">`, whose default ran after the `command` and cleared the `#k=` fragment that
  carries a share key — every guest write after a reload then failed, while the unit test that asserted the
  `router.push` arguments stayed green (found only in a real browser, 2026-10-07). The layer's `VeyqBreadcrumb` makes
  an entry with a `url` a real link and runs its `command` in place of a plain click's default; an entry still needs
  that `url`. A core page that keeps the share key in the URL fragment
  navigates with `hash: route.hash` and gives every link a real `href`; its test mounts the real component and the
  real router and clicks the rendered element (`ShareTreePage.breadcrumb.test.ts`, `ShareTreePage.test.ts`,
  `FolderShareGuest.test.ts`).
- **A Reka primitive answers the press, not the click.** A layer tab switches on a left-button `mousedown` (and on
  focus), a select opens on `pointerdown`, a hover card opens on its trigger's `pointerenter` and closes only on a
  document `pointermove` outside it; a test that sends `click` stays green while nothing switched — the pending-deletion
  case of `MemoryDetail.rights.test.ts` asserted an absence on the wrong tab until ADR 0107's swap showed it. Send the
  event the primitive listens to (`trigger('mousedown')` for a tab), and before asserting that something is absent,
  assert something only the target state shows. A list or panel a primitive portals lands in `document.body`: mount
  with `attachTo: document.body` and query `document`. `src/admin-web/` has the same primitives under its own layer.
- **A guest-mode mount proves it calls no authenticated client** (inbox #401): mock each client module through
  `src/test/guestMode.ts`'s `guardedClients` (`vi.mock('…/api/client', async (o) => (await import('…/test/guestMode'))
  .guardedClients(await o()))` — the public factories become `vi.fn`s to stub, every other one throws and is recorded)
  and assert `authenticatedCalls` stayed empty; a call is then red even where the view swallowed the error.
- **Moving markup silently disarms every negative assertion that names it.** `find(old).exists()).toBe(false)` stays green
  on a selector nothing draws any more: when Depot's uploader line moved into core's header, `FileDetail.guest.test.ts`'
  absence of `data-testid="file-uploader"` passed while asserting nothing (2026-11-03). After a move, grep the old test ids
  and classes across the tests and re-point each negative at the new markup, beside a positive control.
- **`vi.mock` resolves by path, so moving a module silently disarms every mock of it.** A mock left on the old
  path stubs nothing and the real module runs — and a passthrough mock (`withRefresh: (f) => f()`) is green
  either way, so no red announces it. After a move, prove the new mocks bite by sabotaging the *real* module
  (make the export throw): every suite that mocks it must stay green, and the module's own test must go red.
- **Dev credentials come from the seeder — never hardcode or guess them.**
  - **`pnpm run bootstrap:user` (the local principal).** Inputs live in **`<repo-root>/.run/.env`**
    (gitignored — note it is at the **repo root**, not `src/web/.run/`; template `<repo-root>/.run/.env.example`):
    `VEYQ_EMAIL` / `VEYQ_PASSWORD` / `VEYQ_TENANT` / `VEYQ_TENANT_DOMAIN`, and the seeder writes back
    `VEYQ_SECRET_KEY` + `VEYQ_WRAPPED_MASTERKEY`. It creates ONE user that is Server Owner AND tenant owner
    (holds the **TENANT grant** + a personal scope). This is the current dev instance (`./gradlew backend:seed`
    from the repo root) — the tenant is **`veyq.localhost`** (e.g. "Familie Raddatz"), so **live-verify browses
    `veyq.localhost:<viteport>`** and logs in with the `.run/.env` creds/secret key. **Cold start (fresh/empty
    DB):** the `.run/.env` key material is stale after a `docker compose … down -v` (it was written against the
    old DB), so **re-run `pnpm run bootstrap:user`** against the fresh backend first — it reseeds
    `veyq.localhost`, points the tenant at Mailpit so member invites can actually be sent, and rewrites
    `WRAPPED_MASTERKEY` — then browse `veyq.localhost:<viteport>`. **`SECRET_KEY` is re-used, not re-minted**,
    when `.run/.env` already holds a usable one, so the kit you copied into a browser survives a re-seed
    (`buildAccount(..., { secretKey })` — dev bootstrap only; every real birth mints fresh entropy). Against a stale
    or wrong DB, `POST /api/login/verify` answers **401** (password-derived auth doesn't match the registered
    identity) — not a crypto bug; re-seed a fresh instance.
    - **It is an API client like any other** and calls the backend through the generated `…Api` clients, built in
      `scripts/apiTransport.mts` — the one file under `scripts/` that may use `fetch` or `node:http` (lint:
      `veyq/seeders-use-the-generated-client`), and `pnpm run typecheck` covers `scripts/` (`tsconfig.scripts.json`).
      When it hand-wrote its bodies, login verify gained `nonce`, the seeder kept the old shape and failed its own
      login with 400. Tenant-scoped calls go over `node:http` as the client's `fetchApi`, because the backend
      resolves the tenant from `Host` and fetch drops a hand-set one; that works for any domain, resolvable or not.
    - **It needs the bundled object store in use before it creates the tenant** (ADR 0089): the store is a
      Server-Owner setting with a passed check, and `createTenant` answers `409 store_not_ready` until one passed.
      The backend sets the bundled store and checks it itself after its start (decision 3), so the seeder usually
      finds it passed; where it has not (yet), the seeder saves it and checks it once more. Either check PUTs a probe
      through the **public origin the backend signs for** (`VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT`, the tenant domain on
      the browse port), so **the Vite that serves that port must be up**: the backend's own check retries for about
      ten minutes, the seeder's does not, and when it fails with `PROBE_PUT … timed out`/`ConnectException` the owner
      already exists, so the only way back is a wipe (`bootstrap:user` refuses an instance that is already set up).
      Seed with Vite running, never before.
    - **It writes the owner's material as soon as the owner is complete**, before the optional second user — a
      failure in an optional step warns and leaves a usable owner behind. It used to write last, so a failing
      user2 threw away the Secret Key of an owner that already existed in the DB (irrecoverable: a wipe + re-seed
      was the only way out).
    - **DEV auto-unlock (cr-dev-auto-unlock) — reach the unlocked dev app without typing the password.** Browse
      `veyq.localhost:<viteport>/?devlogin` and `boot()` logs in + unlocks from `.run/.env` via the app's real
      `login`+`unlockMasterKey` (then enrols the device key, so later loads restore without the param). For a phone,
      or an agent barred from entering credentials into a field. **Strictly dev-only:** the creds come from an
      `apply:'serve'` Vite middleware (`/__dev/seed-credentials`, never built) and the consumer is
      `import.meta.env.DEV`-gated (tree-shaken); `pnpm run verify:no-backdoor` fails the build if either sentinel
      reaches `dist/`. A plain `pnpm run dev` (no `?devlogin`) is unchanged. **Never remove the DEV gate.**
- **Run the Memory shell locally (cr-0030):** `pnpm run dev` (Vite proxies `/api` → `127.0.0.1:8080`). The active
  tenant is resolved server-side from the **Host** (port stripped); the signed-in user must belong to it. The
  default flow: browse `veyq.localhost:<viteport>` → the tenant `bootstrap:user` created → login → unlock and the
  browse renders. If no tenant claims the Host you get the *“no space”* state (expected).
- **Multiple workspaces in parallel from one dev server (cr-0051):** the dev proxy forwards the **real browser
  Host** (`changeOrigin: false`), so register each tenant under its own `*.localhost` hostname (e.g.
  `workspace-a.localhost`) and browse `workspace-a.localhost:8081` vs `workspace-b.localhost:8081` — each resolves
  its own tenant. `*.localhost` resolves to loopback in browsers without `/etc/hosts` (prefer it over `*.local`,
  which is mDNS on macOS); for another suffix, add it to `allowedHosts` in `vite.config.ts`. `VEYQ_SMOKE_HOST=<host>`
  pins one fixed tenant regardless of the browser URL (headless smoke). **Footgun:** with the real Host forwarded,
  `localhost:8081`, `127.0.0.1:8081` and `veyq.localhost:8081` are three different tenants to the backend — browse
  the one `.run/.env` names. A dev tenant is never an IP literal: the contract's hostname pattern refuses one, so
  `bootstrap:user` refuses an all-digits `VEYQ_TENANT_DOMAIN` before it births anyone, and no seeder writes to the
  database to get around the pattern.

## Conventions
- **Reuse the existing UI, swap only the data layer — never invent new UI.** **Which reference tree, and which
  one to never copy, is the project-wide rule: [`docs/ai/README.md`](../../docs/ai/README.md) § Working rules.**
  Read it before porting a screen — pointing at the wrong tree is the mistake it exists to prevent, and the one
  this file used to make.
  The stylesheets are already here — `src/styles/` came from that tree and was then brought under the
  [web UI styleguide](../../docs/crosscutting/ui-web-styleguide.md) (raw colours → `--app-*`, dark-mode-aware
  values), so a ported component should *look* like the original but is not a byte-for-byte copy. If a ported
  view lacks a field the contract requires, add it **in-style** rather than hardcode a default.
- **Crypto lives in `../packages/crypto` (`@veyq/crypto`), not here** — one copy, shared with `src/admin-web/`
  through the workspace ([ADR 0065](../../docs/decisions/0065-admin-ui-cli-separate-service.md)). `v1` is the
  **conformance-pinned** suite (keep it byte-identical to `../../contracts/crypto/vectors/v1.json`; the
  conformance test must stay green), the Model B auth derivation (`deriveAuthSalt` / `deriveAuthKeypair`,
  Ed25519 from Argon2id — ADR 0003) and the cold `accountBirth` live there too, so does the Emergency Kit's
  `veyqkit:` codec (`emergencyKit` — both packages write kits, and two copies drifted apart unseen; the PDF
  writer and the file/QR import stay here), and so does `base64` —
  transport encoding, but `accountBirth` needs it, and a second copy of the encoder is exactly the drift its
  own header warns about. Import via the package (`@veyq/crypto/v1`, `/accountBirth`, `/emergencyKit`,
  `/base64`, `/argonWorker`, `/testArgon`) — never by a relative path back into it. Its check is its own:
  `cd ../packages/crypto && pnpm run check`, and `pnpm install` runs from the **repo root**, not from `src/web/`.
  **`pnpm run mutation`** is the assertion-strength gate over the curated crypto-logic scope (StrykerJS, [ADR 0045](../../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md))
  — a heavier PR-only crypto job, **not** part of `pnpm run check`; scope (the single source of truth, an explicit
  per-file list) + config in `stryker.mutate.json` + `stryker.config.mjs`, gated in CI by
  [`mutation.yml`](../../.github/workflows/mutation.yml). **There are TWO of those pairs since the package split**
  — `src/web/`'s (the six crypto-logic files that stayed) and `src/packages/crypto/`'s (the shared suite);
  run both, because Stryker's runner is the project's own vitest and neither project sees the other's suites.
  With no remote, that CI job has never run, so **a commit that touches `src/packages/crypto/src` records the
  package gate's exit code** (`pnpm run mutation -- --force`) in its message. **Footgun: it mutates the real source files _in place_**
  (originals backed up to `.stryker-tmp/backup-*`, restored on exit), so mid-run `git status` shows hundreds of
  transient `M` files + stray `stryker-setup-*.js` — never run other `src/web/` work (git ops, `pnpm run check`, an
  edit) against the tree while a mutation run is live; wait for it to finish and restore.
  **A change to the bytes a seal writes** (another envelope, a salt, one more random draw) also moves
  `features/memories/uploadWire.characterisation.test.ts`'s snapshots, whose randomness is ONE pinned stream: re-bless
  them with `-u` and read the diff against what its header says may move (the field seal's salt shifted every later
  wrap and digest, ADR 0093 § 9).
- **Core and application ([ADR 0079](../../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).**
  **Around a node is core's, behind it the application's** (owner 2026-11-01, ADR 0079 decision 3): what every node has
  (create, rename, move, delete, trash, keys, rights, links, cover, favorite, report, export envelope, the live tree) is a
  core building block an application uses or explicitly replaces, never one it rebuilds; what is behind its node (its
  data model, formats, records, operations, screens, validation, arithmetic) is the application's and shared with no other,
  not even as a core template. Data management is the exception: an application's stored blobs go through core's blob
  service (ADR 0112: `core/blob/applicationBlobs.ts` is its transport — the upload and completion, the paged and tagged read, the bytes, a moderator's case read; Expenses' receipts its first application). A shared helper that owns nothing is allowed, never required.
  The applications are `src/features/memories/` (Memories) and `src/features/depot/` (Depot: the `FILE` and `DEPOT_DROPBOX` kinds, their rows in
  the level list, upload, download, the drop box — [docs/apps/depot.md](../../docs/apps/depot.md)) and `src/features/expenses/` (Expenses: the
  `EXPENSES_POT` kind — `domain/`: the entry codec, the ledger's arithmetic and check, the reads and writes; around it the
  Pot page (`components/PotDetail.vue`, a member's and a link guest's alike, rights in `rights.ts`), its session
  (`potSession.ts`: the live read, the writes, the stale re-read), the Pot's settings in its sealed meta (`domain/potMeta.ts`,
  `contracts/expenses/pot-meta.v1.schema.json`), an entry's receipts (`receipts.ts` Expenses' view of core's blob service, `core/blob/applicationBlobs.ts` — a listed blob as a receipt of its entry, the role and its cap —, `domain/receiptRules.ts` the rules, `components/EntryReceipts.vue` and `ReceiptView.vue`, which draws a PDF through core's `shared/pdf/` and decides by the bytes), the re-key, download and report of an entry) — the guard's `APPLICATIONS`, keyed by owner;
  **core is everything else** under `src/` —
  `app/`, `shared/`, `sw/`, `views/`, `components/app/`, every other `features/*`, and `core/`: `core/node/` (the
  node tree: `tree`, `treeStore`, `location`, `browse`, the delete/trash dialogs, the sealed-meta write loop
  `sealedMetaWrite`), `core/applications/registry.ts`
  (the registry) and `core/sharing/` (the kind-independent half of a public share, the subtree walk and the share management — mint, list, revoke, re-issue, recovery — and the drop key with its guests' identity keys, `dropKey.ts`, since cr-files-application F3: Memories' uploader media and Depot's drop-box folders both open through it). Dependencies run application →
  core, never back and never application → application, and `scripts/validate_web_core_imports_no_applications.py`
  (no allowlist) is red on a core file that imports an application or one of its generated clients, and on an
  application file that imports another application or its clients (every `generate:api` output whose `-i` lies in an
  application's directory `contracts/api/<application>/`, read from `package.json` — today `generated/media`, `generated/item-favorites`,
  `generated/share-media` (Memories), `generated/depot` (Depot) and `generated/expenses` (Expenses); so their
  factories are each application's `api.ts`, not `api/client.ts`'s, and a core helper types from core's own client or
  from a literal union of its own, e.g. `mediaDeriver`'s tiers, since core's `BlobRole` is an open token), uses `import.meta.glob`, or names `NodeKind.Memory|Chapter|File` / `'MEMORY'` /
  `'CHAPTER'` / `'FILE'` / `'DEPOT_DROPBOX'` / `'EXPENSES_POT'` (a hand list: a new kind is added to the guard's `KIND` with its application),
  or reads an application's i18n namespace — `'memory.…'` / `'media.…'` / `'depot.…'` / `'expenses.…'` (the guard's `I18N_NAMESPACES`;
  core serves every application's nodes, so its strings are `node.*`/`common.*` and a kind's noun comes from its descriptor). The catalogs sit where they belong: core's names no application namespace, an application's exactly its own, and no application reads another's keys. `src/applicationMessages.ts` (the composition root's catalog half) may import an application's catalog and nothing else of it; `main.ts` calls `registerApplicationMessages()` then `registerApplications()`, and `src/test/setup.ts` calls the first statically for every test file, node environment included. The one exempt file is `src/applications.ts`, the composition root (by exact path, never a
  directory); `main.ts` calls its `registerApplications()` before `boot()`. Build-time only: no glob, no computed
  `import()`, no loading after the fact (ADR 0079 Decision 5, ADR 0026) — registered code runs with every unlocked key.
  **People and participants (ADR 0083, `uc.memory.participants`).** A Memory lists who was there as OPAQUE USER IDS in its sealed meta
  (`participants: { userIds }`, `features/memories/memoryParticipants.ts`; cap 100 - `wrappedMeta` is limited to 8192 chars). The id list is the only
  thing stored: **never a name or a snapshot of one** - a share serves the node's meta unchanged and its guest holds `K_node`, so the
  guest exclusion is the interface's (`memoryRights().participants` is false for every share role, fail-closed) and the ids are what keep the
  residual harmless; if participants ever carry names, ADR 0083 decision 4 is open again. The write merges on `409` through
  `editParticipantsOf` (`core/node/nodeMeta.ts`), not `editField`. Names come from `core/people/directory.ts` (real name, else username, from the
  TENANT's member list). A person's profile is the fragment `#people/<userId>` (`parseHash().person` / `personHash` in `location.ts`, the one door;
  `people` is in `RESERVED_ROUTE_SEGMENTS` and numbered away from a root folder's name); core draws `core/people/PersonProfile.vue` and an application adds a
  section with its registrar's `app.personSection` (Memories: `PersonMemories.vue` over `participantIndex.ts`, which holds Memory keys and so is dropped on
  every scope reset). A share view never links to a profile.
  **A member's profile picture (ADR 0084, `uc.spaces.set-profile-picture`).** A sealed attribute of the per-tenant profile row, a `member-avatar`
  field under `K_tenant` (`sealField`, ADR 0093 § 9; `features/account/profile/memberAvatar.ts`: 256 px WebP in the tenant icon's constant 16 KiB pad (shown at 128 px on the profile page), 21908 base64 chars = the contract's
  `maxLength`, pinned by a test that reads the YAML). The directory carries only `avatarRevision`; `core/people/avatarCache.ts` fetches
  `getMemberAvatar` ONLY for a non-zero revision it does not hold, keeps ONE object URL per user (revoked on a new revision, a 404, `resetPeopleDirectory`)
  and shows the letter for anything that does not open or is no image - **never fetch or draw a member's picture on a share page**
  (`ShareView.memory.test.ts` records every export of the cache and the directory). Draw a person with `core/people/PersonAvatar.vue`
  (`:user-id`), never a bare `VeyqAvatar`. Name and picture are edited by ONE model + component (`useNameAndPicture` /
  `NamePictureFields`) in Settings > Your profile and on one's OWN profile page only - no edit control on another person's page.
  **One header for every node (inbox #406, [ADR 0110](../../docs/decisions/0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md)).** `core/node/components/NodeHeader.vue` is the header of a folder's level, a file, a drop box, a
  Memory and a Pot, and **core draws it, never an application** (owner 2026-11-03, decision 10; `validate_web_core_imports_no_applications.py`'s
  `header` rule keeps `NodeHeader.vue` out of `features/<owner>`): `NodePage` (a `.veyq-node-page`, the inline-size container an application's
  banner answers to) draws it above the kind's `detail` from the descriptor (`registry.kind(node.kind)`: `icon`, `cover`, `header.order` -
  `KindHeader`; FOLDER's pre-registered, which `LevelView` reads for the folder's) and from what the detail CONTRIBUTES as data, `contributeNodeHeader(() =>
  ({ subtitle?, actions?, facts?, banner? }))` (`core/node/nodeHeaderContribution.ts`; a no-op without core's page, so a detail mounted bare draws no
  header; the page re-reads the builder as the detail's state moves, and shows it one render after the detail mounted - a test waits a tick). A
  FACT (`NodeHeaderFact`, `core/node/nodeHeader.ts`) is a `date`/`place` chip (`label`, `empty`, `onClick` = the application's editor, `actionLabel`,
  `disabled`; consecutive chips one row), `people` (`label`, `people: { userId | null, name, former? }[]`, `caption`, `status`; drawn by
  `core/people/PersonGroup.vue` - one person avatar + name, several three overlapping avatars or two + "+N" with a hover card listing everyone)
  or `text`; a BANNER is `{ component, props }`, drawn above the row (Memories'). **People link to `#people/<id>` and show a member's picture only
  where `linkPeople` says so** - `NodePage` sets it for a member's access alone, the prop's default is false, so a share page links nobody
  whatever an application hands it. The leading visual is core's: a kind with a `cover` shows the node's cover (`NodeCoverImage`, the view's
  resolved covers) or, without one, the icon tile, and where `cover.canSet(node)` it is the button that opens core's cover dialog (hint on hover and
  focus); a kind without one, the icon tile. Then the title (the page's one `h1`,
  a folder's level view included, and a space's top level is headed by the space's name, `LevelView`'s `spaceName` — styleguide § Heading levels; the full name its tooltip), the subtitle and the facts, and the actions. **Two layouts,
  picked by the header's own width** (`headerLayout`, `core/node/nodeHeader.ts`; no viewport query): **wide** while the leading visual's wide width,
  the name's minimum (12rem), the floor's two buttons and the ⋯ fit in one row — the name ONE line at its natural width, the actions at the right end
  of its row, yielding to the ⋯ down to the kind's first two shown actions (`headerFloor`), only then the name shortened with "…" — else **compact**
  (`.is-compact`): a 64 px cover (or a 42 px icon tile) beside a name of two lines at most, the actions in a row of their own. The answer
  reads sizes no layout changes — the leading visual's registered `--veyq-node-header-leading-wide` (every cover 244 px, the icon tile 4rem,
  `styles/ui.css`), `--veyq-node-header-name-min`, the column gaps — never the name or the layout in force,
  so it cannot flip. The application only feeds data: its action list
  (`NodeHeaderAction` in `core/node/nodeHeader.ts`: id, label = aria-label + tooltip, icon, handler, `severity` primary|danger, `shown` - REQUIRED, so an
  action nobody gated does not compile, and a row's `LevelRowAction` inherits it -,
  `disabled`, `pressed`, `menuOnly`), each `shown` by its rights function — fail closed, a share guest gets this very header with fewer actions
  (inbox #401), never a guest-only one; `standardNodeActions` builds the share/rename/move/delete four - core's defaults, which
  every kind's page takes from it with its rights answer as the flags (a kind with its own implementation passes its own
  action of that id instead, as the row's rule below); and its kind's priority, the descriptor's `header.order` (the
  ids first as buttons, core's included; an unlisted one follows, never vanishes). **As many as fit are buttons, the rest one ⋯ menu:** core
  measures the row (on mount, on each resize of the header or the name, `fitHeaderActions` the pure count; unmeasured, as in every unit test, the
  header is wide and each action that may be a button is one; an e2e spec reaches an action through `tests/e2e/src/header.ts`, never a button by name) and puts the rest, then the menu-only ones (a `danger` action is one, core's report sets `menuOnly`: report, then delete) behind
  a separator, into one `VeyqMenu` (`actionMenuItems`, `core/node/actionMenu.ts`, the entries the level rows share). Delete and report are never
  buttons. Core owns the look, the fit, the keyboard (real labelled buttons, the layer's menu) and the dark mode (tokens). Tests reach the two
  halves through `src/test/nodeHeader.ts` (`headerButtonIds`, `headerMenuIds`, `chooseHeaderEntry`; `mountWithHeader` mounts a detail under core's
  header as `NodePage` draws it, with `emitted` reading the detail's events). **Core's own actions come
  centrally** (owner 2026-11-01, ADR 0110 decision 9): the heart, the cover, "Copy to…", the ZIP download and Report of every
  node are built in ONE place, `core/node/coreNodeActions.ts` (`useCoreNodeActions`: each gated by its core rights function,
  fail closed; `copy` / `download` are the page's doors, an absent door is an action not offered), which a core view
  provides (`provideCoreNodeActions`): the level (`LevelView`: every row and the folder header) and core's node page
  `core/node/components/NodePage.vue` - both hosts (`views/SpaceView.vue`, `core/sharing/ShareTreePage.vue`) mount
  every kind's `detail` through it, never bare; it mounts the dialog of core's cover action.
  `NodeHeader` takes the REQUIRED `node` it heads (null only for the space's top level) and merges core's actions for it
  (`CoreHeaderAction`, `placement` before|after|{after: id}, `mergeHeaderActions`) with the application's; a page builds,
  lists and forwards none, so it cannot leave Report out (a legal duty) - `NodePage.report.test.ts` walks every
  registered kind's page and the folder level, and that a detail mounted bare draws no header. A test of what core adds to a page mounts it through `src/test/nodePage.ts`
  (`mountNodePage`; `coreActionsStandIn` + `coverStandIn` for a header test). A kind's `order` may still name a core id. Deleting from a
  header is a row's delete: the host asks core's `DeleteNodesDialog` for the one node (its kind's question,
  `core/node/deleteQuestion.ts`, and the one Undo) and leaves the page for its parent once the node went.

  **One host contract (owner 2026-11-01, `core/node/nodeHost.ts`).** Whatever draws a node asks the page around it through
  ONE set of doors, `NodeHost`: `open`, `rename`, `move`, `moveMany`, `delete` (one node, with the kind's own write where it
  has one), `deleteMany`, `share(node, offer)` (`NodeShareOffer`: `contribute`, or the `uploader` variant), `createChild(kind,
  parent)`, `download`, `copy`, `importArchive`, `openTrash`. **Every door may be absent, and absence is the answer** - the
  action is not offered (fail closed), whatever a rights function says: a view narrows its rights by the doors (`hostRights`)
  and binds its standard actions through them (`hostHandlers`); core's row defaults and core's own actions take the same
  doors. The host page provides it (`provideNodeHost`); `LevelView`, `NodePage` and so every kind's `detail` inject it
  (`injectNodeHost`; mounted bare, as in a unit test of a detail, every door is absent), and a row factory gets the same doors
  as its `LevelRowsHost` (which extends `NodeHost` with what only a level has: `registerAction`, the covers, the upload
  screen). A detail emits nothing to the host. The dialogs behind the doors are core's ONE set, `core/node/nodeDialogs.ts`
  (`useNodeDialogs(env)`: who looks, the tree, the node on screen, what the page does after a write) +
  `core/node/components/NodeDialogs.vue`, which both pages mount, so each dialog exists once. The two pages differ only in
  the doors they hand on: the member's space page (`views/SpaceView.vue`) every one; a guest's (`ShareTreePage`) `open`, the
  download, the create (core's naming of the new node after the page's naming flow) and - for a `READ_WRITE` link -
  the move. A test mounts a view as a page would with `src/test/nodeHost.ts` (`recordingHost`, `hostFor(access)`; a
  detail through `mountNodePage`, which records the calls).

  **One list for the level (inbox #407).** The folder level (`core/node/LevelView.vue`) is ONE list - or one set of tiles - of
  everything at the level: the folders, the Memories, the files, the drop boxes, every kind, with no section headers. **Core
  draws every row and tile** (`core/node/components/LevelRowItem.vue`); an application only supplies ROW DATA. **The order**
  (`core/node/levelList.ts`, a pure function): folders first, then ALL other nodes mixed by name - natural (`Foto 2` before
  `Foto 10`), locale-aware and case-insensitive (`Intl.Collator`), equal names in `compareByCreation` order; the Memory's date
  no longer sorts. A row is `{ icon, leading?, title, meta, primary, actions }` (`LevelRow`, `core/node/levelRow.ts`): the
  leading visual (the kind's icon, or an application component with props - Memories' cover), the name, ONE meta line (the kind's noun in `meta`, then the application's
  `details`: "File - 157 B - Modified ...", "Memory - date range - place", "Drop box - 3 people"; each detail an `{ id, text }` the application
  LEAVES OUT when the node has no such fact - never a placeholder - and core joins, wraps and styles, `levelRowDetails`; the profile
  page's Memories list draws the same `LevelRowItem` with the same builder and offers the level's rows/tiles/map switch, `ViewModeToggle`
  over `useViewMode` - the map is the level's `SpaceMap`, the views `memoryViews.ts`, inbox #417 -, [docs/apps](../../docs/apps/README.md#how-an-application-shows-facts-in-a-levels-rows-and-tiles)), the PRIMARY click (what a plain click does: it opens
  the node) and the actions. An action is the header's model (`NodeHeaderAction`: id, label = aria-label, icon, onClick,
  severity, shown, disabled, pressed) plus `first` and `loading`: AT MOST TWO actions marked `first` are real buttons on the
  row (Depot: download, every kind: core's heart), every other shown action sits in ONE "more" menu (the layer's `VeyqMenu`, entries
  `veyq-row-action-<id>`), `shown` by the application's fail-closed rights function - a share guest gets this very row with
  fewer actions, never a guest-only one. **The menu's order** (`splitRowActions`): rename and move first, the other actions in
  their merge order, then after a separator the menu-only group - report, then delete (`danger`); delete and report are never
  row buttons. Core's own actions (the favorite heart of inbox #409) plug in as the
  level's `coreRowActions`, merged around the application's by `mergeRowActions`, with no change to any application.
  **Rename, move and delete are core's DEFAULTS** (owner 2026-11-01, `core/node/standardRowActions.ts`, the row's mirror of
  `standardNodeActions`): an application builds none of them; its row answers `standard: { rename, move, delete }` from its
  rights function for THAT node (a folder's is core's `folderRights`), and the level adds the granted ones through the
  page's doors - `rename` (core's name dialog), `move` (core's move dialog) and `delete` with the one node (core's
  `DeleteNodesDialog`: one node's own question, `deleteQuestion.ts`, with the selection's Undo). Omitted, or a flag left out,
  or the door absent: no default (fail closed). A share guest's page hands on the move door alone (a `READ` link's not even
  that) - no guest renames or deletes a node core's way, whatever an answer says. **An application that implements one itself supplies an action of that id** (`rename`, `move`,
  `delete`) in `actions`, which REPLACES core's default whatever `standard` says - its own `shown` decides (`withStandardRowActions`;
  Depot's file: its rename and move through its session's source, its delete with its own question and Undo, inbox #385 (c)).
  A row's `selection` (`LevelRowSelection` `{ move, delete, remove? }`, inbox #421) is the application's rights answer
  for the level's Select mode (core's `selection.ts`, the bar in `LevelView`): omitted, the node allows nothing; `remove` is
  the kind's own delete door without its tree re-read (Depot's file), else core's `deleteNode`. Several nodes move through
  `core/node/MoveNodesDialog.vue` (`NodeMover.moveMany`: N single writes, one re-read) and are deleted through
  `core/node/components/DeleteNodesDialog.vue` (`deleteSelection.ts`), never a batch request.

  **A revisioned list inside a node is core's (inbox #410, `core/items/itemsLive.ts`, [ADR 0033](../../docs/decisions/0033-live-media-updates-revision-polling.md)).**
  The client half of the backend's `NodeItemsRevisions`: `itemsPollTick({ cursor, fetch, apply, isCurrent? })` is one tick for
  `createLivePoller(ITEMS_POLL)` (`shared/state/livePoller.ts`: 20 s +- 20 %, only while the tab is visible, an immediate catch-up on
  the return to visible or focus, doubling to 2 min on errors, fenced on stop) - it asks nothing when `cursor()` is null, treats
  `{ notModified: true }` (a `304`) as no work, drops a result for a view that moved on, and lets a failed `fetch` reject so the loop
  backs off; `reconcileItems` merges the keyset delta by id (a known item is patched in place through the application's `patch`, a
  `skip` keeps an optimistic removal out) and drops what the id set no longer lists; the keyset `cursor` the server answered with the last page
  read (`readItemPages`) is the `since` the next poll echoes. An application supplies only its FETCH (its generated client) and its REDUCER: Memories' gallery
  (`useMediaGalleryLive`, bound to the loop by `mediaRevisionPolling.ts`) is the first user. Core imports no application.

  **Covers are core's (inbox #411, `core/node/cover/`).** Any node whose kind carries a `cover` descriptor (`KindDescriptor.cover`: FOLDER
  pre-registered by core, Depot's drop box, Memories' MEMORY and CHAPTER - the server's `NodeKindRules.takesCover` is the authority and must
  agree; a file takes none) has one image its owner uploads as the node's own `COVER` blob. `coverModel.ts` is the pure model (the
  `cover` field of the sealed `wrappedMeta` - `core/node/nodeMeta.ts`, moved from Memories - in the shape every Memory cover already
  had: crop, display formula, ref, `uploadedCover`), `coverWrite.ts` the I/O (`uploadCoverImage`: the existing derive pipeline, ONE JPEG at
  `COVER_SPEC`, sealed as the node's blob; `writeNodeCover`: ONE request that sends the blob step AND the meta - `setNodeCover`,
  `removeNodeCover` or a plain `updateNode`, chosen from the cover being replaced as the server hands it back on a `409`), `coverStore.ts` the
  decrypted covers (a member's singleton cleared by `activeSpace`, a guest page's own through the link's gated presign, keyed on the sealed
  meta so a replaced or removed cover drops its object URL at once), `rights.ts` the fail-closed `coverRights` (a writer on a scope that is not pending deletion, keys loaded, an opted-in
  kind, not a guest's identity folder; no guest role), and `useNodeCover` the view's door: the covers it draws and ONE core action `cover`
  (Set / Change, `image`) among core's actions of every header and row (`core/node/coreNodeActions.ts`) - there is no remove action;
  it opens THE dialog, `components/CoverDialog.vue` (choose an image, the shared 16:9 `ImageCropper`, re-crop of the current cover with its
  stored crop, Remove where a cover exists, Save/Cancel; a non-image, empty or over-40-MB file is refused inside it, `pickImage.ts`
  `coverFileProblem`), which each view mounts once beside its content as `<CoverEditor :cover="cover" />` (`LevelView`, and `NodePage` for
  a node's page, whose ONE cover core's header shows and opens for every kind - no kind draws a cover control of its own). Save is `useNodeCover.save(node, intent)` for every kind: the intents are `upload` (a job of the core upload queue + the
  one `setNodeCover` write), `keep` (the stored value with only its crop moved) and `remove`. A cover is set only by an upload in that
  dialog (the lightbox offers no "Set as cover"); a kind whose nodes already store pictures may add one seam to its descriptor,
  `cover.findStored` (`KindCover`; Memories: the PREVIEW of a gallery photo whose original equals the picked file), so the cover shares
  that blob instead of storing it again (ADR 0013 § 5). The level
  resolves every node's cover once and hands it to the row factories (`LevelRowsHost.covers`, `ensureCovers`). Display: the row/tile leading
  visual (`NodeCoverImage`; Memories' cards and map draw `MemoryCoverImage` - the same `coverImageStyle` formula, pinned identical by a parity
  test), core's header (244 px wide on every kind), the sidebar's Favorites; the icon is the default and a cover without a stored crop is a centred fit.

  **Favorites are core's (inbox #409, [ADR 0068](../../docs/decisions/0068-favorites-two-confidentiality-levels.md) §10).** The member's private pin of
  a node of ANY kind - folder, file, Memory, drop box - lives in `src/core/favorites/`: `favorites.ts` (the sealed data layer, purpose
  `favorites` under the Master Key, the generated `favoritesApi()` of `api/client.ts`), `favoritesStore.ts` (the reactive set, optimistic
  toggle, refused until loaded), `rights.ts` (`favoriteRights(access, node)`: member only, fail closed, no scope root, no
  unsupported kind; kept during a pending deletion), `favoriteActions.ts` (the heart as a `CoreHeaderAction` / `CoreRowAction`, `first`,
  placed before the application's own) and `PinnedNodes.vue` (the sidebar section: every kind's pinned node with its registry icon and
  name, opened by `hashForNode`; an id that does not resolve or a kind this client cannot draw is skipped). `LevelView` contributes the
  heart to every row (not the unsupported ones) and to the folder header, and core's node page to every node page's header
  (`core/node/coreNodeActions.ts`), so an application adds no heart of its own. `app/activeSpace` clears and loads the set with the scope and `rotateKeys` re-seals it - both directly, not
  through an application's lifecycle hook. A share guest's list is empty and the store is never read for it. The item MARKS inside a
  Memory stay Memories' (`features/memories/itemFavorites*`, `generated/item-favorites`).

  **The tabs are a pure FILTER** (inbox #348, `core/node/levelTabs.ts`): `All | one tab per application`, the tab named by
  its kinds' `list.tabLabel` (kinds sharing a label share a tab, its id the first kind - what `shared/state/useLevelTab`
  stores, per account, one for every share-guest page, never one for the browser, no value written while the account id is
  unknown), over the CURRENT folder's children only; the FOLDERS stay in every tab; a tab is offered where its kinds have a
  node here or the viewer may add one (a kind with an allowed "New" entry), the bar only with two or more tabs; a stored tab
  the level lacks shows as `All` without rewriting it. A tab with nothing says so in its application's own words
  (`list.emptyText`, an i18n key function over the level context), and a level with nothing at all says so ONCE in plain text
  composed from what the viewer may create (`node.levelEmptyNew`), or `node.levelEmpty` - no create tile.

  **What an application registers** (its `register.ts`; the registry is write-once, a second registration throws). It
  names itself ONCE, `const app = registry.registerApplication({ id, name })` — its register id (its switch, from
  `features/platform/capabilities.ts`) and its name's i18n key — and registers everything else through the SCOPED
  registrar it is answered (`ApplicationRegistrar`); there are no free register functions. **Ownership is the
  registration:** a kind belongs to the application whose `app.kind` registered it (`applicationOf(kind)` answers that to
  core), so no list of an application's kinds exists beside its registrations, and `app.id` is the one place the id is
  written on the web side - a kind's `contentSchema` gives its version only (`{ version }`), the registry fills its
  application in from the registrar. The registrar's doors:
  `app.kind(kind, descriptor)` per node kind (`KindRegistration`: detail component - the BODY of the node's page, under core's header, to
  which it contributes data only (above) -, icon, `header` - `{ order }`, its header's priority -, `deleteTitle` - the i18n key core's delete dialog
  titles a node of the kind with, else a neutral "Delete", and `deleteCopy`, its sentence - `nounWithArticle`, the noun core's
  create and rename dialogs name a colliding sibling of the kind with ("There is already a file named ...", else "an item") - `emptyInTrash`, the note core's
  trash shows on a node of the kind that holds no attachment - `renameSelectionEnd`, where core's rename dialog ends the
  selection on a name, e.g. a file's before its extension - `placement`, where a node of the kind may be moved (`{ root,
  parents }`, a hint mirroring the backend's `NodeKindRules`; core's move dialog offers nothing for a kind without one,
  inbox #358) - and its `list` of the level list - `{ rows, tabLabel?, views?, emptyText? }`. `rows` is a FACTORY
  (`LevelRowsFactory`): the level calls it ONCE per view, in its setup, with the level's reactive context (`LevelRowsContext`:
  `folder`, `nodes` of every kind that names the factory, `epochs`, `access`, `tree`, the clamped `viewMode`, `liveTick`) and a
  host (`LevelRowsHost`: the page's doors - `open`, and `rename` / `move` as the doors of an application's OWN rename or move,
  each possibly absent; core's defaults need none -, and `registerAction`), and it returns a `LevelRowSource`: `row(node)` (a row,
  or `null` for a node it cannot draw - core then shows the explicit unsupported row), `notices` (what only the application
  can explain, drawn above the list by `LevelNotices`; Depot: why an upload failed) and `presentation(mode)` (the
  application's own presentation of a mode core does not draw, in place of the list: Memories' map - `views` names it, so the
  toggle offers it exactly where a shown tab includes the kind). The factory owns the DATA a row needs and the flows behind its
  actions (Depot: the blob listing, re-read on `liveTick`, the upload and replace flows with their confirmation and the
  picker it builds; Memories: covers, dates, the heart), so none of that depends on which tab is shown. Kinds naming the
  same `rows` function share one row source and must agree on `views` (else `app.kind` throws). A kind registered
  without a `list`, or not registered at all, is an unsupported row. For the level's "New" menu (inbox #406) a kind brings
  `createEntry` (`{ label, icon, order, allowed(ctx), enabled?(ctx) }`: the kind's menu entry, its label an i18n key of the
  application's own namespace, `allowed` its fail-closed rights function, and it needs a `createDialog`, which
  `app.kind` checks) and `levelActions` (entries that are no create dialog - Depot's "Upload files" and "Upload folder…";
  ids write-once, `create:` reserved; `acceptsDrop` makes the level hand it what is dropped onto it). The factory registers
  the action's handler with `host.registerAction(id, { enabled, run, drop?, dropDirectory? })` (`core/node/levelActions.ts`)
  for the level's life; an entry whose handler is not `enabled` is disabled, and a DROP reaches `drop` (files) or
  `dropDirectory` (a directory that is no download) only while the entry is `allowed` AND its handler `enabled` (a drop is
  never more than the menu entry). There is no import entry: core recognises a download in what an upload picks or drops
  (`host.screenUpload`, `host.screenDirectory`) and offers the member its import. The level's toolbar is core's: the "New" menu
  (`registry.levelMenu()` plus core's own folder entry, one `VeyqButton` + popup `VeyqMenu`, each entry only where its own
  rights function says yes, so a READ guest has none; a create entry asks the page's `createChild(kind, folder)` door and
  the page mounts the `createDialog`), the space's trash, and the view switch over the union of the presentations on screen - core's grid and
  list for every row, plus the shown kinds' `views` (`core/node/levelViews.ts`, inbox #370); the level shows the stored mode
  only where that union offers it, else the grid, without rewriting the stored preference, and hands that mode to every
  factory as `LevelRowsContext.viewMode` - a factory never reads `useViewMode` (inbox #404), `useViewMode` is keyed like the
  tab (`shared/state/scopedPreference.ts`; per browser, not synced). The LIVE REFRESH (inbox #408: `core/node/useLevelLive.ts` over `shared/state/livePoller.ts`, started by the two hosts
  while a folder level is on screen - the member's `SpaceView` through `treeStore.pollTree`, the guest's `ShareView`
  through `GuestAccess.poll` - about every 5 s, only while the tab is visible, paused while a dialog is open, one request
  per level, and a CONDITIONAL one, [ADR 0081](../../docs/decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md):
  the member's `fetchTree` (`listNodesRaw`, the scope's tree revision as `ETag`/`If-None-Match`) and the guest's
  `pollPublicShare` (`resolveShareRaw`, a digest of what the link serves - never the scope revision, which would tell a guest
  when the rest of the space changes) read a `304` as "nothing moved": no parse, no decryption, no tick. `read` resolves whether
  the state MOVED and the host's `useLevelLive` hands every row factory `LevelRowsContext.liveTick` only then, which Depot's
  factory answers with a blob re-read - an upload completing moves the revision and changes no row; the tree itself is
  replaced only when the rows' fingerprint differs. A guest poll that meets the link gone (`404`) flips `ShareView` to its
  unavailable state, a changed password to the gate; a network error, `429` or `5xx` only backs the loop off), the folder's
  header (core's `NodeHeader`, which every node page opens with too - see above). Its `createDialog` - props `NodeCreateDialogProps` `{kind, scopeId, epochs, parentId,
  parentKey}`, mounted by the page when the "New" menu or a detail asks its `createChild` door for that KIND; and its
  `shareDialog` - props `NodeShareDialogProps` `{scopeId, nodeId, nodeKey, epochs, nodeName, nodeKind, uploader?, contribute?, readonly?}` (`contribute`: offer READ_WRITE for a new link — the rights function's `shareContribute`, carried by the `share` door's `NodeShareOffer`, never read by the dialog; `readonly`: the scope is pending deletion, set by the host for every kind - the dialog lists and copies the links and offers revoking the caller's own live one, and mints and changes nothing else, as the server refuses every share write in the grace window but a revoke, uc.spaces.scope-access A1); a
  kind without one offers no share. There is no guest-only list or detail: core's page of a `READ`/`READ_WRITE` public
  share (`core/sharing/ShareTreePage.vue`) is core's `LevelView` at a folder and the kind's `detail` at any other node -
  the link's root included - handed the guest's access and tree (inbox #401). Core owns the rest: a folder's create and every node's rename are
  `core/node/FolderNameDialog.vue`, which derives the slug by the renamed node's kind; every node's move is
  `core/node/MoveNodeDialog.vue` (inbox #358) around core's folder browser `core/node/MoveBrowser.vue` (one place at a time,
  drawn with `LevelRowItem`; it takes a node list, which a level's selection hands it through `MoveNodesDialog`), mounted by both hosts when a row factory or a
  detail asks the page's `move` door, moving through the session's one door (`core/node/move.ts` `nodeMover`: the member's `moveNode`, a `READ_WRITE`

  guest's `placeShareNodeAsGuest`, none otherwise) and explaining each server refusal by its code; and FOLDER's share dialog
  `core/sharing/FolderShareDialog.vue` is pre-registered); no shell slot - the top bar and the sidebar are core's alone
  (the upload status `core/blob/UploadStatusMenu.vue` lists every application's batches of the one core queue, the
  Favorites `core/favorites/PinnedNodes.vue` every kind's pins), and a place there for an application is a seam that comes
  with its first user; a kind registered `listed: false` is no row of the level at all - neither its own nor the
  unsupported one (`registry.listed(kind)`, read by `LevelView`; it brings no `list`, `app.kind` throws on both): the seam
  for a kind an application reaches another way (Inventory's one pool per space; its navigation entry is
  [docs/apps § Decided, not built](../../docs/apps/README.md#decided-not-built)); `app.route(slug)` — ONCE, for every kind
  the application owns: its segment in a node's URL fragment path (`#foo/memories/rom`; Memories claims
  `memories`, a second decision or a slug another claimed throws, `RESERVED_ROUTE_SEGMENTS` are refused) — or `app.route(null)`,
  the explicit "no segment" option only Depot takes (its nodes are plain names: `#foo/report`; one application at most). The grammar
  — a folder ends in a slash, a claimed slug is reserved among folder and file names (`memories-2`), folders and files are numbered
  apart, no slash resolves to the file before the folder — lives in `core/node/location.ts` (ADR 0015, amended 2026-10-11) and
  nowhere else: `scripts/validate_web_fragment_paths_via_location.py` is red on a file that splits or hand-builds a fragment path;
  `app.shareBody(rootKind, body)` for the drop page of
  a `WRITE_ONLY` link rooted at that kind (`PublicShare.rootKind`, a kind of the application's own - another's throws; the
  shell reads it with `registry.shareBodyFor`) — the one page with no member equivalent, its guest
  holding no key: Memories' photo drop (`MemoryShareBody`), Depot's drop box (`DropBoxShareBody`); `FOLDER` is claimed by
  nobody and refused. Inside a guest's identity folder — the served `shareIdentityId`, core's column — no view offers any
  guest a write; `app.personSection(component)` and `app.itemReview(component)` (above, and the moderator's review of an
  application's reported record);
  `app.lifecycle(hooks)` once
  with `onScopeReset` / `onScopeEntered` and the **required** `onMasterKeyRotated` — material an application seals under the
  Master Key (Memories' item marks) is otherwise unverifiable after a rotation. There is no registry-wide rotation gate:
  core re-seals its own Master-Key material (the node pins) in `rotateKeys` itself, so a build with no application hook
  rotates (`rotateKeys.test.ts` pins it); that Memories' hooks reach the real registry is `applications.test.ts`'s. Every
  registered kind but FOLDER belongs to the one application that registered it and has its route decided (`registry.kinds()`,
  `routeDecided`; `applications.test.ts`). A test registers its made-up kinds through `src/test/testApplication.ts`
  (`testApp(registry, id?)`, `testKind(registry, descriptor, id?)`). A kind or share nobody registered renders

  an explicit unsupported/unavailable state, never an empty region. **Who looks** (inbox #401, `core/access.ts`): a
  `detail` (`NodeDetailProps`) and a row factory (`LevelRowsContext`, which also carries the level's `viewMode`) are handed `access` — `Access`, a member
  (`scopeId`, `epochs`, `readonly` = pending deletion, the instance's three share leaves `publicShares`/`publicSharesContribute`/`publicSharesDropBox`, one boolean per register id the host fills from ONE `hasCapability` each — the server ANDs the path, a rights function never does, ADR 0088 — and `applicationsOff`, the ids of `registry.applications()` the read set lacks (`isSwitchedOff`, never an unread set): every rights function then drops every write but the delete - core's `nodeRights` and each application's ask `applicationWritable(access, kind)`, never `applicationsOff` itself, as core does for its own actions on the kind -, uc.platform.switch-application-off) or a share guest
  (`shareId`, `role`, `head`, `pwToken`, `fragmentKey`, `requestGuest`, `reload` — `guestAccess`, built by
  `views/ShareView.vue`, which owns the re-resolve after a guest's write and the page's one naming flow, core's `core/sharing/GuestNaming.vue` with its "Not you?"; every naming place uses that one
  component) — and `tree`, a `TreeSource` (`node`,
  `children`, `keyOf`, `nameOf`, `inIdentityFolder`, `linkTo`: every navigation goes through it, so a guest's link keeps
  `#k=`); the member's come from `memberAccess`/`memberTreeSource`, built by the host, the guest's tree from
  `guestTreeSource` (`core/sharing/guestAccess.ts`: a served `ShareNode` as a `TreeNode` — `createdAt` 0, so equal
  names number by node id; `metaRevision` 0; `wrappedMeta` as served). A row factory built for a guest reads nothing of
  the member's world (`treeStore`, `activeSpace`, the cover and pin stores) — Memories' rows read their dates, covers
  and map from the guest's tree (the covers are core's, `core/node/cover/`, resolved by the level), Depot's rows read and write through Depot's data
  source (`features/depot/source.ts`: the member's or the guest's, fixed at mount; a write is offered where the matrix
  grants it AND the source carries it — `sourceRights` — and the guest's source has no delete, no rename), and so
  does Memories' detail (`features/memories/source.ts`, picked by `MemoryDetailHost`: the detail, its composables, the
  lightbox's loaders and — by injection, `useMemoryView`, which throws outside a detail — the organizer touch no API but
  through it; the guest's has no trash, node meta, cover, marks, buckets, live poll or EXIF, and its delete is a purge of
  its own). A drop page (`ShareBodyProps`) is handed the
  guest's `access` too and names its uploader on the page (the identity key is its application's). What a view offers is its
  application's pure, fail-closed rights function over `access` and `targetOf(tree, node)` — `memoryRights`
  (`features/memories/rights.ts`), `depotRights` (`features/depot/rights.ts`), `expensesRights`
  (`features/expenses/rights.ts`), core's `folderRights` for the level view; each matrix is its
  `docs/apps/<application>.md` table — never a `readonly`, `shareable` or `hasCapability` read inline; `targetOf`
  carries the kind's answer (`shareable`: the kind brings a `shareDialog`, its whole answer), so no share button appears whose dialog the host
  cannot mount. **Core's base, the application narrows** (owner 2026-11-01): what every node may do — `share`,
  `shareContribute`, `shareUpload`, `rename`, `move`, `delete`, one name per right everywhere (`NodeRights`) — is core's
  `nodeRights(access, target)` in `core/access.ts`: the member/guest role, a pending deletion (no write; the share
  dialog stays, the host's `readonly` makes it mint and change nothing but a revoke), the application switch, the instance's share
  leaves, the kind's share dialog, a guest's identity folder, the scope root. Every rights function takes that answer
  and only ANDs its own conditions onto it (Depot's drop box is never renamed, a guest moves no Pot); its
  `rights.test.ts` asserts it is never wider over every access shape (`src/test/nodeRightsShapes.ts`). A row's
  `standard` and `selection` come from one answer (`rowRightsOf`), and a header passes the same object to
  `standardNodeActions`; the cover is core's `coverRights`, which core's header reads for its cover button too
  (`useNodeCover().canSet`).
  **A share view is the member view with a source and rights — never add a guest-only UI** (owner principle
  2026-10-09, inbox #401). A guest's function goes into the member's component as a door of the application's data
  source and a right of its rights function; a member-only function must not leak into share mode (no door on the
  guest's source, false in the guest's column, and the guest-mode mount test of that view records it). The guest-only
  exceptions are exactly the ones with no member equivalent: the shell's gates (password, terms, a note that asks to be accepted — decided on each full resolve, never by a
  live poll, so it cannot yank a page mid-visit), core's one naming
  component with "Not you?" (`GuestNaming`, the shell's dialog behind `access.requestGuest`), the curator-note banner, the
  role badge and the breadcrumb up to the link's root (`ShareTreePage`), and the two `WRITE_ONLY` drop pages
  (`*ShareBody.vue`, claimed — `scripts/validate_web_share_bodies_are_drop_pages.py`). Every other separate guest body
  drifted from its member twin (#356, #361, #363) and was deleted. **Limits, stated:** the dialogs are per kind
  (cr-files-application P4c) and the shell has no application slot; the level view (its list items `veyq-level-item`) and core's `FolderNameDialog` carry core `veyq-*` classes (`styles/ui.css`, dark twins in
  `dark.css`) and core `node.*` i18n keys since cr-files-application P4d, which Memories' own dialogs and detail read
  too for the shared labels; the kind is an open string token (the generated client has no `NodeKind` enum), so core names its one
  `FOLDER_KIND` (`core/node/kind.ts`), each application its own (`features/memories/kinds.ts`, `features/depot/kinds.ts`), and no compiler lists the
  kinds — the registry's `{ supported: false }` answer and its tests do; each application's strings are
  its own catalog (`features/<owner>/i18n.ts`, added at build time by `src/applicationMessages.ts`; core's `src/i18n/index.ts` holds core's alone), Memories' global classes its own `features/memories/memories.css` (the media lightbox included, #517) and Expenses' its `features/expenses/expenses.css`, each imported last by its application's `register.ts` (inbox #513, #517); the PDF text layer's rules are core's `shared/pdf/pdf.css`, imported by the renderer `shared/pdf/PdfPages.vue`, which every application that shows a PDF mounts. An application's global classes are its own sheet in its directory, imported by the application: it loads BEFORE `style.css`, so a rule styling an element a core class also styles compounds with that class (`.veyq-card.memory-card`) and wins by specificity, never by order; its dark twins sit at its end. `validate_stylesheet_anchors.py` reads every `.css` under `src/`.
  **Testing against it:** `src/test/setup.ts` fills the registry in a `beforeAll` *dynamic* import (a static one
  evaluates the application before the test file's `vi.mock`s and disarms them — 119 files went red). A
  node-environment suite gets no registry, so one that exercises a hook registers the real `memoriesLifecycle`
  itself (`activeSpace`, `coverStore`, `rotateKeys` tests). A suite that stubs the registry delegates through a
  `Proxy` onto a fresh `createRegistry()` (`ShareView.registry.test.ts`, `SpaceView.registry.test.ts`): a
  hand-listed set of methods silently misses the next one the registry gains.
- **Wire encoding:** opaque fields are **standard base64** (`b64std`), not base64url — the contract pins
  `[A-Za-z0-9+/]*={0,2}`, so base64url is rejected with 400 at the boundary.
- **Operator-blind:** seal/derive everything client-side; never send the password, plaintext or raw keys.
  See [security model](../../docs/crosscutting/security-model.md) and
  [account & key model](../../docs/features/account/enablers/en.account.key-model.md).
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
  bounds the risk is the input class: we only ever decode QR codes **our own client produced** — the four
  markers `veyqkit:`, `veyqpair:`, `veyqsignin:` and the safety number's `veyqid:2:`, all of which stay far below the
  known version-23 defect (*measured:* a 44-char secret gives **version 3** under `veyqpair:` and **version 4** under
  the two-character-longer `veyqsignin:`; `veyqid:2:` plus a 32-byte fingerprint digest and a 32-byte key-chain head
  (ADR 0111 decision 14) is 98 characters, **version 6**, pinned by `features/account/safetyCode.test.ts`; the older
  `veyqid:1:`, 53 characters, is still decoded, only to say the other app is outdated). **The bound is a property of what the payloads CARRY, not of how
  many markers there are:** a marker plus a 32-byte secret or digest is ~55 characters, while the transport key
  `veyqsignin:` deliberately does not carry would be 1256 bytes and **version 34** (qrcode's default level M, measured 2026-10-15), and the identity bundle `veyqid:`
  deliberately does not carry is past version 23 too — so the thing that would void this exception is a payload
  growing a key, not another marker. Pure JS is memory-safe
  so a decoder bug is a wrong string rather than an exploit, and the decoded value is validated immediately
  against the contract-pinned `[A-Za-z0-9+/]*={0,2}`. **Revisit on any of three triggers:** we need to decode
  a *foreign* QR code, jsQR gets a CVE, or `BarcodeDetector` reaches Firefox and desktop Chrome.

- **A file page shows the file when it is opened** (inbox #412, `features/depot/components/FileViewer.vue` +
  `useFileViewer.ts` + `view.ts`, the PDF pages core's `shared/pdf/PdfPages.vue`; the rules and caps are `docs/apps/depot.md` § The viewer). Depot makes **no thumbnail or
  preview blob**. Four rules bind a change here: (1) **the decrypted bytes decide the kind** (`core/blob/sniff.ts`), the
  sealed type and the name only narrow - a test per claim-vs-bytes case; (2) **never render a peer's bytes as markup or code**:
  text is `textContent`, an image an `<img>` on an object URL owned by `useObjectUrl` and typed with the SNIFFED type (SVG
  too - never inline, `<object>` or `<iframe>`); (3) **video/audio go through the service worker's media route** with the
  chunk provider the download uses (the worker holds no key; protocol 4 accepts `audio/*`), never a whole-file decrypt;
  (4) **everything the viewer made is undone** on a new file, on navigation and on unmount (object URL, playback handle,
  the `AbortSignal` into the stream pump). A PDF is drawn by **pdf.js** (`pdfjs-dist`, legacy build) onto canvases in the page, never by the browser's
  viewer: `frame-src`/`object-src` stay `'none'` and **the CSP is unchanged** (widening it is the owner's decision). The rules
  (core's `shared/pdf/`, so any application draws a PDF through it - Depot's viewer is the first; `docs/apps/depot.md` § The viewer): `pdfEngine.ts` is the only seam and reaches pdf.js through a
  **dynamic** `import('./pdfjsRuntime')` - the one file that imports it, never imported statically (a source test and
  `pnpm run verify:pdf-worker` pin it, with the worker a same-origin hashed asset for `worker-src 'self'`, no `eval`/`new
  Function` in the emitted files, no `.wasm`); `pdfOptions.ts` is the option set - the document is `{data}` (never a URL),
  `useWasm: false` (the WASM decoders need `'wasm-unsafe-eval'`, which the policy does not grant: JBIG2/JPEG 2000 scan
  images render blank - a documented gap, a policy decision to change), `enableXfa: false`, no scripting, no annotation
  layer (a text layer yes - spans only, selectable and copyable - and a password prompt that hands the password to pdf.js
  only: never state, log, storage or network); CMaps, standard fonts and ICC profiles are same-origin copies under `assets/pdfjs-<version>/`
  (`vite.config.ts`'s `pdfjsAssetsPlugin`), `connect-src 'self'` carries them. **Bumping `pdfjs-dist` re-runs the real-library
  test and `verify:pdf-worker`; check that its options still exist** (v6 dropped `isEvalSupported`). The member and the guest
  mount the same component.

Stack: Vue 3, the UI layer in `src/shared/ui/` (Reka UI and Lucide underneath, ADR 0107), vue-router. Working rules: [`../../docs/ai/README.md`](../../docs/ai/README.md).
