---
id: cr-guest-write-record
title: A guest's write keeps its client address, port and time for N days, as evidence a hold can copy
status: planned
satisfies: []            # phase 0 writes the enabler and the alternate flows; see § Use-cases
adrs: ["0093", "0094", "0092", "0088", "0089", "0056", "0035"]
invariants:
  - the record never takes an address a client can choose (ADR 0094)
  - no member write and no read is recorded; no User-Agent
  - an address is evidence, never the ground of a block (ADR 0093 § 6)
  - nothing of the record ever reaches a log line (ADR 0092 § 1, the one part of ADR 0092 this CR honours)
---

## Goal

Build ADR 0093 § 10: one core table `veyq_guest_write_record`, a row per guest write that enters a guest as last
editor — an upload's commit, an identity folder's creation, a folder's creation, a rename or move, a medium's meta at
upload — holding the client address from ADR 0094's one resolver, its source port where one is known, the server time
in milliseconds UTC, the share identity, the object and `expires_at`. Switched by `core.public-shares.guest-write-record`
(operator only, on by default); kept **N days** (instance setting, 7 by default, 1 to 30) with the two-limit purge that
never keeps a guest longer than the page told them; purged at startup too; **left out of the instance backup's
database dump** (schema kept); copied into a hold by `cr-report-and-hold`'s seam; disclosed on every guest write page
in one first-layer sentence that links the operator section of the terms. This CR builds **only what ADR 0093 owns**:
the record and its retention setting. ADR 0092 (logs) is proposed, not accepted, and its CR comes after the moderation
work; nothing of § 3–§ 7 there is built here.

## Context & constraints

