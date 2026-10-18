---
id: cr-volume-measurement-local
title: Does a small ARM host carry an instance? The harness and the local pre-check on a pinned 2-core instance
status: in-progress
satisfies: ["uc.memory.gallery", "uc.memory.browse", "uc.spaces.add-member"]
adrs: ["0057", "0093"]
invariants:
  - all seeded content is sealed by the real client crypto, and every seeded member is a real account onboarded through the product's own invite flow; nothing is written to the database or the store by hand
  - no product code changes; the harness measures from outside, and a fix found here is a CR of its own
  - the conclusion is a sizing recommendation, not a target (architecture §10 "Deliberately unstated" stays true)
---

## Goal

**This is the first of two CRs** (owner 2026-10-18). This one builds the harness and runs it locally on the
production recipe, pinned to 2 cores and 4 GB. [`cr-volume-measurement-cax`](cr-volume-measurement-cax.md) runs the
same harness on a real CAX11, writes the sizing recommendation and deletes the harness. The split exists because
the remote half waits on a server only the owner can set up, and the local half should not wait for it.

Answer one operator question with a measurement: **does a small ARM host, 2 vCPU and 4 GB like a Hetzner CAX11,
carry a veyq instance for a household, and up to what volume?** Today the Run area says "No CPU, memory or disk
minimum is documented" (`site/src/content/docs/run/plan.md`, § Size the host). After both CRs it names a smallest
host it was measured on, and what that host was measured carrying.

On Compose everything shares that host: the application's JVM, MariaDB, Garage and Traefik. Garage serves every
thumbnail a browser fetches. So the server-side cost of a household is more than "accept and send data". It is the
list queries on slow cores, plus the store sending thousands of thumbnails, plus uploads, all in 4 GB.

The volume grows along **the three lists the client loads whole**:

| Axis | What grows | Stages | Loaded by |
|---|---|---|---|
| **Photos** | media in one Chapter | 1,000 · 3,000 · 10,000 | `listNodeMedia`, then a presign and a store GET per thumbnail |
| **Tree** | nodes (folders, Memories, Chapters) in one space | 500 · 2,000 · 5,000 | `listNodes`, the whole scope tree |
| **Members** | members of one tenant | 20 · 200 · 1,000 | `listScopeMembers` on the tenant scope, then an avatar per member |

Then **household scenarios** run against the full dataset: an idle baseline, five people opening the big Chapter at
once, and one person uploading 1,000 phone photos.

The **browser leg stays in** (owner 2026-10-18). It measures the client's device, not the host, and answers the
second question the code-reading raised: what a big list costs the person who opens it.

Out of scope: any fix. Each hotspot whose numbers matter becomes an inbox row and later a CR of its own.

## Context & constraints

