# Review backlog: the V1 heavy stage's collection run (2026-10-23)

**Temporary.** This file holds what the collection run found until each item has moved to where it binds: an inbox
row, a CR, a fix, or a line in an existing row. The owner decided the run would only collect findings, and that
everything is reviewed together afterwards. A finding's place is decided in that review, not here. The file is
deleted when its last item has moved.

**Two runs so far, both gitignored and on this machine only:**
- The collection run at `87266fe3`:
  `.claude/worktrees/heavy-collection-997b57/.pipeline-out/heavy-collection-2026-10-23/results.md`.
- The proof run of the first fix round at `d4eab6be`, landed as `bc64b3c9`:
  `.claude/worktrees/heavy-fixes-integration/.pipeline-out/heavy-fixes-proof-2026-10-24/results.md`.

Each step's log lies beside its results file. MS Defender ran without exclusions in both runs.

## Fixed and proven in the proof run (2026-10-24)

| # | What | Proof |
|---|---|---|
| F1 | The `media-batch` deadlock: the attach restarts once when the primary appeared after its lock. The same inversion is closed in `moveMedia`, `deleteBucketReparent`, `createSubBucket` and `MediaNodeRekeyParticipant.lockRows` (order buckets → media → node). | The original race test is green on MariaDB; the full races run is green. The bite on MariaDB went 1213 → 500. **Two new forced tests are still red, see F1b.** |
| F2 | `scopeRotation.e2e`'s suite registered no Memories re-key; the product was right. | web-e2e 27/27 |
| F3 | `move.e2e` sent no `parentEpoch`; the server was right. | web-e2e 27/27 |
| F4 | The cover/banner "exactly one of" rule is now in the schema. The re-key parts example used an unstorable UUID; a new rule in `validate_id_contract_format.py` catches it. The fuzz hook accepts the declared 422 `date_time_out_of_range`. | api-fuzz GREEN |
| F5 | Was a race on a direct load, not scale: the tenant settings pages now wait for the tenant they belong to. At most 6 avatar requests run at once. | 1,000 rows in 1.09 s; a promotion; four deep links |
| F6 | The 400 is the boot's silent token refresh, which is by design; the specs armed their console guard too early. One shared exemption (`tests/e2e/src/signedOutBoot.json`). **Owner question open, see Q1.** | the 400 is gone from the stack lane |
| F7 | Chromium reported unread empty bodies (a PUT 200 with no content, a 304) as `ERR_ABORTED`. The client now reads them. | S2: 0 of 1,176 PUTs aborted; #489 `carries: true` at 1k/3k/10k |
| F8 | A spec defect: `depot-styles` navigated away mid-upload. | `depot-styles` green, the text layer light and dark |
| F9 | The release check backs off: 5 → 10 → … s, capped at a day. **The reset is open, see F9b.** | app log |
| F10 | The upgrade record validator accepts the cover's `blobId`. | `--upgrade` GREEN (the predecessor already carried the fix) |
| F11 | New, found from T3: every ZIP export without the save picker was cut off. The archive's exact length is now passed, and a SW-side failure now reaches the page. Protocol version 4 → 5. | `export-import`'s download passes |
| T1–T9 | Spec isolation (an own tenant for `report-name`), the `instance-mail` console, the Reka and modal selectors, the profile driver's request rule, the seeder's 429 back-off, and the stack lane's after-browser checks running past a red browser stage. | T2, T4, T5, T7, T8, T9 green; T1, T3 and T6 now fail on something else, see L1–L3 |
| N1, N2, N5–N9 | Run in the proof run: two-owner re-key, changed-key review, the #513 and #517 cascades, `REACHED`, S2 with JFR, and the after-browser checks. | green (N5 still has L4 open) |
| R4 | #489 meets every ADR 0105 target at 1,000, 3,000 and 10,000 photos. | 18 runs |

## Still open

| # | Item | Where |
|---|---|---|
| F1b | Two of the new forced race tests are red on MariaDB, reproducibly: `aSecondFirstUploadThatWaitedForTheNodeLocksThePrimaryItMissed` (1205 lock-wait timeout at `NodeEpochGate.lockNode:56`) and `aSubBucketCreateThatWaitedForTheNodeRunsAgainWhenThePrimaryAppeared` ("it locked none of them"). Open whether the test's forcing or the product is wrong. | proof run `step1a-races-slice.log` 133–170, `step1c-races-full.log` 217–240 |
| L1 | `depot-clicks`: after Escape in `FolderNameDialog`, the focus is not back on the New button. Possibly a product bug. | `.e2e-stack-out/20261024-031154/` |
| L2 | `export-import`: the spec expects the folder heading at h1; the page has h2. A spec defect. | same |
| L3 | `field-seal`: `getByText('Profile updated.')` hits both the toast and the live region (strict mode). A spec defect, and a pattern other specs may share. | same |
| L4 | `memories-styles` (the lightbox): a console **409** on `…/scopes/{id}/nodes/{id}`. It took the 400's place: a write with a stale epoch? | same |
| L5 | `memory-banner`: the tab bar sticks 454.7 px beside the compact header instead of to it. Possibly a product bug. | same |
| F9b | The back-off resets to 5 s eight times in 9 minutes, mostly during browser sign-ins. What resets it is open. | proof run, the app log |
| N1b | The re-key job sat LEASED for about 5 minutes on a closed tab's worker before the other owner's worker could take it (`attempts` 2). Open whether a 5-minute lease is the right length. | proof run, N1 |
| N3 | #491's trust reset leaving an empty store: it needs a way to make the device refuse its store. | — |
| N4 | #500, marking past 12,284: it needs a seeder for the sealed mark blob. | — |
| N10 | #491's QR compare needs a camera: the owner's own pass on a phone after the V1 queue. | — |

## Owner questions

- **Q1 (F6):** keep the boot's silent refresh 400 as designed (recommended), or remove it in the product? Two ways to remove it: a local "refresh is possible" mark, which costs one sign-in after the upgrade, or a non-error answer for "no session", which is a contract change and its own CR.
- **Q2:** moved to inbox #534 (owner 2026-10-24: inbox, on the V1 line).

## Readings that need a decision

| # | Reading | Run |
|---|---|---|
| R1 | AEAD: WebCrypto seals ≈ 18× (whole) to ≈ 35× (framed) faster than `@noble/ciphers`. S2 seals on the main thread; GHASH alone takes 1.9 s self time in a 50-photo upload. This is the biggest hotspot. | collection 4a.3, 4a.5 |
| R2 | Chromium's CPU throttle does not reach workers; confirmed again (4.4 s vs 4.2 s). A throttle-4 number is a slower main thread, never a slower device. The profile method and ADR 0105's 4× targets must say so. | both |
| R3 | S3 sends ≈ 116 SQL statements per request. And `GET …/members` at 1,000 members sends **1,012 SELECTs**. Both look like N+1. | collection 4a.4; proof run |
| R5 | #491: a rotation on 999 pins sends 393,728 bytes (3.8 % of the body limit); one pin write is 597 bytes. | collection 4c.1, 4c.2 |
| R6 | #261: the wire share is 9.5 % of an upload at throttle 1 and 3.5 % at throttle 4. Defender was at 86–88 % CPU during the run. | proof run S2 |
| R7 | S1 unlock: 2.9–3.5 s; the main thread is ≈ 94 % idle while the Argon2id worker runs. | collection 4a.2 |
