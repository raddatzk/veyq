---
id: cr-moderation-review-and-measures
title: A moderator reviews a case from the held snapshot and takes reversible measures by id
status: planned
satisfies: []            # phase 0 writes the use-cases; see § Use-cases
adrs: ["0093", "0099", "0066", "0065", "0026", "0088"]
invariants:
  - operator-blind E2EE (only what was reported opens; the review page holds the moderation key and no vault)
  - blocking deletes nothing (every measure is lifted by deleting its row)
  - a member's sign-in and a tenant stay the Server Owner's to block (ADR 0099 § 6)
---

## Goal

Build ADR 0093 §§ 3 and 6 with ADR 0099 § 6: the moderation surface lists the instance-wide **queue** (clear fields
only); a moderator opens a case, which fetches the **report share** against the key proof and shows it **in the share
viewer's moderation mode** on the reported tenant's host — the guest viewer over the held snapshot, read-only, with
the report's details; then decides: **dismiss** (the hold ends at once), or take a step within their reach — **block
the share** (the link answers as revoked), **block the content** (refused to everyone, members included, shown as
"Blocked by the operator"), **block a guest uploader** with the link it came through — or **propose** the Server
Owner's steps (block a member's sign-in, take the tenant offline), which the Server Owner takes in admin-web or
`veyq-admin`. Every measure can be lifted; every step is a log event. A moderator may **extend** a hold with a reason
and **bar a reporter** (ADR 0093 § 5). The statement of reasons and the objection are `cr-statement-of-reasons-and-objection`'s.

## Context & constraints

- Entry conditions: `cr-report-and-hold` **done** (cases, reports, the snapshot, the pins, the proof gate in use).
  Re-read its decisions 1–6 against the merged tree first.