- Entry conditions: `cr-report-and-hold` **done** (its hold seam takes the record's rows). Independent of
  `cr-moderation-review-and-measures` and `cr-statement-of-reasons-and-objection`: it may run beside them.
- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 10 (all of it), § 4 (the hold copies the rows); [ADR 0094](../docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)
  § For the moderation CR (the slice cases and the stack scenario, verbatim requirements);
  [ADR 0092](../docs/decisions/0092-logs-are-operational-and-name-no-person.md) § 2 (the range 1–30, the default 7, the
  two-limit rule, the startup purge — ADR 0093 § 10 takes these over by reference);
  [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) (the switch,
  operator-only); [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  (the retention setting); [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) (the
  database leg changes: ADR 0093 § Consequences says so).
- **Sibling CR, named, not edited:** `cr-backup-store-leg-is-an-index` (planned; ADR 0056/0096/0098,
  `site/src/content/docs/run/backup-and-restore.md`, `run/upgrade.mdx`) reshapes `scripts/instance_backup.py` and leaves
  what the dump holds as it is (`grep -n -i dump changes/cr-backup-store-leg-is-an-index.md`). This CR's backup phase **starts
  from that CR's merged commit** and adds the exclusion there; if that CR is still open when this one reaches the phase,
  the phase waits or hands the one-line requirement over ("the dump leaves out `veyq_guest_write_record`'s rows, keeps
  its schema") — `changes/README.md` § Two CRs that write the same page name each other.
- The disclosure's full text lives in the operator section (`cr-operator-section-of-the-terms`, last in the order).
  This CR ships the first-layer sentence and its link target's anchor; **no release may ship between this CR and that
  one** (`release: none` today: `grep -n '^release:' scripts/applied_migrations.sha256`).

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| The resolver and its result (address + nullable port); its only consumer today is the rate limiter | `grep -n 'data class Resolved\|fun HttpServerRequest.clientAddress' backend/core/src/main/kotlin/me/raddatz/veyq/core/http/ClientAddress.kt`; `grep -rn 'clientAddress(' backend/core/src/main` |
| The guest writes that set a guest as editor or uploader (angle 1: the editor) | `grep -rn 'PrincipalKey.Share(' backend/core/src/main backend/apps/*/src/main` |
| The same set from the credential (angle 2: every write that takes a contributor token, header or body) | `grep -rln 'xContributorToken\|contributorToken' backend/core/src/main backend/apps/*/src/main` |
| Upload commits that name a guest uploader (core attachment, Memories media) | `grep -rn 'UploadOpener.Contributor(' backend/core/src/main backend/apps/*/src/main` |
| A guest writes no node meta (ADR 0093 § 10's "meta" is a medium's meta at upload) | `grep -n 'wrappedMeta = null' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareNodesHandler.kt`; `grep -n 'fun setMeta' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaStore.kt` |
| None of the guest-write handlers receives the HTTP request (the resource injects it and passes the resolved address down) | `grep -n 'HttpServerRequest\|@Context' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingResource.kt backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/ShareMediaResource.kt backend/apps/depot/src/main/kotlin/me/raddatz/veyq/depot/share/ShareFilesResource.kt` |
| The capability catalog; `serves` must name a built use-case | `grep -n '"core.public-shares' contracts/platform/capabilities.json`; `grep -n 'serves' scripts/validate_capabilities.py \| head -5` |
| The dump is a full `mariadb-dump`, with no table excluded today | `grep -n 'mariadb-dump\|NOT_DATA =' scripts/instance_backup.py` |
| The e2e log sweep and the stack lane's client-port stage | `grep -n 'def sweep_logs\|def token_needles' scripts/run_web_e2e.py`; `grep -n 'client port' scripts/run_stack_e2e.py \| head -3` |

## Decisions this CR takes

1. **Recording sits at the resource layer**, after the write succeeds and in its transaction: each guest-write
   resource resolves `clientAddress()` once and hands `(ip, port?, observedAt)` to a core `GuestWriteRecorder`, which
   writes nothing when the resolver yields no address and records the address alone when it yields no port (§ 10).
   The set of writes is angle 1 ∪ angle 2 above, and they must agree; a disagreement is a finding for phase 0.
2. **Columns:** `id`, `share_identity_id` (FK, cascades with the identity), `object_kind`, `object_id`, `field_group`
   (nullable), `client_ip` (text form), `client_port` (nullable), `observed_at TIMESTAMP(3)`, `expires_at`. The FK to
   the object deletes with it (§ 10 "the foreign keys delete with the identity or object") — where the object is an
   application's row, core cannot name it: the record keeps `object_kind`/`object_id` only, and the application's
   `NodeSubtreeEraser` and `ScopeEraser` delete its records through a core call, as they already return the guest
   identities they free.
3. **The retention setting** is an instance setting (admin channel, `instance.openapi.yaml`, a new tag), range checked
   1–30 by the contract; a row is written with `expires_at = observed_at + N`; the purge deletes where
   `expires_at <= now OR observed_at + N_now <= now` (§ 10, ADR 0092 § 2).
4. **Switched off**, nothing is recorded and the next purge deletes every live row; hold copies stay (§ 10).
5. **The disclosure value is served with the share**, so a guest page renders the N current at the write: one more
   field on the share's resolve answer (the switch's state and N), never a separate pre-auth read.

## Open questions and legal blockers

**Decided (Q13).** Owner 2026-10-17 ("Ja so übernehmen"): every recommendation in this section is taken, and implementation has the owner's go in the order of inbox #462. The legal blockers named here stay open; they are not owner decisions.

- **Legal blocker (ADR 0093 § Legal basis, Still open 4):** what the recorded port is worth when an operator's own NAT
  sits behind the edge, and whether the disclosure must say which edge an instance runs before a record is handed
  over — "needs counsel before the moderation CR". It blocks the **wording** of the first-layer sentence and of the
  operator section's record text (phase 5 here, and `cr-operator-section-of-the-terms`), not the record.
- **Re-checked at refine (ADR 0093 Still open 3):** the German IP-retention bill (BT-Drs. 21/6581): on 2026-10-17 a
  search of the Bundestag's pages found the first reading (24 June 2026) and the hearing (7 October 2026), and no
  final vote. Non-blocking; re-check before a release.
- **Q13 — The first-layer sentence on which pages?** § 10 says "every guest write page". **Recommendation:** wherever
  a guest can write — the contributor's naming step and upload control on a `READ_WRITE` link, the drop box's page —
  shown before the first write, not after; a `READ` guest sees nothing (nothing is recorded).

## Use-cases

Phase 0 writes the model enabler `en.moderation.guest-write-record` (rows, triggers, retention, switch, backup
exclusion, disclosure) and adds an alternate flow (the sentence, and what is recorded) to `uc.sharing.share-contributor`,
`uc.sharing.share-uploader` and `uc.depot.drop-box`; the switch's `serves` names those three (`validate_capabilities.py`
wants a built use-case).

## Phases

- [ ] **0 docs** — the enabler, the alternate flows, planned coverage; ADR 0094's status line (`built except the
  guest-write record`) stays until phase 4. · Done when: feature-format, coverage, doc-link, doc-citation, change guards
  green.
- [ ] **1 contracts** — the switch in `contracts/platform/capabilities.json` (EN+DE title, purpose, whenOff;
  `levels: ["instance"]`); the retention setting's operations; the share resolve's disclosure field. · Done when:
  `validate_capabilities.py` green; `npm run generate:api`; `./gradlew check` (the generated register; a new field on an
  existing response reddens its builder until phase 2: name it).
- [ ] **2 backend** — decisions 1–5; the purge as a `@Scheduled` with its interval in `VeyqConfig`; readers in
  `EveryStoredTimeHasAReaderTest`; the hold seam's contributor copies the record rows of a held object
  (`cr-report-and-hold` decision 4). · Done when: `./gradlew check` and `run_backend_races_on_mariadb.py` green.
  **Bites, from ADR 0094 § For the moderation CR, each with its control:** `198.51.100.66, 203.0.113.77` records
  `203.0.113.77` and `198.51.100.66` is in no record and no captured log record; a stop entry `203.0.113.77:51234`
  records `51234`; `198.51.100.66:4711, 203.0.113.77` records no port and `4711` is in no record and no log; under
  `source=header` a stop entry `203.0.113.77` with `X-Veyq-Client-Addr: 203.0.113.77:51234` records `51234`, under
  `none` no port; a member's write records nothing (control: the same write as a guest records one); the switch off
  → nothing recorded and the next purge empties the table while a hold copy stays; shortening N reaches an old row at
  the next purge, lengthening does not extend it.
- [ ] **3 admin-web + CLI** — the retention setting with its range and the sentence that a value above 7 needs a
  reason in the record of processing (§ 10); `veyq-admin` gets the setting; the switch appears in the capability panel
  by itself (the register). · Done when: `npm run check` in `admin-web/` green.
- [ ] **4 stack + backup** — the stack scenario ADR 0094 asks for: through the recipe's Traefik, the recorded port
  equals the browser's own socket port (`scripts/run_stack_e2e.py`, beside its `client port` stage; inbox #472 left it
  here); the backup exclusion per § Context (in the backup CR's merged `scripts/instance_backup.py`, with a selftest
  case in `scripts/instance_backup_selftest.py`: a capture of an instance with a record row restores with the table
  empty and its schema present). ADR 0094's status line drops "except the guest-write record". · Done when: the stack
  lane's run green with the new stage (Docker: the implementer's), `python3 scripts/instance_backup_selftest.py` green;
  or the phase records which half waited for the backup CR.
- [ ] **5 web** — the first-layer sentence (per Q13 and the legal blocker's wording), with the N from the resolve and
  the right to object (Art. 21) as a sentence of its own, linking the operator section's anchor; EN+DE. · Done when:
  `npm run check` green; a guest-mode test that the sentence shows N as served and is absent on a `READ` link.
- [ ] **6 docs + coverage** — `CLAUDE.md`'s invariant on what the server keeps (the record's address, port and time join
  the operator-visible metadata: ADR 0093 § Consequences names it); `security/what-the-server-sees.md` (a sixth
  concession, or the record under Sharing — measured: the five concessions are a table there); `run/configuration.md`
  or `run/capabilities.mdx` (the setting, its range, NTP on the host, the deletion concept for backups made otherwise,
  N + B); the security model's T&S section; coverage refs. · Done when: coverage, wiki-sources, the site check and
  `validate_doc_links.py` green.

## Definition of done

Every phase's Done-when; the enabler's criteria name their tests; every `scripts/validate_*.py` except
`validate_guard_gaps.py` green. The structural invariant "every guest write records" is promoted to a source-scan test
in the shape of `TreeRevisionBumpsTest`: every resource method that takes a contributor token (header or body) either
calls the recorder or is listed as exempt with a reason (a read) — bite: drop the call from one → red.

Implementation has the owner's go (2026-10-17), in the order of inbox #462; a legal blocker named above still blocks the part it names.

## Retrospective

_Filled at implementation._
