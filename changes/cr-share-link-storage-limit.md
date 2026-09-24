---
id: cr-share-link-storage-limit
title: Every upload link stores at most the tenant's per-link limit — usage derived, reserved at start under a tenant lock
status: planned
satisfies: [uc.sharing.share-link-storage-limit]
adrs: ["0073", "0013", "0030", "0031", "0042"]
invariants: [operator-blind E2EE, API-first, self-hostable by default]
---

## Goal

Nothing bounds how much an upload link stores, so an anonymous contributor or uploader link that travels further
than intended can fill the server's disk. The only size check is per blob at `start`.

After this CR:
- every CONTRIBUTOR and UPLOADER link stores at most one per-link limit, which the tenant owner sets in the tenant
  settings (10 GiB until changed);
- a link's usage is **derived** from the rows that already exist, and a link `start` **reserves** its declared size
  under a lock on the tenant row;
- a request that does not fit is refused **whole** with the new per-item code `quota_exceeded`;
- the visitor reads that the link takes no more uploads right now, and is offered no retry.

This is the first half of inbox #155. The tenant's own quota, set by the Server Owner, is `cr-tenant-storage-quota`.
It extends the unit and the lock this CR builds.

## Context & constraints

- **The rule:** [ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) §2–§6
  and [uc.sharing.share-link-storage-limit](../docs/features/sharing/use-cases/uc.sharing.share-link-storage-limit.md)
  (main flow, A1–A6, E1). Neither is restated here. Neighbours already edited in the refine:
  - share-contributor E2 and share-uploader E3;
  - `en.memory.media` E2a;
  - ADR 0030 §6 and ADR 0031 §6;
  - ADR 0013 §3.
- **Order — hard:**
  - **After `cr-upload-frame-size-contract` (done).** The presigned `PUT` now signs each chunk's exact
    `Content-Length` and `complete` checks every chunk at exactly that length, so the declared size is the stored
    size — a chunk can no longer be overwritten larger after `complete` has checked it, which is what makes a
    quota on the declared size hold.
  - **After `cr-upload-windowed-presign`.** Today an open contributor session names nobody, so it cannot be
    charged to its link. That CR records the opener as a nullable **share-identity** reference; the refine
    constrained it to that shape for this CR.
  - **Spot-check before the first edit** — the row this CR leans on hardest:
    `grep -rn 'share_identity' backend/src/main/resources/db/migration/*.sql | grep -i session` must show the
    session's opener column. If it is an opaque key instead, stop and bring it back.