- **Measured on the production recipe, never on the dev stack.** quarkusDev runs a dev-mode JVM with no
  `mem_limit` and a hot-reload layer, so its numbers say nothing about a host. Every reading comes from a
  throwaway instance installed by `veyqctl` from release assets, as an operator installs it. This also means no
  dataset ever touches the dev user's instance. That **replaces** the owner's earlier placement (photos in the dev
  user's personal space, members in a tenant of their own, 2026-10-18); see Q2.
- **Locally, as a pre-check** (owner 2026-10-18). The recipe's containers are pinned together to 2 cores
  (`docker update --cpuset-cpus`), and memory is held to 4 GB. An M4 Pro core is markedly faster than an Ampere one,
  so a local pass is **not** a sizing answer, and nothing in the Run area may claim one from it. What the local run
  delivers: a harness that works, the gross hotspots, and a first verdict the CAX run is compared against.
- **The load model is a household** (architecture §10). The top stage of each axis is its upper edge. The member
  axis reaches past a household on purpose, because the operating mode *club, organisation or company* exists
  (ADR 0093 § 14).
- **No copying rows to seed faster.** Every key is wrapped with AAD bound to its address (scope, ids, role), so a
  copied row fails to decrypt. Photos go through `addCopiedMedia`, nodes through the app's create calls, members
  through invite, register-and-accept and grant delivery.
- **The browser is measured from outside.** No `performance.mark` goes into `web/src`. The browser numbers cannot
  split crypto from network or rendering; the crypto microbenchmarks give that split.
- **Scripts in Python, except where the crypto lives.** The seeders and the API client reuse `@veyq/crypto` and
  `web/src`, so they are TypeScript in `web/scripts/` and go through the generated client and `apiTransport.mts`
  (lint rule `veyq/seeders-use-the-generated-client`). The browser driver uses the workspace's Playwright. One Python
  script in `scripts/` runs a stage, samples the containers and writes the readings.
- **The conclusion stays; the readings and the harness go** (owner 2026-10-18). Raw readings never enter git. The
  harness outlives this CR only until `cr-volume-measurement-cax` has used it; that CR deletes it.

### Measured substrate (worktree `load-testing-data-server-4b2e42` at `c9e4310a`)

**Re-grounded at implement time on `a1898747`** (25 commits later: the moderation CRs). Measured: `git diff --stat
c9e4310a..a1898747` over every file the tables below cite changes three contracts only (`_common`, `instance`,
`moderation`), none of them a cited operation; every web, backend, script and site locator is byte-identical, so the
tables hold as written. Two line numbers were already off by a line or two (`bootstrap-user.mts:250` is the comment
above `onboardUser2`); the symbols are what the harness follows.

**Host and recipe**

| Fact | Locator |
|---|---|
| The recipe's limits: application `mem_limit` 1 GiB, heap 60 % of it; MariaDB and Garage unlimited on Compose | `site/src/content/docs/run/plan.md` § Size the host |
| The release is built per architecture, arm64 included | `scripts/run_pipeline.py:463-470`; `ci.yml`'s `stack` job matrix |
| A local install of the production recipe through the release's own `veyqctl`, TLS through Pebble, kept with `--keep` | `scripts/run_stack_e2e.py` module docstring, stages 0–2 and 10 |
| `veyqctl --unsigned-local` installs local images, a bare `name:tag` only | `scripts/run_stack_e2e.py:4-5` |
| A first-run setup from Node, on a fresh instance only (`setupRequired === true`) | `birthInstance`, `web/src/test/e2eBootstrap.ts:164` |

**Photos**

| Fact | Locator |
|---|---|
| The first list call returns the whole Chapter in one response, with no page or limit parameter | `grep -rnE "name: (cursor\|limit\|pageSize)" contracts/api` → 0 lines; `MediaResource.listNodeMedia`, `backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaResource.kt:81` |
| About 4 queries per photo when a row is mapped (original blob, derivative list, each derivative's blob) | `MediaStore.toRow`, `MediaStore.kt:301` |
| `wrapped_exif` (up to 16 KB) is loaded with every listed row and never returned | `MediaEntity.wrappedExif`, `backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/Entities.kt:68` |
| No composite index for the list's filter and `ORDER BY updated_at, id` | `grep -n "ix_media" backend/apps/memories/src/main/resources/db/migration/V202610041201__memories_baseline.sql` |
| The gallery fetches every thumbnail in one unbounded `Promise.all` | `web/src/features/memory/useThumbnailPipeline.ts:46` |
| One presign POST per blob, though the endpoint accepts batches of up to 256; each presign also re-reads the scope's moderation blocks | `presignAllChunks`, `web/src/core/blob/transport.ts:239`; `maxItems: 256`, `contracts/api/blob.openapi.yaml:388`; `BlobUploadHandler.download`, `backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/BlobUploadHandler.kt:268` |
| The grid is not virtualized; every tile is a plain `<img>` | `web/src/features/memory/components/MediaGrid.vue`, `components/MediaTile.vue:47` |
| The seeding entry point, without derivation or EXIF | `addCopiedMedia`, `web/src/features/memory/media.ts:712` |
| Seeding has no rate limit; completes into one Chapter serialize on its node row, and each makes one store HEAD per chunk | `@RateLimitExempt` on `MediaResource`, `BlobResource`; `MediaStore.attach`, `MediaStore.kt:124`; `verifyBlob`, `BlobUploadHandler.kt:239` |
| The live poll re-reads every 20 s while a Chapter is open | `web/src/features/memory/useMediaGalleryLive.ts:324` |

**Tree**

| Fact | Locator |
|---|---|
| The whole scope tree in one response, unpaged; 304 on an unchanged ETag | `listNodes`, `contracts/api/content.openapi.yaml:95`; `NodesHandler.list`, `backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodesHandler.kt:217` |
| One query for the rows plus one `IN` query for identity keys, not per node | `NodesStore.list`, `backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodesStore.kt:214` |
| Every node's name is opened (HKDF + AES-GCM) and its content key unwrapped, top-down, on the main thread | `openEntries`, `web/src/core/node/tree.ts:142` |
| The child lists are built by copying the array on every row (`[...old, r]`), quadratic in the number of siblings | `web/src/core/node/tree.ts:144` |
| The walk takes from the front of an array (`level.shift()`), linear per step, so quadratic in the number of nodes | `web/src/core/node/tree.ts:150-151` |
| The node creators the seeder uses | `createFolder`, `tree.ts:252`; `createMemory` / `createChapter`, `web/src/features/memory/memoryNodes.ts:30,48` |

**Members**

| Fact | Locator |
|---|---|
| The directory is the tenant scope's whole member list, unpaged | `listScopeMembers`, `contracts/api/scopes.openapi.yaml:215`; `ScopesResource.kt:222` |
| Every member's sealed name is opened, then the avatars are synced, one fetch per new avatar version (every one on a first load) | `web/src/core/people/directory.ts:95-110` |
| The full member recipe: an invite carrying an admission tag, the mail, a cold account birth, register-and-accept, then the key-holder claims the grant-delivery job | `onboardUser2`, `web/scripts/bootstrap-user.mts:250`; claim at `:285` |
| **Invites are rate-limited to 20 per tenant per hour and 3 per recipient** | `veyq.ratelimit.invite.*`, `backend/core/src/main/resources/application.properties:167-169` |
| There is no tenant delete, only a suspension (irrelevant on a throwaway instance, but it rules out a tenant as a disposable container on a kept one) | `grep -nE "operationId: (delete\|suspend)Tenant" contracts/api/tenants.openapi.yaml` → `suspendTenant` only |

## Decisions this CR takes

1. **Stages are seeded incrementally.** Each seeder is told a target count, counts what is there and fills the gap,
   so a stopped run resumes and the next stage only adds.
2. **Photos: one fixture per kind.** One small JPEG each for ORIGINAL, THUMBNAIL and PREVIEW, re-sealed per photo
   under a fresh K_blob, with a sealed `wrappedMeta` carrying `w`, `h` and a `capturedAt` spread over several years.
   A tenth are portrait. The upload scenario alone uses originals of a phone's size (decision 7).
3. **Tree: a realistic shape, plus one wide case.** Folders holding Memories holding Chapters (roughly 1 : 5 : 20),
   and once, at 2,000, 2,000 Chapters under a single Memory as the worst case for the quadratic child lists.
4. **Members: complete rows.** Every seeded member is granted, carries a sealed name, and every fifth one an avatar.
   The seeder follows `onboardUser2` member by member.
5. **The server leg, per axis and stage:**
   - From the client: time to first byte and total time, 5 calls (median, min and max), cold after an application
     restart and then warm. Also raw bytes, gzip bytes, the `Content-Encoding` sent, and a 304 call.
   - Query count: MariaDB's `Com_select`, read before and after each call. The counter is global, so no browser is
     open during this leg.
   - On the host: a sampler reads `docker stats` every second for each recipe container (CPU %, memory, and the
     JVM's restarts and OOM kills), plus the host's load and free memory. It runs its commands through a configurable
     prefix (empty here, `ssh <host>` on the CAX), so the remote CR uses it unchanged.
6. **The browser leg waits on states, never on durations.**
   - Photos: the list arrived, the first tile is in the DOM, every derivable tile's `<img>` has a `blob:` source.
   - Tree: from unlock to the sidebar listing the seeded folder, and to a deep Chapter opening by URL.
   - Members: from opening the members page to every row named and every seeded avatar shown.

   Each run records the long tasks (a `PerformanceObserver('longtask')` the init script installs), the JS heap and
   DOM node count (CDP `Performance.getMetrics`), and the request count by kind (Resource Timing). Three runs each,
   unthrottled and with a 4× CPU throttle (a mid-range phone).
7. **Household scenarios**, on the full dataset, with the host sampler running throughout:
   - **Idle:** 30 minutes with nobody connected; the background sweeps run.
   - **Five at once:** five members' browser contexts open the 10,000 Chapter together, through the real client.
     Replaying the real client matters because its unbatched presigns and parallel thumbnail fetches are the load.
   - **Bulk upload:** one member uploads 1,000 photos with 4 MB originals through `addCopiedMedia`.

   Locally the browsers run on cores outside the instance's cpuset, so they do not take the instance's CPU.
8. **The crypto microbenchmarks** are `vitest bench` files beside the code they measure:
   - Photos: 10,000 × (`unsealMeta` + `unwrapBlobKey` + `openFrame` over a thumbnail-sized frame).
   - Tree: `openTree` (`tree.ts:214`) over synthetic sealed rows in both shapes from decision 3.
   - Members: the directory's name opening over synthetic rows.

   `vitest run` does not collect bench files, so `npm run check` does not change.
9. **The invite limit is raised only while seeding members**, through the instance's environment, and the instance
   is restarted with the defaults before anything is measured. The member seeder stops on a 429.
10. **Where the local result lands.** The local verdict per stage and the exact procedure (boot, seeding and
    measuring commands, the Q1 rule as decided) are written into `cr-volume-measurement-cax`, which needs them and
    outlives this CR. Each hotspot whose numbers already justify a fix becomes an inbox row now. A hotspot is a
    property of the code, not of the host. The sizing text in the Run area and §10, and the §11 rows, are the CAX
    CR's. No reading tables are kept.

## Open questions (owner)

**Decided (Q1–Q2).** Owner 2026-10-18 ("Ja passt alles"): both recommendations are taken. Q1's rule is the
judging rule for this CR and the CAX one; Q2's throwaway instance replaces the earlier placement.

- **Q1: What counts as "carries"?** Without a rule, the numbers end in an opinion. This is a judging rule for the
  recommendation, not a product target. **Recommendation:** a host carries a stage when, across that stage and all
  three scenarios:
  - no container is OOM-killed or restarted;
  - peak host memory stays at or below 80 % of its RAM;
  - no request fails;
  - on a desktop browser, a 3,000-photo Chapter's list arrives within 2 s and its last thumbnail within 15 s.

  The 10,000 stage is reported against the same rule but does not decide the recommendation. The CAX CR judges
  by the same rule, so it is decided here once.
- **Q2: Replacing the earlier placement.** Measuring the production recipe on a throwaway instance (§ Context)
  supersedes "photos in the dev user's personal space" and "members in a tenant of their own". Nothing is seeded into
  the dev instance, and nothing needs cleaning up there. **Recommendation:** confirm.

## Phases

- [x] **0 boot path**: a fresh production-recipe instance on this host, through `scripts/run_stack_e2e.py`'s install
  stages and `--keep`, or through `veyqctl install` directly if the lane cannot stop before its own first run. The
  harness performs first-run setup itself (`birthInstance`'s path), so it holds the owner's credentials. Pin the
  recipe's containers to 2 cores and 4 GB. Find out how the instance's environment overrides the invite limit
  (decision 9). · Done when: `birthInstance` succeeds against the instance; `docker inspect` shows the cpuset and
  the limits; the steps are written into this CR. **Bite:** a second `birthInstance` against the same instance is
  refused (`setupRequired` false), so the harness can never set up an instance it did not start fresh.
  - *As built.* The lane cannot stop before its browser suite (`run_stack_e2e.main` has no such switch), so
    `measure_volume.py boot` imports the module and calls its stages itself: `ports_free`, `browser_pins`,
    `browser_image`, `assets`, `release_mirror`, `pebble`, `install`, `certificate` — the instance is installed by the
    release's own `veyqctl --unsigned-local`, as L1's is, and nobody sets it up. The steps:
    1. images from this tree, bare `name:tag`: `docker build -f Dockerfile -t veyq-vol:local .` and
       `docker build -f admin-web/Dockerfile -t veyq-admin-vol:local .` (on this Mac with a `DOCKER_CONFIG` that
       has no keychain credential helper);
    2. `python3 scripts/measure_volume.py --instance DIR fixtures` (ImageMagick), then `… boot --app veyq-vol:local
       --admin veyq-admin-vol:local` (ports 80, 443 and 18081 must be free: one such stack per host);
    3. `… seed birth` — `birthInstance` itself, under tsx, then the tenant's mail pointed at the overlay's relay as
       the app reaches it (`mail.relay.test:1025`); the generated owner credentials land in `DIR/accounts.json`.
  - *The pin* is the overlay's `cpuset` (`2,3` for app, MariaDB, Garage, Traefik and admin; `6-13` for Pebble,
    Mailpit, the release mirror and the browser), not `docker update`: it survives the app's recreation by
    `veyqctl start`. *Memory is not held to 4 GB as a whole* — Compose has no group limit; a cgroup parent would need
    a privileged container in the Docker VM — so the 4 GB is a **judged** budget: the recipe's containers' summed
    memory against Q1's 80 %. A stage that passes it never reaches the real box's ceiling, so the rule loses nothing;
    the app keeps the recipe's own `mem_limit` (1 GiB). The overlay also drops L1's `RUST_LOG: garage=info` (main
    gained it on 2026-10-18 for the wire sweep's control): the measured store logs as an operator's does, `warn`.
    The phase 0–7 numbers below were taken before that recipe change, with Garage's image default (`info`).
  - *The invite limit (decision 9)*: Quarkus reads `VEYQ_RATELIMIT_INVITE_PER_TENANT_MAX_REQUESTS`; `… invite-limit N`
    adds it to the app's `environment` in the instance's `compose.override.yml` and runs `veyqctl start`, which
    recreates the app; `… invite-limit default` removes it again. Proven: with 100000 the seeder passed the 21st
    invite, without it the 21st was a 429.
  - Measured: `birthInstance` succeeded (20 s); `measure_volume.py status` showed `cpuset 2,3` and the app's
    `memory 1073741824`; the second `birth` failed on `run against a FRESH instance (setupRequired=true): expected
    false to be true` and wrote nothing.
  - *Re-seed time*, this Mac under load 15–35 (not a reading): boot about 1 min with the images present, birth 20 s,
    photos about 2,400 a minute, tree nodes about 3,800 a minute, members about 9.5 a minute end to end (16.7 joined,
    22 profiled; Argon2id dominates). `teardown` moves the instance's files to `DIR/torn-<project>/`; `boot` refuses a
    directory that still holds any.
- [x] **1 photo seeder**: `web/scripts/seed-volume.mts photos --target N` (decision 2). It prints photos per minute
  every 500. · Done when: `npm run check` in `web/` green; seeded to 1,000; the Chapter opens with real thumbnails.
  **Bites:** a second run with the same target adds nothing; a run stopped midway reaches the target exactly.
  - *As built.* The seeders run the app's own modules under tsx (`volumePrelude.mts` gives them Vite's one define;
    `volumeHarness.mts` the instance, the sign-in and the files), through a pinned HTTPS `fetch` in `apiTransport.mts`
    (the domain to `127.0.0.1:443`, the run's Pebble root, the refresh cookie kept). Placement: the photos in the
    tenant space, Memory 'Volume photos', Chapter 'All photos' (the five members of decision 7 can open it); the
    bite's Chapter 'No derivatives' beside it. Fixtures: thumbnail 512 px ≈ 39 KB, preview 2,048 px ≈ 730 KB,
    original 1,024 px ≈ 190 KB, phone original 12 MP ≈ 4.0 MB.
  - Measured: killed at 25 s (857 of 1,000), the next run added 143 and held exactly 1,000; the run after printed
    `nothing to add`. The browser leg then decrypted 1,000 of 1,000 thumbnails (`blob:` sources). `npm run check` in
    `web/` green (383 test files passed, 27 skipped; no bench file collected).
- [x] **2 server leg and host sampler**: `web/scripts/measure-volume.mts` plus `scripts/measure_volume.py`
  (decision 5). Readings go to `.run/measurements/` (`.gitignore` gains it). · Done when: one photo stage measured
  end to end with the sampler running. **Bites, with controls:** the 304 call shows almost no queries (control: the
  full call's count grows with N); at 1,000 the count matches the `toRow` shape (≈ 1 + 4N) or the difference is
  explained; with a browser left open on the Chapter, the script notices the count drifting and refuses the
  reading. Under a deliberately tiny `mem_limit`, the sampler reports the OOM kill instead of a quiet gap.
  - *As built.* A seeder may not shell out (lint `veyq/seeders-use-the-generated-client`), so `measure-volume.mts` is
    a child of `measure_volume.py` speaking a line protocol, and the driver reads `Com_select` around each call. The
    cold calls each follow a `docker restart` of the app; the sampler tells those apart from an unbidden end (an
    `oom`, or a `die` with any exit but 143). The drift windows are 60 s, the tolerance the idle baseline's worst
    window plus 10: with 30 s windows an open Chapter's live poll slipped through one of the two.
  - Measured (under load, not readings): at 1,000 the full call made 4,011 SELECTs every time, cold and warm — the
    `toRow` shape 1 + 4N plus ten for the request's own checks; the 304 call 9. Idle baseline 36–37 per minute; with a
    browser held open on the Chapter the windows read 110 and 87 against a tolerance of 47 and the reading was
    refused (exit 3); without it 37 and 37, accepted. `docker update --memory 64m` on the app: the sampler reported
    `oom` and `die 137`, the limit was restored and the app came back healthy. The answer is 2.34 MB, sent without
    `Content-Encoding`; gzip would make it 1.33 MB.
- [x] **3 browser leg**: `e2e/measure/volume.ts` (outside `e2e/tests`, so lane L1 does not collect it), one entry
  per axis (decision 6). · Done when: one photo stage measured, 3 runs × 2 throttle settings. **Bite:** pointed at a
  Chapter whose photos have no derivatives, the "every thumbnail decrypted" wait reports 0 of N and stops, rather
  than hanging or passing. **Control:** its list-response time and the server leg's differ by no more than network
  overhead.
  - *As built.* It runs in the overlay's Playwright image (`docker compose run browser -c measure/playwright.config.ts`)
    on the run's networks and the outside cores, the Pebble root trusted in its NSS db as in L1; spec and result are
    `DIR/browser-spec.json` / `browser-result.json`. Each run signs in at the tenant root, then opens the target by
    its fragment in the same document, so the clock starts at the open. Failed requests count from the open on (a
    fresh device's boot answers `token/refresh` 400 by design). `e2e/tsconfig.json` includes `measure`.
  - Measured (under load): 1,000 photos, 3 runs × {1, 4}: list 227–359 ms (request 198–331 ms) unthrottled, last
    thumbnail 17.8–23.1 s; ×4 last thumbnail 95–112 s. The warm server leg read 171–345 ms total: the control holds.
    One open requests the list once, 1,000 presigns (`blob-downloads-batch`, one blob each) and 1,000 store GETs
    (≈ 40 MB). 'No derivatives' with 50 photos: `0 of 50 thumbnails decrypted (50 tiles); nothing moved for 20s`.
    `npm run check` in `e2e/` green; `playwright test --list` names no `measure/` file.
- [x] **4 tree seeder**: `seed-volume.mts tree --target N`, and `tree --wide 2000` (decision 3). · Done when: seeded
  to 500; the sidebar shows the folder and a deep Chapter opens. **Bite:** the server leg's tree count equals the
  seeded count plus the nodes there before (counted first).
  - *Adapted:* the sidebar lists spaces, not folders (`AppSidebar.vue`), so the leg waits for the folder at the
    space's root view (`main`). The realistic tree lives in the group 'Volume tree', the wide one in 'Volume wide',
    so neither counts into the other or into the tenant's tree.
  - Measured: seeded 500 into an empty group; the server leg listed 500 items with 10 SELECTs whatever N (one rows
    query plus one `IN`, as the substrate says); the root view showed 'Folder 000' 136 ms after the open, the deepest
    Chapter's heading 148 ms.
- [x] **5 crypto benches**: decision 8. · Done when: `npx vitest bench` prints per-operation and total times;
  `npm run check` unchanged. **Control:** the tree bench at 500 and at 5,000 rows in the realistic shape; if the time
  grows by about 10×, the quadratic terms do not matter yet at this size, and if it grows by much more, they do.
  - *Adapted:* the tree bench seals every row as a FOLDER — `openTree` reads no kind, and core may not name an
    application's (`validate_web_core_imports_no_applications.py`); the shape is the seeder's.
  - Measured (`npx vitest bench --run` on the three files, under load): openFrame of a 40 KB frame 0.47 ms, unsealMeta
    0.020 ms, unwrapBlobKey 0.012 ms; 10,000 × all three 5.06 s. openTree 14.4 ms at 500, 153.9 ms at 5,000 (10.7×:
    the quadratic terms do not matter yet), 58.7 ms wide 2,000. Names: 0.36 / 3.5 / 17.5 ms for 20 / 200 / 1,000.
- [x] **6 member seeder**: `seed-volume.mts members --target N` (decisions 4, 9). · Done when: seeded to 20; the
  members page names all 20 and shows their avatars; accounts per minute recorded (Argon2id included). **Bite:**
  with the default invite limit, the seeder stops at the 21st invite with a message naming the limit, rather than
  reporting a short count as done.
  - *As built.* Each member: a cold birth, the owner's `inviteMember`, the mail read from the overlay's Mailpit,
    `registerAndAccept`, the app's own `runGrantDeliveryWorker` as the owner; then, signed in as the member, a sealed
    name and every fifth a picture. The owner is named too, so "every row named" covers the list.
  - Measured: 19 joined in 1.1 min (16.7 a minute), profiled at 22 a minute; then `--target 25` with the default limit
    stopped with exit 3: `invite 2 of this run (member 22 of 25) was refused with 429: invites are limited per tenant
    per hour (veyq.ratelimit.invite.per-tenant-max-requests, default 20) … The tenant holds 21 members, NOT the
    target 25.` With the limit raised it reached 25; the members page showed 25 rows named and 5 pictures.
- [x] **7 scenarios**: decision 7, as `measure_volume.py scenario <name>`. · Done when: each scenario has run once
  locally with the sampler. **Control:** the idle scenario's memory is flat after its first five minutes; if it
  climbs, that is a finding, not noise.
  - Measured, each once and under load (not readings): idle 30 min — the app's memory went from 266 MB after five
    minutes to 300 MB at the end (+13 %): a lead for phase 8, which repeats it quietly. Five at once on the 1,000
    Chapter: each device's list at ≈ 1.55 s, its last thumbnail at 27.6–30.3 s, no failed request, the recipe's peak
    1.15 GB. Upload of 50 phone photos one at a time: 435 a minute, ≈ 35 MB/s of plaintext. All three judged
    "carries" with the load note.
- [ ] **8 local run**: every stage of every axis and every scenario on the pinned local instance; the readings judged
  against Q1's rule. · Done when: the local verdict is written into this CR with the hotspots it found. A stage that
  already fails locally is a finding on its own; the CAX run still goes ahead, because the owner's question is
  about that box.
- [ ] **9 hand over**: write the local verdict, the procedure that produced it and the Q1 rule as decided into
  `cr-volume-measurement-cax`'s § Local pre-check; add an inbox row per hotspot worth fixing, each naming the number
  that justifies it; `infra/README.md` gains no line, because the harness is temporary. Tear down the local instance.
  · Done when: the CAX CR can be executed from its own text without this one; the trust-chain guards green.

## Definition of done

Every phase's Done-when; the harness committed and working; the CAX CR carries the local verdict and the procedure;
every `scripts/validate_*.py` except `validate_guard_gaps.py` green; no file under `web/src` changed except the bench
files.

## Retrospective

_Filled at implementation._
