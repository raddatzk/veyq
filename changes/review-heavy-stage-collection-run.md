# Review backlog: the V1 heavy stage's collection run (2026-10-23)

**Temporary.** This file holds what the collection run found until each item has moved to where it binds: an inbox
row, a CR, a fix, or a line in an existing row. The owner decided the run would only collect findings, and that
everything is reviewed together afterwards. A finding's place is decided in that review, not here. The file is
deleted when its last item has moved.

The run was at `87266fe3`, one step after another on one machine. MS Defender ran without exclusions and was busy
during much of the profile. Each reading is marked with that. The full record is
`.pipeline-out/heavy-collection-2026-10-23/results.md`, with each step's log beside it. Those files are gitignored and
exist only on this machine, so every item below names its step (`step 1.3`, …) in that record.

## Product findings

| # | Finding | Step | First suspicion |
|---|---|---|---|
| F1 | **Deadlock on MariaDB.** Two concurrent `POST /api/scopes/{scopeId}/media-batch` calls into one Memory: one answers **500**, with a 1213/40001 deadlock at `NodeEpochGate.lockNode` (the node row's `FOR UPDATE`). Test: `MediaCreateRaceSliceTest.aBatchWhoseSecondItemLosesTheRaceStillAnswersItsFirstItem`. | 1.3 | cr-scope-rotation-rekeys-nodes' new lock is taken in an order that conflicts with the batch's own locks. This is the risk that CR named, now measured. |
| F2 | **The remaining owner's worker completes 0 rotations, expected 1** (`src/web/src/features/tasks/scopeRotation.e2e.test.ts:160`, the cr-0092 backstop). | 1.6b | A regression from the re-key CR; ADR 0109's worker path was proven by unit tests only. |
| F3 | **A stale move answers `400 invalid_request` instead of `409 stale_placement`** (`src/web/src/core/node/move.e2e.test.ts:116`). | 1.6a | The new epoch validation runs before the staleness check. |
| F4 | **api-fuzz: 5 failures not in the baseline.** ST201, a schema-violating request accepted: `PATCH /api/shares/{shareId}`, `POST /api/instance/moderation/orders`. ST202, a schema-valid request rejected: `PUT …/nodes/{nodeId}/cover`, `PUT …/nodes/{nodeId}/banner`, `POST …/rekey/nodes/{nodeId}/{rekeyId}/parts`. | 1.7 | The contract and the server disagree, at least partly since the re-key CR (cover, banner and parts take node epochs). |
| F5 | **The members panel does not load at 999 members** ("Could not load members."). `GET …/members` answers 200 with 2.09 MB, then the panel sends one avatar GET per member. A large tenant can then not make a co-owner or review a changed key. | 4c.4 | The list is unpaged and the avatars are not loaded lazily. |
| F6 | **A recurring `Failed to load resource: 400` on a Memory's detail page.** It makes `memories-styles` and `memory-banner` red in their afterEach. | 3.5, 3.12, 5.2b | Not yet identified. Possibly a read without the node epoch. |
| F7 | **Requests reported `net::ERR_ABORTED` while the operation succeeds.** Listing GETs (`…/nodes`, `…/nodes/{id}/media`): 1, 3 and 11 per run at 1,000, 3,000 and 10,000 photos. On S2's upload, 1,163 of 1,176 store PUTs. | 4a.5, 4b.Σ | The client aborts superseded requests, or Chromium reports finished ones that way. This decides whether ADR 0105's "no failed request" holds (F7 is the only target #489 missed). |
| F8 | **The PDF text layer never appears.** In step 3 the PDF's "Open …" button was missing; isolated, `.pdf-page .pdf-text-layer span` never attached in 60 s. | 3.5, 5.2a | It may be the spec. It may be the product, if the text layer no longer renders. |
| F9 | **The release check logs every 5 s** when it cannot reach GitHub ("It tries again at its next run"). | 4c.6 | It does not back off. |
| F10 | **The upgrade seeder's `upgrade-record.json` lacks `memory.cover.mediaId`**, so `upgrade.b-read` never ran. | 1.6c | The seeder was not updated when the cover moved. |

## Test-code defects (not the product)

| # | Defect | Step |
|---|---|---|
| T1 | `depot-clicks.spec.ts` (#372): the spec's `page.route` and the confinement's `context.route('**/*')` both handle a store PUT (`Route is already handled!`, `tests/e2e/src/egress.ts:48`). | 3.2 |
| T2 | `video-share-and-seek.spec.ts` (#104): the guest does not accept the tenant's terms, so the link lands on the terms page. | 3.4 |
| T3 | `export-import.spec.ts` (#379): the Memory's ZIP download reports "canceled". Open whether this is the spec or the SW route, so it may become a finding. | 3.3 |
| T4 | **Suite isolation**: specs leave the shared tenant as they did not find it. `report-name-and-uploader` counts 140 items instead of 2. | 3.9 |
| T5 | `instance-mail.spec.ts` finds the admin console already signed in when run after other specs, and its mail tests are skipped in serial mode. Every mail-dependent spec cascades from it: moderation, takedown, report, statement-and-objection, erasure, and #520's `REACHED`. | 3.7, 3.8, 5.2c |
| T6 | **Selectors moved by recent CRs**: `field-seal.spec.ts:163` (the date dialog's Day combobox, Reka), `memory-guest-live.spec.ts` (no file chooser after Upload, the header actions). Agents write L1 specs without running them, so nothing caught this. | 3.10, 3.11 |
| T7 | The profile driver's S2 check refuses a run on any failed request (F7), so it stopped after one run. | 4a.5 |
| T8 | The member seeder hits `429 rate_limited` in its profile phase at ~500 members, so `stage members 1000`'s own legs did not run. | 4c |
| T9 | `run_stack_e2e.py` skips its own after-browser checks when the browser stage is red: the store sentinel, the guest-write record (ADR 0094), and the wire and canary sweep. The canary sweep is the stack lane's operator-blind proof. web-e2e's run-level sweeps were green. | 3.13 |

## Readings that need a decision

| # | Reading | Step |
|---|---|---|
| R1 | **AEAD: WebCrypto seals ≈ 18× (whole) to ≈ 35× (framed) faster than `@noble/ciphers`** (≈ 60 MiB/s vs ≈ 1,080 / 2,110 MiB/s; both matched the v1 vectors). S2 seals on the main thread, and GHASH alone takes 1.9 s of self time in a 50-photo upload. This is the biggest hotspot found. | 4a.3, 4a.5 |
| R2 | **Chromium's CPU throttle does not reach workers.** At throttle 4 the Argon2id worker and the listing worker are unchanged, while the main thread is ×4.5. A throttle-4 number is a slower main thread, never a slower device. The profile method and ADR 0105's 4× targets must say so. | 4a.1 |
| R3 | **S3 sends ≈ 116 SQL statements per request** (3 requests, ≈ 348 statements, for opening a 1,000-photo Chapter). It looks like N+1. | 4a.4 |
| R4 | **#489: every time, long-task and DOM target in ADR 0105 is met** at 1,000, 3,000 and 10,000 photos, desktop and 4×. Only "no failed request" is missed, through F7. | 4b.1–4b.Σ |
| R5 | **#491: a rotation on 999 pins sends 393,728 bytes** (3.8 % of the body limit) in 2.86 s; one pin write is 597 bytes; 999 pin entries hold 295,704 bytes sealed. | 4c.1, 4c.2 |
| R6 | **#261: the wire is ≈ 9 % of an upload's window** (3.7 s of 39.1 s for 50 × 3.6 MB). This is one run, with Defender busy. | 4a.5 |
| R7 | S1 unlock: 2.9–3.4 s (t1), 3.4–3.5 s (t4). The main thread is ≈ 94 % idle while the Argon2id worker runs 5.35 s summed. | 4a.2 |

## Green on its first run

#414 (5/5), #371's four interruption cases (5/5), `depot.e2e.test.ts`'s streamed step (#371), #500's failed-write
messages in EN and DE, #520's admin console clicked through once, `validate_guard_gaps.py` (90 cases), and every
pipeline job except backend-races, web-e2e, api-fuzz and the app image build (that build's red was a network
timeout; it passed unchanged on rebuild).

## Not run, and what blocks each

| # | Item | Blocked by | Runs again after |
|---|---|---|---|
| N1 | ADR 0109's re-key worker with two owners, in a browser | F2 (the worker is red in web-e2e) and F5 (no co-owner at 999 members) | F2's fix; on a small tenant |
| N2 | #491's changed-key review | F5 | now, on a small tenant; or after F5's fix |
| N3 | #491's trust reset leaving an empty store | a way to make the device refuse its store; flipping a byte in `fetch` did not reach the client | a corruption step on the throwaway instance's stored object |
| N4 | #500: marking past the 12,284 cap | no seeder writes marks | a seeder that fills one member's sealed mark blob |
| N5 | #513: the cascade of `.veyq-card.memory-card` and the lightbox rules | F6 | F6's fix |
| N6 | #517: the PDF text layer's cascade | F8 | F8 settled |
| N7 | #520: `REACHED` | T5 | T5's fix |
| N8 | S2's other 5 runs, and the JFR server side with its canary bite | T7 (with F7) | F7 settled, T7 adjusted |
| N9 | The stack lane's after-browser checks | T9, and a red browser stage | a green browser stage, or T9 changed |
| N10 | #491's QR compare | a camera | the owner's own pass on a phone after the V1 queue |

## Proposed order (for the review, not decided)

1. Fix F1–F4, the re-key regressions. F1 first: it is a 500 on an everyday path (two people uploading into one Memory).
2. Fix F5 and F6, and settle F7 and F8.
3. Fix T1–T9.
4. Run a second, smaller collection run: only the red and not-run items above.
5. Take the R-decisions. R1 and R3 are hotspot rows for #526.
