# Review backlog: the V1 heavy stage's collection run (2026-10-23)

**Temporary.** This file holds what the collection run found until each item has moved to where it binds: an inbox
row, a CR, a fix, or a line in an existing row. The owner decided the run would only collect findings, and that
everything is reviewed together afterwards. A finding's place is decided in that review, not here. The file is
deleted when its last item has moved.

**Four runs, all gitignored and on this machine only.** Each step's log lies beside its results file. MS Defender ran
without exclusions in all four.
- The collection run, at `87266fe3`:
  `.claude/worktrees/heavy-collection-997b57/.pipeline-out/heavy-collection-2026-10-23/results.md`.
- The proof run of fix round 1, at `d4eab6be`, landed as `bc64b3c9`:
  `.claude/worktrees/heavy-fixes-integration/.pipeline-out/heavy-fixes-proof-2026-10-24/results.md`.
- The proof run of fix round 2, at `51725e75`, landed as `e1af30b5`:
  `.claude/worktrees/heavy-fixes-round2/.pipeline-out/heavy-fixes-round2-proof/results.md`.
- The proof run of fix round 3, at `bf5c386b`, landed as `b471733d`:
  `.claude/worktrees/heavy-fixes-round3/.pipeline-out/heavy-fixes-round3-proof/results.md`.

## Fixed and proven

| # | What | Proven in |
|---|---|---|
| F1, F1b | The `media-batch` deadlock is fixed: the attach restarts once when the primary appears after its lock. The same lock inversion is closed in `moveMedia`, `deleteBucketReparent`, `createSubBucket` and `MediaNodeRekeyParticipant.lockRows`, all now in the order buckets → media → node. The two forced tests that stayed red were the test helper: `awaitWaiters` read InnoDB's lock cache, which refreshes only after 100 ms without a read. | round 2: MariaDB races slice and full green, no 1205/1213; the F1 tests take 2.5 s instead of 60 s |
| F2, F3 | Both were test defects. The suite registered no Memories re-key (F2), and the hand-built move body sent no `parentEpoch` (F3). | web-e2e 27/27 (rounds 1, 2) |
| F4 | The contract was wrong in three places: the cover/banner "exactly one of" rule was missing, the re-key parts example used an unstorable UUID (now caught by a new rule in `validate_id_contract_format.py`), and the fuzz hook did not accept the declared 422. | api-fuzz GREEN (rounds 1, 2) |
| F5 | Not caused by scale: a direct load raced the tenant. The tenant settings pages now wait for their tenant, and at most 6 avatar requests run at once. | round 1: 1,000 members in 1.09 s |
| F6 | The 400 was the boot's silent token refresh. It now answers `401 no_session` (#535). The specs exempt exactly that answer (`tests/e2e/src/signedOutBoot.json`). | round 2 |
| F7 | Chromium reported unread empty bodies (a PUT 200, a 304) as `ERR_ABORTED`. The client now reads them. | round 1: #489 `carries: true` |
| F8, L2, L3, L4, L1 | F8 was a spec defect (navigating away mid-upload). L2 was a spec defect (h1 vs h2). L3 was Reka's doubled toast text; there is now a shared `toast()` helper, used by 11 reads. L4 was the product: `settle()` ran twice and wrote the auto date twice. L1 was the product: a menu pick ran its command before focus was back on the opener, which affects every menu → dialog path. | round 2 stack lane: 133 passed |
| F9, F9b | The release check backs off. The resets were the harness restarting the app (`docker restart`), not a sign-in; regression tests with bites. | round 2: 11 resets, each after a start |
| F10 | The upgrade record validator. | round 1 `--upgrade` |
| F11 | The ZIP export's exact length is now passed, and a SW failure reaches the page (protocol 4 → 5). | rounds 1, 2: `export-import` 7/7 |
| T1–T9 | Spec isolation, the `instance-mail` console, selectors, the profile driver's rule, the seeder's 429 back-off, and the after-browser checks running past a red browser stage. | rounds 1, 2 |
| N1, N1b | The two-owner re-key works. The lease was 5 min with no heartbeat (a gap to ADR 0021 § 6). It is now 2 min, renewed every 30 s while the job is worked on. | round 2: a closed tab blocks the job for 2 min 19 s; a heartbeat at 30 s |
| N2, N3, N4, N5–N9 | The changed-key review; the trust reset leaving an empty store (999 → 0 entries); the refusal of the 12,285th mark in EN and DE; the #513/#517 cascades; `REACHED`; S2 with JFR; the after-browser checks. | rounds 1, 2 |
| #534 (part), #535 | #535 is fully built. #534 has the server's shape check and the schema rule; Q3 is still open. | round 2: api-fuzz, web-e2e |
| R4 | #489 meets every ADR 0105 target at 1,000, 3,000 and 10,000 photos. | round 1 |
| Q3, C1 | The root is only `parentId: null`; `parentId == scopeId` is refused with 400 before any state check (owner 2026-10-24). `CommitNodeRekeyRequest` has the schema rule and a shape check before the rights check. | round 3: api-fuzz GREEN, no ST201 on the three operations |
| Q4 | Every `NodeHeader` title is its page's single `h1`, a folder page included; the styleguide has a section on heading levels (owner 2026-10-24). | round 3: `export-import` 7/7, eight folder-heading specs green |
| B1 | The cache was not the cause (a label is image config, not a layer). But the app image set no revision label, the admin image carried nginx's, and `ci.yml` overwrote the labels with metadata-action's (version = branch). Each image now sets its own OCI labels; `validate_release_identity.py` assertion 12; `scripts/image_identity_cache_proof.py`. | round 3: the identity proof OK on a warm cache |
| H1 | `seed members` raises the invite limit and restores the previous value on every exit path. | round 3: 1,000 members without a stop at 22; SIGTERM exits 143 and restores |
| Q5 | 2 min lease, renewed every 30 s, no release endpoint and no self-takeover (owner 2026-10-24); in ADR 0021 § 6 and the config docs. | docs |