- **Order — soft:**
  - **After `cr-scope-access-gate`.** That CR lands alone and touches the same `start` handlers. It also brings the
    query-only `TenantEntity` into `shared/tenant` (the half it takes from #173), which is the lock's handle. The
    media feature must not import `features/admin/tenant`. The quota check runs after its gate, never instead of
    it.
  - **After `cr-request-bounds-enforced`** (done), so the new schemas land under its guard, `scripts/validate_request_bounds.py`.
- **What exists** (measured at refine, `main` @ `a494436`; do not rebuild it — spot-check one row):
  - **The start sites.** One `startForScope` serves both paths, and the contributor path reaches it after its
    gate chain:
    `grep -n 'fun start\b\|fun startForScope\|fun startOne' backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`
    and `grep -rn 'startForScope' backend/src/main/kotlin`.
  - **No transaction spans a `start`.** Each `createSession` is its own. Neither media nor sharing locks a row
    anywhere: `grep -n 'Transactional' backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`
    returns only a comment.
  - **The lock precedent:** `grep -rn 'PESSIMISTIC_WRITE' backend/src/main/kotlin` (`SessionService`,
    `LoginHandler`).
  - **Link attribution.** `veyq_media.share_identity_id` names the contributor, and a derivative reaches its
    blob through `veyq_media_derivative`. The column has **no index**:
    `grep -n 'share_identity_id' backend/src/main/resources/db/migration/*.sql` and
    `grep -n 'ON veyq_media\b\|ON veyq_media (' backend/src/main/resources/db/migration/*.sql`.
  - **The tenant-owner settings pattern** to mirror is mail settings: path, `403 not_tenant_owner`, `404` for a
    non-member. Locators: `grep -n 'mail-settings' contracts/api/tenants.openapi.yaml` and
    `grep -rn 'TenantMailSettingsPanel' web/src/views/SettingsHubView.vue`.
  - **Where a refused contributor upload surfaces today.** It is thrown as its error code and shown as the generic
    failed tile with a retry. Locators: `grep -n 'throw new Error(r' web/src/features/memory/share.ts` and
    `grep -n 'uploadTileFailed\|retry' web/src/features/memory/components/OptimisticTile.vue web/src/views/ShareView.vue`.
- **Out of scope:**
  - the tenant quota, the member-side message, and the admin-web surface (`cr-tenant-storage-quota`);
  - a per-link override chosen at creation (ADR 0073 §4);
  - a per-member quota (#179);
  - showing a visitor the link's remaining room, or its usage in the share list.

## Decisions

Each names the line that settles it.

1. **Contract** (`sharing`, `tenants`):
   - `quota_exceeded` joins the per-item error description of `startShareBlobUploads`. The member `start` gains
     it in `cr-tenant-storage-quota`, when a member can first hit it. Locator:
     `grep -n 'budget_exceeded' contracts/api/sharing.openapi.yaml`.
   - **The tenant owner's read and write**, on `/api/tenants/{tenantId}/storage`, beside `mail-settings`:
     - `getTenantStorage` returns `{usedBytes, shareLinkLimitBytes}`;
     - `updateTenantStorage` takes `{shareLinkLimitBytes}`, int64, `minimum: 0`, required;
     - both are owner-gated like mail settings.
   - `usedBytes` passes § *before adding a read field* (`changes/README.md`): the client cannot derive it, and the
     server knows it exactly.
   - Both land on the existing `tenants` tag, so the contracts phase cannot end with `./gradlew check` green.
2. **The limit lives on the tenant row:** `veyq_tenant.share_link_limit_bytes BIGINT NOT NULL`. The migration
   default is 10 GiB, so every existing tenant carries it (ADR 0073 §4). The same migration indexes
   `veyq_media.share_identity_id`.
3. **One unit answers "does this request fit".** It lives in the media feature, since media owns blobs and
   sessions. It is the only place the SUM is written.
   - For a link it sums:
     - the `byte_size` of every blob referenced by a medium whose `share_identity_id` is one of the link's
       identities, as the medium's own blob or as a derivative's;
     - the `byte_size` of every open session whose opener is one of those identities.
   - It does **not** filter on `deleted_at` — trash counts (ADR 0073 §2).
   - It may join `veyq_share_identity` in its query, or take the identity ids from `SharingHandler`. The
     implementer chooses; the DoD holds it to one SUM.
4. **A link `start` runs as one transaction:**
   1. lock the tenant row (`PESSIMISTIC_WRITE`, through `shared/tenant`), where the tenant is the scope's
      `tenant_id`;
   2. validate each item as today;
   3. sum;
   4. if the admissible items' total plus the usage exceeds the limit, answer every admissible item
      `quota_exceeded` and insert **no** session; otherwise insert every session;
   5. commit, and **presign after the commit**, so the lock is not held across up to 256 × `W` signatures.

   Member starts do not take the lock yet; `cr-tenant-storage-quota` routes them through the same unit.
5. **Web:**
   - **The tile.** `quota_exceeded` on a contributor or uploader tile is no longer a generic failure. The tile
     says the link takes no more uploads right now, and shows **no** retry control. Wording EN + DE, the DE
     neutral-first ([cc.inclusive-language](../docs/crosscutting/cc.inclusive-language.md)).
   - **The settings panel.** The tenant settings gain an owner-gated **storage** panel. It shows usage in a human
     unit and edits the per-link limit, entered in GiB and sent in bytes. Its shape is the mail-settings panel's
     ([ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md) § Reuse first), including the read-only
     notice on a `403`.

## Phases

- [ ] 1 contracts — decision 1. · Done when: `npm run generate:api` + `npm run check` (web) green, and every
      `scripts/validate_*.py` green (including `validate_request_bounds.py` on the new schemas). `./gradlew check`
      is red until phase 2: the existing `TenantsApi` gains two methods.
- [ ] 2 backend. In this order:
      1. **The migration alone** (decision 2): the column with its default, and the index. Nothing reads them yet,
         so this step ends green.
      2. **Then the live defect** (guard → red → fix → green): with a small limit written on the row, a link
         `start` whose declared size exceeds it → every item `quota_exceeded`, and **no** `veyq_upload_session`
         row for the scope. Today it is admitted. Record the red on the assertion, not on a setup line.
         Control: a request that fits is admitted.
      3. The unit and the transactional link `start` (decisions 3–4).
      4. `getTenantStorage` / `updateTenantStorage`, owner-gated, in the mail-settings handler's shape.

      **Injection bites.** Each term of the definition gets a case that goes red when its lever drops the term
      from the SUM (snapshot-and-restore). Each case has a positive control on the same predicate:
      - a trashed contribution still counts;
      - a derivative counts;
      - an open session counts;
      - a contributor's own delete frees at once.

      **Over-reach controls** (the lever: widen the SUM to the scope):
      - a second link on the same tenant, with its own room, is admitted while the first is full;
      - a member's upload into the same memory does not count against the link.

      **Whole request:** three items where two would fit → all three refused, zero sessions. Lever: admit per item.

      **Parallel:** N concurrent link starts with room for exactly one → exactly one admitted. Lever: drop the
      lock. If H2 cannot make the lever bite, that is a design question (`changes/README.md` § *a named lever
      that moves nothing*): bring it back, do not drop the test.

      **Owner gate:**
      - the owner reads usage and writes the limit;
      - a non-owner member gets `403 not_tenant_owner`, a non-member `404`;
      - a lowered limit deletes nothing and refuses the next `start`;
      - `0` refuses every link `start`.

      · Done when: `./gradlew check` green, and every red above recorded in this file before it goes.
- [ ] 3 web — decision 5. Tests:
      - **Tile:** `quota_exceeded` renders the link-full text with no retry control. Control: any other error still
        renders the generic failed tile **with** retry.
      - **Panel:** the owner edits and saves the limit, with GiB → bytes asserted on the request; a `403` renders
        read-only.

      · Done when: `npm run check` (web) green.
- [ ] 4 docs + coverage
      - `uc.sharing.share-link-storage-limit.yaml`: drop `status: planned`, give every criterion its refs, drop
        both gaps.
      - The `sharing/_index.md` entry moves to **built**.
      - Read ADR 0073 §2–§6 and the use-case end to end against what was built. A disagreement is a question back
        to the owner, not a silent edit.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The phase-2 live defect was seen red on its assertion; each injection bite was seen red with its named lever.
- **One SUM.** This is a one-shot proof, not a guard: the behavioural bites pin every term of the definition, and
  a second copy only drifts where it is the one called. The proof:
  `grep -rniE 'sum\(|sumOf|\bSUM\b' backend/src/main/kotlin` lists the unit, plus at most `verifyBlob`'s
  per-blob chunk check in `MediaHandler`. At refine that check was the only hit, and it is not a quota.
  (Refine also tried `grep -rln 'byte_size\|byteSize' … | xargs grep -ln 'sum\|SUM'`: it over-includes, because
  "sum" matches words like "summary", so it proves nothing.)
- `docs/coverage/` updated, with `python3 scripts/validate_coverage.py` green.
- Harvest before deleting this file: the #155 row names what is built and what `cr-tenant-storage-quota` still
  owes; any trap met goes to a comment at its line.

**Not proven:** that the lock holds on MariaDB exactly as on H2. The test runs on H2. `SELECT … FOR UPDATE` is in
both dialects' subset, and a live check against the dev MariaDB is a manual step, not a claim.

## Retrospective