- Decisions: [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 3 (review in moderation mode; deciding in the surface; admin-web keeps the Server Owner's part), § 4 (dismissal
  ends the hold; extension rules), § 5 (bar a reporter), § 6 (the steps, who takes each, "an address is evidence,
  never the ground of a block", a copy escapes); [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6 (the surface; what stays on the admin channel; the limits), "Decided with the owner" 2 and 3;
  [ADR 0066](../docs/decisions/0066-tenant-suspension.md) (taking a tenant offline, built).
- Not here: the statement, its deferral, the objection (`cr-statement-of-reasons-and-objection`); orders and the CSAM
  flow (`cr-authority-orders-and-art18-reports`), which reuse this CR's block-by-id operations.

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| A guest stop makes every guest path answer exactly as an unknown link (`404 share_not_found`) — the hook for "block the share" | `grep -n 'enum class Reason\|fun guestStops\|fun activeShare' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareGuestGate.kt` |
| A member's share list shows the stops as codes (`ShareSummary.guestStops`) — a block is one more code | `grep -n 'guestStops' contracts/api/sharing.openapi.yaml` |
| Every byte served goes through `presignGets`: the guest path and the member path (the hooks for "block the content") | `grep -rn 'presignGets(' backend/core/src/main backend/apps/*/src/main` |
| The listings that would carry a "blocked" marker (Memories' media lists, Depot's file lists, the node tree) | `grep -n 'fun listShareMedia\|fun listMedia' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaStore.kt`; `grep -rn 'fun list' backend/apps/depot/src/main/kotlin/me/raddatz/veyq/depot/share/ShareFilesHandler.kt`; second angle on the wire: `grep -rn 'NodeSummary:\|MediaItem:\|FileSummary:' contracts/api` |
| What "the uploader can delete it but not replace or restore it" meets: replace and restore paths | `grep -rn 'fun replace\|fun restore' backend/core/src/main backend/apps/*/src/main` |
| A guest identity's token is checked in one place | `grep -n 'fun findIdentity' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt` |
| Sign-in: the challenge and verify (the hook for "block a member's sign-in") | `grep -n 'fun challenge\|fun verify' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/LoginHandler.kt` |
| Tenant suspension exists as admin channel operation + CLI | `grep -n 'suspendTenant' contracts/api/tenants.openapi.yaml`; `grep -n "'tenant suspend'" admin-web/src/cli/commands.ts` |
| The share viewer reads its key from the fragment and renders the guest body | `grep -n 'fragmentKey\|resolvePublicShare\|:is="body"' web/src/views/ShareView.vue` |
| Guest and member see one level view with fewer actions (a placeholder is a row state, not a guest-only UI) | `grep -n 'LevelView' web/src/core/sharing/ShareTreePage.vue`; the rule: inbox #401, `grep -n '^| 401 ' docs/inbox.md` |
| The lightbox and the file view that must show the placeholder | `grep -rln 'MediaLightbox\|FileView\|ViewFile' web/src/features` |

## Decisions this CR takes (each with the line that settles it)

1. **Measures are rows**, `veyq_moderation_block` (kind: share, content, guest identity, member sign-in; target id;
   case id or order id; taken by; taken at; lifted at). "Blocking deletes nothing ... every step hangs on ids, never on a
   report or a key" (ADR 0093 § 6) — so `cr-authority-orders-and-art18-reports` reuses the same rows.
2. **Block the share** adds a `GuestStop.Reason` (`OPERATOR_BLOCKED`), so every guest path answers as revoked and the
   member's list says why; the owner can neither reactivate nor re-issue it (re-issue would mint the same content under
   a new id: refused while blocked).
3. **Block the content** is enforced at the two `presignGets` paths and at every listing that names the item, which
   marks it `blockedByOperator` instead of omitting it (the placeholder, ADR 0093 § 6 "members and guests see 'Blocked
   by the operator'"). The uploader's delete stays allowed; replace and restore are refused.
4. **Block a guest uploader** blocks the identity (its token is refused) **and** its link (decision 2), as § 6 says, so
   the guest cannot mint a new identity on it; the link's creator may issue a new one (a re-issue is allowed here,
   unlike decision 2, because the block names the identity, and the new link is a new decision of a member).
5. **Server Owner steps are proposals in the case** and operations on the admin channel: a member's sign-in block
   (new, `tenants.openapi.yaml` or `instance-moderation` — phase 2 picks the tag whose resource it lands in) and the
   existing suspension. admin-web shows open proposals; the CLI gets `moderation proposals` and `account block|unblock`.
6. **The review route** is `https://<tenant>/s/<report share id>#<sealed report reference>` as ADR 0093 § 3 states it,
   but served by moderation endpoints, not the guest ones: they answer with the same schemas the guest viewer reads
   (`_common` / sharing models), from the snapshot, behind the role and the proof. The viewer swaps its data source; it
   never calls a guest endpoint for a report (bite in phase 5).
7. **Hold ends set here:** dismissed → at once; a measure → six months after the decision, until
   `cr-statement-of-reasons-and-objection` moves the start to the statement's sending (ADR 0093 § 4: "where nobody can
   be told, from the decision"); a lifted measure → with the lift. An **extension** is a log event with a sealed reason
   and its own `expires_at`, at most six months per step (§ 4).

## Open questions (owner)

**Decided (Q9–Q10).** Owner 2026-10-17 ("Ja so übernehmen"): every recommendation in this section is taken, and implementation has the owner's go in the order of inbox #462. The legal blockers named here stay open; they are not owner decisions.

- **Q9 — What does blocked content answer?** **Recommendation:** listings keep the item as they serve it today and mark
  it (`blockedByOperator: true`); a byte request answers `451 blocked_by_operator` (RFC 7725's
  status for exactly this), distinct from `404`, so the client draws the placeholder rather than "missing". Alternative:
  `403` with the code. Members and guests get the same answer (inbox #401: a share view is the member view).
- **Q10 — Does a sign-in block end the member's live sessions?** ADR 0093 § 6 says "a member: sign-in on the instance".
  **Recommendation:** yes, every session family of the account ends with the block (a block that leaves a signed-in
  member reading is no block); lifting it lets them sign in again.

## Use-cases

Phase 0 writes `uc.moderation.review-a-case` (queue, open, moderation mode, dismiss, extend, bar a reporter) and
`uc.moderation.take-a-measure` (the four steps, proposals, lifting, what members and guests see), with planned
coverage. Existing use-cases whose behaviour changes and gain an alternate flow in phase 0: `uc.sharing.share-public-viewer`
(a blocked link), `uc.memory.gallery` and `uc.depot.view-file` (a blocked item), `uc.account.login` (a blocked account),
`uc.admin.suspend-tenant` (taken on a moderator's proposal).

## Phases

- [ ] **0 docs** — the two use-cases, the alternate flows above, planned coverage. · Done when: `validate_feature_format.py`,
  `validate_coverage.py`, `validate_doc_links.py`, `validate_changes.py` green.
- [ ] **1 contracts** — the queue, open a case (fetch the report share), the snapshot reads in the guest viewer's
  schemas, decide (dismiss, block, propose, lift, extend, bar a reporter) on the moderation spec; the Server-Owner
  operations (proposals, a member's sign-in block and its lift) on the admin channel; `blockedByOperator` on the
  listed models; `451` on byte reads per Q9; every operation classified. · Done when: `npm run generate:api` (web,
  admin-web), the contracts guards, `./gradlew check` (red predicted where an existing tag gains an operation: name the
  resource) and `npm run check` in `web/` run once to see which typed lines a new required field reddens.
- [ ] **2 backend: decisions and blocks** — decisions 1–5 and 7; every decision a log event (decided, lifted,
  hold extended or ended); the separation in `@RolesAllowed`: moderator operations never carry the admin marker, the
  Server Owner's always do (`AdminChannelPostureSweepTest` holds the latter). · Done when: `./gradlew check` and
  `run_backend_races_on_mariadb.py` green. **Bites, each with a positive control:** a blocked share → every guest
  endpoint `404` byte for byte as an unknown link (control: the same link before the block serves); blocked content →
  a member's download `451`, a guest's `451`, its sibling still served; a blocked guest identity's token → refused while
  another identity on a re-issued link writes; a moderator's session calling the member-block operation on the public
  port → `404` (the admin channel), the Server Owner on the admin port → done; a dismissal → the hold's pins are gone
  and the sweep reclaims a meanwhile-deleted object.
- [ ] **3 backend: review reads** — decision 6's endpoints over the snapshot, gated by role and proof; the log's
  "opened" event per fetch (ADR 0093 § 11). · Done when: `./gradlew check` green. **Bite:** delete the original
  share, the node and the scope after the report → the review read still answers the held rows (control: the live
  read is gone).
- [ ] **4 web: moderation surface** — the queue (category, time, tenant, state; urgent first), a case's details and
  log, the decision panel with each step's reach stated, proposals shown as such, extend with a reason, bar a reporter;
  opening a case navigates to the reported tenant's host. EN+DE. · Done when: `npm run check` green; a test per
  decision call that it carries a fresh proof over its own operation id.
- [ ] **5 web: moderation mode and placeholder** — `ShareView` in moderation mode: the surface's unlock (no vault
  after the grants are opened: ADR 0099 § 6, as the moderation surface of `uc.moderation.serve-as-moderator` already unlocks), the sealed report opened with the
  epoch's key, `K_report` unwrapped, the guest viewer over the moderation data source, read-only, the report's details
  beside it. The "Blocked by the operator" placeholder in the level rows and tiles, the lightbox and the file view, for
  members and guests alike, with no reason shown (ADR 0093 § 6); delete offered to the uploader, replace and restore
  not. · Done when: `npm run check` green; a test that moderation mode calls no guest endpoint and no scope client
  (`guardedClients` pattern; bite: route one read through `resolvePublicShare` → red); a browser pass: report a medium
  as a guest, review it as a moderator on the tenant host, block the content, see the placeholder as the member and the
  guest, lift it, see the medium again.
- [ ] **6 admin-web + CLI** — the proposals list per case, a member's sign-in block and lift, the link to the existing
  suspension; `veyq-admin moderation proposals`, `account block|unblock`. · Done when: `npm run check` in `admin-web/`
  green; the container build run or its omission stated.
- [ ] **7 docs + coverage** — `run/moderation.md` (the queue, the steps, who takes which, lifting; drop "ask the tenant's
  owner", ADR 0093 § Consequences), a Use page section on "Blocked by the operator" (EN+DE: what a member and a guest
  see and what they can still do), `security/what-the-server-sees.md` (blocks are clear metadata), the security model's
  T&S "built" line; coverage refs. · Done when: `validate_coverage.py`, `validate_wiki_sources.py`, `npm run check` in
  `site/` green.

## Definition of done

Every phase's Done-when; coverage for both use-cases (and the alternate flows of the existing ones) with `backend` and
`web` refs; every `scripts/validate_*.py` except `validate_guard_gaps.py` green. The invariant "every byte path honours
a content block" rests on the two `presignGets` callers: phase 2 adds a test that enumerates them by reflection or by a
source scan in the shape of `TreeRevisionBumpsTest` and is red on a new caller that is neither checked nor exempt
(bite: add an unchecked caller → red).

Implementation has the owner's go (2026-10-17), in the order of inbox #462; a legal blocker named above still blocks the part it names.

## Retrospective

_Filled at implementation._
