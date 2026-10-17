---
id: cr-report-and-hold
title: Anyone who sees something can report it; a report seals to the moderation key and holds a snapshot
status: planned
satisfies: []            # phase 0 writes the use-case and the enabler; see § Use-cases
adrs: ["0093", "0099", "0062", "0079", "0051", "0072", "0080", "0034", "0083", "0097"]
invariants:
  - operator-blind E2EE (a dump opens no report; the server learns category, time and ids, nothing more)
  - a hold is invisible by construction (every delete, overwrite and revoke keeps running; only the sweep skips a pin)
  - core never names an application table (ADR 0079): applications contribute their rows through a seam
---

## Goal

Build ADR 0093 §§ 1, 2, 4 and 5: a member, or a guest of a link, selects what they see and reports it; their client
mints a **report share** for exactly that — a node's key, a medium's blob keys, a field's `K_field` — under a fresh
`K_report`, seals `K_report`, their text, their contact and the context to the instance moderation key's current epoch,
and sends it with the category and the ids in the clear. The server checks that every id lies inside what the
reporter reaches, opens **one case per reported item** (a further report attaches), takes a **hold** — a snapshot of the
affected rows plus a pin on their storage keys that `StoreReclaimSweep` skips — and mails every accepted moderator
(the urgent categories at once, the rest bundled). Nothing is blocked. Reviewing and deciding are
`cr-moderation-review-and-measures`'; until it lands a hold ends only by its 90-day bound.

## Context & constraints

- Entry conditions: `cr-moderators-and-the-moderation-key` **done** (the published bundle, the role, the proof gate,
  the log table, mail to confirmed moderators). Re-read its decisions 1, 3 and 5 against the merged tree before the
  first edit (`changes/README.md` § A sibling CR still in flight is cited as PLANNED). The instance contact's
  notification address (`cr-instance-contact-and-provider-identification`) joins the new-case mail **if it has
  landed**; the mail phase reads the merged tree and says which.
- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 1 (what a report share carries per kind; who may report what; the dialog's fields; the 100-item cap; invisible and
  inert), § 2 (sealed vs clear; nobody else learns; the mail), § 4 (snapshot, pin, the sweep's skip "is not a failed
  attempt", the hold-end table, extension), § 5 (bounds), § 9 (a field's key); [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 2 (seal to the current epoch's public half; a report stays sealed to its epoch) and § 3 ("until an epoch exists
  the report action is off"); [ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md) (the
  one choke point every store object passes); [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
  (core / application direction).
- The guest-write record that § 4 also copies into the hold does not exist yet; `cr-guest-write-record` extends this
  CR's snapshot seam with its rows.

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| `veyq_share` has a `role`, no kind column; its owner, scope and node are NOT NULL and the scope's erase deletes it | `sed -n 405,441p backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql` |
| Every reader of share rows that a report row stored there would meet (listings, bulk switch-offs, node purge, scope erase, the guest gate's stops) | `grep -n 'fun listByOwner\|fun listByNode\|fun turnOffAllInScope\|fun deactivateSharesByOwnerInScope\|fun tombstoneAllOn' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt`; `grep -n 'class ShareScopeEraser' -A8 backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareScopeEraser.kt`; `grep -n 'SPACE_UNAVAILABLE' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareGuestGate.kt` |
| The sweep, its due-selection and `defer` (which counts an attempt and abandons at the maximum) | `grep -n 'fun due\|fun defer\|attempts +=\|abandonedAt = now' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreReclaim.kt`; `grep -n 'fun run\|handOver' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreReclaimSweep.kt` |
| Every path that queues a storage key for reclaim | `grep -rn 'reclaim\.record(' backend/core/src/main backend/apps/*/src/main` (second angle, keyed on the producer of the key: `grep -rn 'storageKey, it.chunkCount\|blob.storageKey' backend/*/src/main backend/apps/*/src/main`; the arity rule is `scripts/validate_store_reclaim_arity.py`'s docstring) |
| The rows a snapshot copies: core's node, attachment, blob, share identity, epoch wraps; Memories' bucket, media, derivative; Depot owns no table | `grep -n 'CREATE TABLE' backend/core/src/main/resources/db/migration/*.sql backend/apps/memories/src/main/resources/db/migration/*.sql`; `find backend/apps/depot -name '*.sql'` (empty) |
| The seam shape to follow: phase-ordered core interface, implemented per application | `grep -rn 'interface NodeSubtreeEraser\|override val phase' backend/core/src/main backend/apps/*/src/main` |
| Guest identities are deleted once nothing names them (a snapshot copies them) | `grep -n 'fun dropDeadIdentities' -A6 backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt` |
| What a guest reaches: the blobs of a shared subtree (the reach check for a guest's report) | `grep -n 'fun authorizedBlob\|fun subtreeBlobIds' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt` |
| A node's name seals under its PARENT's key, so `K_node` does not open its own name (decision 3) | the `node-name` row of `docs/features/platform/enablers/en.platform.field-key.md` § What is sealed under what |
| Field keys a client can hand over | `grep -n 'export const fieldKeyOf\|export const openFieldWith' packages/crypto/src/seal.ts packages/crypto/src/v1.ts` |
| Where "Report" goes in the UI: the selection bar, the row's core actions, the header's core actions, Memories' lightbox | `grep -n 'veyq-level-selection-bar\|coreRowActions' web/src/core/node/LevelView.vue`; `grep -n 'export function favoriteRowActions' web/src/core/favorites/favoriteActions.ts`; `grep -n 'CoreHeaderAction' web/src/core/node/nodeHeader.ts`; `grep -n 'media-lightbox-actions' web/src/features/memory/components/MediaLightbox.vue` |
| A WRITE_ONLY link's page is the application's `shareBody`; the share page's gate order | `grep -n 'shareBody\|needsTerms' web/src/views/ShareView.vue` |
| Rate-limit keys available (IP, USER, SHARE, …) | `grep -n 'enum class RateLimitKey' -A8 backend/core/src/main/kotlin/me/raddatz/veyq/core/ratelimit/RateLimit.kt` |
| The backup's blob index assumes only `veyq_blob` and `veyq_upload_session` carry a storage key (a hold table with pinned keys meets it) | `grep -n 'def blob_index' -A10 scripts/instance_backup.py` |

## Decisions this CR takes (each with the line that settles it)

1. **A report share is a table of its own, `veyq_report_share`**, not a `veyq_share` row with a new role. ADR 0093 § 1
   wants it in no listing, revocable by nobody, outliving the original share and the tenant's suspension; the
   substrate table above lists six readers of `veyq_share` that would each need a filter, and the scope erase would
   delete it. A separate table makes "invisible and inert" true by construction.
2. **Cases and reports:** `veyq_moderation_case` (one per reported item: kind and id unique while the case is open;
   category = the most urgent of its reports; opened at; state) and `veyq_moderation_report` (case, epoch, time,
   category, the sealed bundle). Clear: category, time, ids. Sealed to the epoch: `K_report`, text, contact, context
   (ADR 0093 § 2). A second report of an open case's item attaches (§ 5); the hold is not taken twice.
3. **What each kind carries** (ADR 0093 §§ 1, 9; "which keys a medium report carries is the report's own decision"):
   a node → `K_node` **and its own name's `K_field`** (the name is under the parent's key) and its meta's `K_field`;
   a medium → the `K_blob` of every blob it has, plus its `media-meta` and `media-exif` `K_field`s; a guest upload →
   through the drop key and, only on "everything by this uploader", the identity key; a field → that revision's
   `K_field`; a `WRITE_ONLY` link → its `K_share` and the link's name and note `K_field`s.
4. **The hold is core's, filled through a seam** `HoldSnapshotContributor` (phase-ordered like `NodeSubtreeEraser`):
   core copies node, attachment, blob, share-identity and scope-epoch rows (the wraps a held key chain needs); Memories
   copies bucket, media and derivative rows. Ciphertext and metadata only. Pins are rows of `veyq_hold_pin`
   (storage key, hold, `expires_at`).
5. **The sweep's skip is in the selection**, not after it: `StoreReclaimStore.due` leaves out keys an active pin holds,
   so `defer` is never called on them (ADR 0093 § 4: "a skip is not a failed attempt"). When the last pin goes, the
   row is due as it was.
6. **Hold ends in this CR:** a case-open hold ends at 90 days after the report; the other rows of § 4's table are set by
   the CRs that build their triggers (dismissal and measures: `cr-moderation-review-and-measures`; the statement:
   `cr-statement-of-reasons-and-objection`; orders and the CSAM handover: `cr-authority-orders-and-art18-reports`).
   The purge that ends an expired hold drops the snapshot and the pins (`@Scheduled`, so it also runs at the first
   trigger after startup).
7. **The report action is off until an epoch exists** (ADR 0099 § 3) — read from the unauthenticated bundle read of
   `cr-moderators-and-the-moderation-key`.

## Open questions (owner)

- **Q6 — The reporter's receipt.** ADR 0093 § 2: "A reporter who left a contact gets a receipt" (DSA Art. 16(4)), but
  the same section seals the contact to the moderation key, so the server cannot address the receipt; and ADR 0097
  decision 3 lets instance mail reach only the Server Owner, moderators and the instance contact.
  - (a) The reporter's client sends the contact once in the clear beside the sealed copy; the server mails the receipt
    from memory and stores only the sealed copy; instance mail is widened to "a reporter, for their receipt and
    decision" (ADR 0097 decision 3 edited).
  - (b) No mail: the dialog's success screen is the receipt (case reference and time); the decision is mailed later by
    the moderator's client handing the unsealed address over (`cr-statement-of-reasons-and-objection`).
  - (c) The receipt is mailed when a moderator first opens the case, by their client.
  - **Recommendation: (a)** for the receipt, (b)'s on-screen receipt as well. Art. 16(4) asks for it "without undue
    delay"; (c) depends on a moderator's availability, and (b) alone gives a reporter nothing to keep. The server sees
    the address for the length of one send, as it sees every recipient of every mail; the stored copy stays sealed.
- **Q7 — What is "bundled"?** ADR 0093 § 2 mails the last two categories "bundled" without a cadence.
  **Recommendation:** one mail per moderator per day at most, listing the count of new cases per category; a day
  with an urgent mail already sent still gets its bundle.
- **Q8 — A cap on open reports for a guest.** § 5 caps "a reporter's open reports"; a guest has no account and, on a
  `READ` link, no identity. **Recommendation:** the cap counts per account for members; guests are bounded by the
  rate-limit posture alone (`IP` + `SHARE`), stated in the use-case as the residual.

## Use-cases

Phase 0 writes `uc.moderation.report` (member and guest; the dialog; the categories; the WRITE_ONLY case; the
action off before an epoch) and the enabler `en.moderation.hold` (snapshot, pin, the sweep's skip, the ends table and
who sets each), with planned coverage, under the feature `cr-moderators-and-the-moderation-key` created.

## Phases

- [ ] **0 docs** — the use-case, the enabler, planned coverage; ADR 0097 decision 3 per Q6. · Done when:
  `validate_feature_format.py`, `validate_coverage.py`, `validate_doc_links.py`, `validate_changes.py` green.
- [ ] **1 crypto** — the report bundle: `K_report` minted, the per-kind wraps of decision 3 under it, the sealed part
  (`sealTo` a new purpose `moderation-report` [epoch] to the epoch's X-Wing public half); vectors. · Done when:
  `npm run check` and `npm run mutation -- --force` in `packages/crypto/` (exit code in the commit), the conformance
  test both ways. **Bite:** open a report sealed to epoch n with epoch n+1's key → refused (test red if it opens).
- [ ] **2 contracts** — submit a report (member: bearer; guest: the share's existing credentials, password token
  included; a WRITE_ONLY guest: the link only), the response a case reference and time; classified in
  `OperatorBlindProbeTest`; bounds on every field and the 100-item cap in the schema. · Done when:
  `npm run generate:api` (web), the contracts guards, `./gradlew check` green or the predicted red named (a new
  operation on the existing `sharing` tag reddens `SharingResource` until phase 3: say which).
- [ ] **3 backend: report and case** — decision 1's and 2's tables; the reach check per kind (a member's scope grant;
  a guest's `authorizedBlob`/subtree; a WRITE_ONLY guest only its link); attach to an open case; the instance
  events of the log for "received" and "report attached"; posture `IP` + `SHARE` (guest) and `USER` (member); Q8's
  cap. · Done when: `./gradlew check` and `run_backend_races_on_mariadb.py` green (two reports of one item race).
  **Bites, each with its positive control:** a guest reporting a node outside its link's subtree → refused while one
  inside is accepted; a READ guest reporting a medium of the link → accepted, of another memory → refused; the same
  item twice → one case, two reports.
- [ ] **4 backend: hold** — decision 4's seam and tables, the snapshot in the report's transaction, decision 5's skip,
  decision 6's end and purge, readers in `EveryStoredTimeHasAReaderTest`. · Done when: `./gradlew check` and
  `run_backend_races_on_mariadb.py` green. **Bites:** (i) a held medium deleted by its uploader → the row is gone
  from every member and guest read (positive control: the hold is invisible), its store objects are still there after
  the sweep runs past five attempts' time, and its reclaim row's `attempts` is unchanged; remove the skip → the
  objects are gone (red); (ii) the hold expires → the sweep removes them (the positive control of (i)); (iii) a
  scope erase of the reported space → the snapshot survives (the report reads from it, ADR 0093 § 4).
- [ ] **5 backend: mail** — the new-case mail to every accepted moderator with a confirmed address (and the contact's
  notification address if present): category and "new case" only, no key, no link, no id (ADR 0093 § 2); the urgent
  three at once, the rest per Q7; the reporter's receipt per Q6. · Done when: `./gradlew check` green; a test that the
  mail body carries neither a case id nor a URL (bite: put the case id in the subject → red).
- [ ] **6 web** — "Report" in the selection bar, each row's and the header's core actions, Memories' lightbox (the
  application calls core's dialog: application → core is the allowed direction), the WRITE_ONLY body's link report;
  the dialog with ADR 0093 § 1's fields (reason and good-faith confirmation required; contact optional; the five
  categories; the CSAM category's no-name note), the 100-item cap with the offer to report the enclosing node, the
  report action hidden before an epoch; the client builds the bundle of decision 3. EN+DE. · Done when: `npm run
  check` in `web/` green; a guest-mode mount proves no authenticated client is called (`guardedClients`); a wire test
  asserts at `fetch` that the request carries no plaintext of the reason, the contact or the context; a browser pass:
  a member and a READ guest each report a medium and a folder, the moderator receives the mail.
  **Bite:** send the reason in the clear field → the wire test is red.
- [ ] **7 docs + coverage** — `site/src/content/docs/privacy/is-my-data-safe.md` and its DE twin ("there is no report
  button" becomes false: `grep -n 'report' site/src/content/docs/privacy/is-my-data-safe.md site/src/content/docs/de/privacy/is-my-data-safe.md`),
  a Use page "Report something" (EN+DE), `run/moderation.md` (what a report does and does not do yet),
  `security/what-the-server-sees.md` (a report's clear fields), the security model's T&S "built" line
  (`docs/crosscutting/security-model.md` § Sharing & share-boundary Trust & Safety); coverage refs. · Done when:
  `validate_coverage.py`, `validate_wiki_sources.py`, `npm run check` in `site/` green.

## Definition of done

Every phase's Done-when; coverage for `uc.moderation.report` with `backend` and `web` refs; every
`scripts/validate_*.py` except `validate_guard_gaps.py` green. The structural invariant "no path deletes a pinned
store object" is held by the bite of phase 4 as a permanent slice test; the CR says whether a buildless guard is
worth adding (it is not, if the skip lives in the one `due` query every deletion passes — `validate_store_reclaim_arity.py`
already holds that every store object goes through the reclaim list).

**Coordination:** the hold table's storage keys meet `scripts/instance_backup.py`'s `blob_index` assumption (only
`veyq_blob` and `veyq_upload_session` carry one). That file is being reshaped by the parallel backup-model refine
(ADR 0056/0096/0098): phase 4 names the assumption to that CR's owner and does not edit the script. It matters: once
a held medium's live `veyq_blob` row is deleted, its pinned objects are named only by `veyq_hold_pin`, so an index read
from `veyq_blob` alone leaves them out of a backup's store leg while the database leg still carries the hold that needs
them. Whichever CR lands second makes the index read the pins too (`changes/README.md` § Two CRs that write the same page).

Implementation waits for the owner's go.

## Retrospective

_Filled at implementation._
