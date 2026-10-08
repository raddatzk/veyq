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
  real router and clicks the rendered element (`FolderShareBody.breadcrumb.test.ts`).
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
  The applications are `src/features/memory/` (Memories) and `src/features/files/` (Files: the `FILE` and `FILES_DROPBOX` kinds, their level
  and share sections, upload, download, the drop box — [docs/apps/files.md](../docs/apps/files.md)) — the guard's `APPLICATIONS`, keyed by owner;
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
  **What an application registers** (its `register.ts`; the registry is write-once, a second registration throws):
  `registerKind` per node kind (detail component, icon, shareable, `deleteTitle` — the i18n key core's delete dialog
  titles a node of the kind with, else a neutral "Delete" — and its `section` of the folder level view —
  `{ order, component }`; the level view is core's, `core/node/LevelView.vue`: the folder's overview card, the
  sections sorted by `order`, a fallback section for a child whose kind no section serves, then the sub-folders; kinds
  naming the same component share one section, rendered once with the children of all of them, and naming it with two
  different `order`s throws; its `createDialog` — props `NodeCreateDialogProps` `{kind, scopeId, epochs, parentId,
  parentKey}`, mounted by the shell when a section emits `create` or a detail `createChild` with that KIND; and its
  `shareDialog` — props `NodeShareDialogProps` `{scopeId, nodeId, nodeKey, epochs, nodeName, nodeKind, uploader?}`; a
  kind without one offers no share; and its `shareSection` — `{ order, component }`, props `ShareSectionProps`
  (the share, the folder on screen, the kind's children with the chain's keys and names, `canWrite`, `requestGuest`):
  core's folder page of a public share (`core/sharing/FolderShareBody.vue`) renders it at every folder instead of
  cards for those children, by the level sections' rules (Files' file list for guests). Core owns the rest: a folder's create and every node's rename are
  `core/node/FolderNameDialog.vue`, which derives the slug by the renamed node's kind, and FOLDER's share dialog
  `core/sharing/FolderShareDialog.vue` is pre-registered); `addSlotItem(slot, id, component, order?)` for the two list slots `topbarTray`
  and `sidebarPins` (an id namespaced by the application, e.g. `memories.pinned`; write-once per (slot, id); the shell
  renders `slotItems(slot)` sorted by `order`, ties in registration order — the top bar after core's own upload status
  menu, `core/blob/UploadStatusMenu.vue`, which lists every application's batches of the one core queue and is no
  application's item, so today no application adds to `topbarTray`); `claimShareBody(rootKind)` for the
  public share page (`PublicShare.rootKind`, served for every role, so one body per kind picks its screens by role;
  core claims `FOLDER` itself and embeds the claimed body for each child, handing it `subtreeRoot` — that node's id,
  key and name — so the body renders and writes into that node, not the share root; a body claimed `{ rootOnly: true }`
serves only its own link, and core's folder page then shows no node of that kind nor anything below it — Files' drop box);
  `registerLifecycle(id, hooks)` with `onScopeReset` / `onScopeEntered` and the **required**
  `onMasterKeyRotated` — `rotateKeys` refuses to start when none is registered, because material sealed under the
  Master Key (Memories' pins) is otherwise undecryptable after a rotation. A kind or share nobody registered renders
  an explicit unsupported/unavailable state, never an empty region. **Limits, stated:** no slot is single-claim any
  more (the dialogs are per kind since cr-files-application P4c; the two slots `topbarTray`, `sidebarPins` are ordered
  lists); the level view and core's `FolderNameDialog` carry core `veyq-*` classes (`styles/ui.css`, dark twins in
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
