# web (Vue 3 + PrimeVue)

The web client. Loaded in addition to the root `CLAUDE.md` when working here. All content crypto runs
here; the server is blind.

## Prove your work
- `npm run check` (lint + typecheck + test + build).
- API client is **generated** and **not tracked** (gitignored, mirrors the backend) — `npm run generate:api`
  from `../contracts/api/*.openapi.yaml` produces it (and runs automatically on install via `prepare`). Never
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
- **`vi.mock` resolves by path, so moving a module silently disarms every mock of it.** A mock left on the old
  path stubs nothing and the real module runs — and a passthrough mock (`withRefresh: (f) => f()`) is green
  either way, so no red announces it. After a move, prove the new mocks bite by sabotaging the *real* module
  (make the export throw): every suite that mocks it must stay green, and the module's own test must go red.
- **Dev credentials come from a seeder — never hardcode or guess them. Two paths, and they are NOT
  interchangeable:**
  - **`npm run bootstrap:user` (the primary local principal).** Inputs live in **`<repo-root>/.run/.env`**
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
    (`buildAccount(..., { secretKey })` — dev bootstrap only; every real birth mints fresh entropy). Prefer this
    over `seed:dev` for a manual smoke: it is one command and needs no dev-invite file.
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
  - **`npm run seed:dev` (cr-0046, the e2e-test seeder).** Mints a Server Owner + a **`127.0.0.1`** tenant owner
    and writes **`web/scripts/.seed/dev-seed.json`** (gitignored) with creds **and** raw key material; loaded by
    `scripts/devSeed.mts` → `loadDevSeed()` / `vaultFromSeed()` and read automatically by `media.e2e.test.ts`
    when `VEYQ_EMAIL` etc. are unset. (`POST /api/tenants` rejects a numeric domain, so it creates the tenant
    under a hostname and remaps the domain to `127.0.0.1` in the dev DB.)
  - **Footgun:** the two seed different tenants (`veyq.localhost` vs `127.0.0.1`). Logging in on the **wrong
    host/creds** — or against a DB seeded by the *other* path — fails at `POST /api/login/verify` with **401**
    (password-derived auth doesn't match the registered identity), NOT a crypto bug. Match host ↔ creds ↔ the
    seeder that populated the running DB. Either seeder wants a **fresh** instance first
    (`docker compose -f infra/docker-compose.yml down -v && up -d`, then restart the backend so Flyway recreates
    the schema).
- **Run the Memory shell locally (cr-0030):** `npm run dev` (Vite proxies `/api` → `127.0.0.1:8080`). The active
  tenant is resolved server-side from the **Host** (port stripped); the signed-in user must belong to it. The
  default flow: browse `127.0.0.1:5173` → the tenant whose **`domain` is `127.0.0.1`** (`npm run seed:dev` sets
  this up) → login → unlock auto-births the personal scope and the browse renders. If no tenant claims the Host
  you get the *“no space”* state (expected).
- **Multiple workspaces in parallel from one dev server (cr-0051):** the dev proxy forwards the **real browser
  Host** (`changeOrigin: false`), so register each tenant under its own `*.localhost` hostname (e.g.
  `workspace-a.localhost`) and browse `workspace-a.localhost:5173` vs `workspace-b.localhost:5173` — each resolves
  its own tenant. `*.localhost` resolves to loopback in browsers without `/etc/hosts` (prefer it over `*.local`,
  which is mDNS on macOS); for another suffix, add it to `allowedHosts` in `vite.config.ts`. `VEYQ_SMOKE_HOST=<host>`
  pins one fixed tenant regardless of the browser URL (headless smoke). **Footgun:** with the real Host forwarded,
  `localhost:5173` ≠ `127.0.0.1:5173` (only `127.0.0.1` carries the seed tenant) — the canonical dev URL stays
  `127.0.0.1:5173`.

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
  run both, because Stryker's runner is the project's own vitest and neither project sees the other's suites. **Footgun: it mutates the real source files _in place_**
  (originals backed up to `.stryker-tmp/backup-*`, restored on exit), so mid-run `git status` shows hundreds of
  transient `M` files + stray `stryker-setup-*.js` — never run other `web/` work (git ops, `npm run check`, an
  edit) against the tree while a mutation run is live; wait for it to finish and restore.
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
  (*measured:* a 44-char handle gives **version 3** under `veyqpair:` and **version 4** under the two-character-
  longer `veyqsignin:`). **The bound is a property of what the payloads CARRY, not of how many markers there
  are:** a marker plus a handle is ~55 characters, while the transport key `veyqsignin:` deliberately does not
  carry would be 1260 bytes and **version 28** — so the thing that would void this exception is a payload
  growing a key, not a fourth marker. Pure JS is memory-safe
  so a decoder bug is a wrong string rather than an exploit, and the decoded value is validated immediately
  against the contract-pinned `[A-Za-z0-9+/]*={0,2}`. **Revisit on any of three triggers:** we need to decode
  a *foreign* QR code, jsQR gets a CVE, or `BarcodeDetector` reaches Firefox and desktop Chrome.

Stack: Vue 3, PrimeVue, vue-router. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