## Still open

| # | Item | Where |
|---|---|---|
| L5 | Now a product finding: on a 390 px phone the sticky tab row sits 8 px below the compact header. `--app-sticky-top` is 121 px (`layout.css:521`), but below 720 px the tenant row is 64 px instead of 72 px. The scroll part of the spec now works through `element.style`. A fix is in progress. | round 3, `step4-stack.log` 287–391 |
| H1b | After a SIGTERM the seeder's node child ran on for ~5 s past the restore of the invite limit (502s during the app's restart). The restore should wait for the child to exit. A fix is in progress. | round 3, step 5.3 |
| N10 | #491's QR compare needs a camera: the owner's own pass on a phone, after the V1 queue. | — |

## Owner questions

- **Q1 (F6):** moved to inbox #535 (owner 2026-10-24: 401 with its own code). Built.
- **Q2:** moved to inbox #534 (owner 2026-10-24: inbox, on the V1 line). Partly built.
- **Q3 (#534, the root form): decided 2026-10-24, the root is only `null`; built.** Was: today the server reads `parentId == scopeId` as the root, but it outputs the root as `null`, and the web client never sends `scopeId`. The shape check exempts that form for now, so the contract calls invalid what the server accepts. Recommended: refuse `parentId == scopeId`, so a root is only `null` (ADR 0086). That is one line in the server. Also: the generated TS types do not enforce `parentEpoch` (openapi-generator ignores `dependentSchemas`); catching it at typing time would need a typed client wrapper.
- **Q4 (from L2): decided 2026-10-24, a folder page gets an `h1`; built.** Was: a folder's page has no `h1`; its head is an `h2` (`NodeHeader.vue:19`). Give a folder page an `h1` for accessibility?
- **Q5 (N1b): decided 2026-10-24, 2 min without the two extensions; documented.** Was: keep 2 min as the default lease? A Server Owner who sets it below about 60 s defeats the renewal. Two optional extensions:
  - a neutral release endpoint, called on `pagehide`;
  - letting the same user take over their own still-valid lease at once. This changes ADR 0021's "one worker per job".
- **Q6 (from Q4):** the top level of a space has no `h1`, because no node is named there. Which subject heads it (e.g. the space's name) is a design decision; the styleguide marks it *Open*.
- **Q7 (from B1):** run `scripts/image_identity_cache_proof.py` in the `build` job too? It costs three builds per image; assertion 12 already holds the rule. Recommended: no.

## Readings that need a decision

| # | Reading | Run |
|---|---|---|
| R1 | AEAD: WebCrypto seals ≈ 18× (whole) to ≈ 35× (framed) faster than `@noble/ciphers`. S2 seals on the main thread; GHASH alone takes 1.9 s self time in a 50-photo upload. This is the biggest hotspot. | collection 4a.3, 4a.5 |
| R2 | Chromium's CPU throttle does not reach workers (confirmed again). A throttle-4 number is a slower main thread, never a slower device. The profile method and ADR 0105's 4× targets must say so. | collection, round 1 |
| R3 | S3 sends ≈ 116 SQL statements per request, and `GET …/members` at 1,000 members sends 1,012 SELECTs. Both look like N+1. | collection 4a.4; round 1 |
| R5 | #491: a rotation on 999 pins sends 393,728 bytes (3.8 % of the body limit); one pin write is 597 bytes. | collection 4c.1, 4c.2 |
| R6 | #261: the wire is 9.5 % of an upload at throttle 1 and 3.5 % at throttle 4. Defender was at 86–88 % CPU during the run. | round 1 S2 |
| R7 | S1 unlock: 2.9–3.5 s; the main thread is ≈ 94 % idle while the Argon2id worker runs. | collection 4a.2 |
