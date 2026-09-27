---
id: cr-tenant-storage-quota
title: The Server Owner sets each tenant's storage quota — every start into the tenant reserves against it
status: planned
satisfies: [uc.admin.tenant-storage-quota]
adrs: ["0073", "0065", "0013", "0067"]
invariants: [operator-blind E2EE, API-first, self-hostable by default]
---

## Goal

After `cr-share-link-storage-limit`, a link is bounded, but a tenant is not. Its members can fill the instance's
store, and the operator who pays for that store has no say in how it is shared between tenants.

After this CR, the Server Owner sets, changes or clears a **quota** per tenant through the admin channel, and sees
every tenant's usage beside it in `admin-web` and the CLI. There is no quota by default.

**Every** `start` into the tenant's scopes reserves against the quota under the tenant lock: a member's upload, a
cover, and a link upload. A link upload is checked against the quota and against its per-link limit
independently. A member whose upload does not fit sees the file fail with a no-space reason, and it is not
retried. The tenant owner sees the quota beside the usage.

This is the second half of inbox #155.

## Context & constraints

- **The rule:** [ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) §1–§3,
  §5 and §6, and [uc.admin.tenant-storage-quota](../docs/features/admin/use-cases/uc.admin.tenant-storage-quota.md)
  (main flow, A1–A6). Neither is restated here. Neighbours already edited in the refine: `uc.memory.upload` E5,
  `en.memory.media` E2a.
- **Order — hard:**
  - **After `cr-share-link-storage-limit`.** This CR extends its unit and its transactional `start`; it does not
    write a second SUM or a second lock.
  - **After `cr-admin-api-only-through-the-admin-container`.** The new Server-Owner operation needs its
    `x-veyq-channel: admin` marker, and that CR's sweep makes the marker mandatory. `admin-web` must already call
    the backend through the listener.
  - **Spot-check before the first edit:**
    - `grep -rn 'x-veyq-channel' contracts/api/tenants.openapi.yaml` shows the marker on the existing
      Server-Owner operations;
    - `grep -rn 'SUM\|sum(' backend/src/main/kotlin/com/veyq/app/features/media` names the one unit this CR
      extends.
- **What exists** (measured at refine, `main` @ `a494436`):
  - **Every stored object reaches its tenant** through `veyq_blob.scope_id` → `veyq_scope.tenant_id`. The scope
    index exists: `grep -n 'ix_scope_tenant\|ix_blob_scope' backend/src/main/resources/db/migration/*.sql`.
  - **Trash leaves the blob rows alone.** Node trash stamps only the nodes, and media trash stamps only the
    medium, so a SUM over `veyq_blob` already counts trash. Locator:
    `grep -n 'deleted_at\|deletedAt' backend/src/main/kotlin/com/veyq/app/features/content/NodesStore.kt backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt`.
  - **The tenant operations and their clients:**
    - `grep -n 'operationId' contracts/api/tenants.openapi.yaml`;
    - `ls admin-web/src/features/tenants`;
    - `grep -n "^  'tenant " admin-web/src/cli/commands.ts`;
    - `grep -n 'veyq-admin' infra/README.md`.
  - **What the member sees today.** The uploads tray retries **every** error once, and shows no reason:
    `grep -n 'MAX_ATTEMPTS\|file.error' web/src/features/memory/uploadQueue.ts` and
    `grep -n 'error' web/src/features/memory/components/UploadStatusMenu.vue` (empty at refine). The cover has
    its own error path: `grep -n "throw new Error(r" web/src/features/memory/media.ts`.
- **Out of scope:**
  - a quota per member (#179);
  - who writes the quota once a tenant can have a store of its own (#41; ADR 0073 §1);
  - a quota field in the create dialog. The quota is set on an existing tenant, which is one operation and one
    place.

## Decisions

Each names the line that settles it.

1. **Contract** (`tenants`, `media`):
   - **`setTenantStorageQuota`**, `PUT /api/tenants/{tenantId}/storage-quota`, body `{quotaBytes}`: int64,
     `minimum: 0`, nullable, where `null` clears it. It is gated to the Server Owner and marked
     `x-veyq-channel: admin`.
   - **`TenantSummary`** (from `listTenants`) gains `storageUsedBytes` and `storageQuotaBytes` (nullable).
   - **`getTenantStorage`** gains `quotaBytes` (nullable, read-only). No tenant-owner operation takes it, so "the
     tenant owner cannot raise it" is a property of the contract, not a check.
   - **`quota_exceeded`** joins the per-item error description of `startBlobUploads`. Locator:
     `grep -n 'budget_exceeded' contracts/api/media.openapi.yaml`.
2. **`veyq_tenant.storage_quota_bytes BIGINT NULL`**, where null means no quota (ADR 0073 §1).
3. **The unit's tenant sum becomes a check**, still the only SUM. `StorageQuota.tenantUsage` already exists — the
   first CR built it for the owner's `getTenantStorage` read, and it is untested as a *check*: the declared size
   of every blob, plus every open session, in the scopes whose `tenant_id` is the tenant, regardless of any
   `deleted_at`. `listTenants` asks the unit once for all tenants, grouped, never with one query per tenant.
4. **Every `start` goes through the unit's locked transaction** — the member path too, which
   `cr-share-link-storage-limit` left unlocked: `MediaHandler.startForLink` is the locked shape (lock the tenant row,
   check each item, ask the unit, insert or refuse whole, commit, then presign), and the member core
   `startForScope` is the unlocked one it must join. Within it:
   - the quota (if set) is checked for every start;
   - the per-link limit is checked for a link start;
   - each check is independent, and either one refuses the whole request.

   Presigning stays after the commit.
5. **Web:**
   - `quota_exceeded` is a hard failure in the uploads queue: no automatic retry. Other errors keep their one
     retry. This is live for link visitors too: the first CR changed only the tile, so a link upload refused
     `quota_exceeded` is still retried once, silently, before its tile shows the link as full.
   - The tray shows the failed file's reason when it is `quota_exceeded`: no space left, and trash counts until it
     is emptied. A cover refused the same way says the same.
   - The storage panel from `cr-share-link-storage-limit` shows the quota beside the usage when one is set.
   - Wording EN + DE, the DE neutral-first.
6. **admin-web:**
   - The tenant detail panel shows usage and quota, and sets or clears the quota (GiB in, bytes out).
   - The list shows usage and quota per tenant.
   - The CLI gains `tenant quota <id> <GiB|none>`, and `tenant list` prints both figures.

## Phases

- [ ] 1 contracts — decision 1. · Done when: `npm run generate:api` + `npm run check` green in `web/` and
      `admin-web/`, and every `scripts/validate_*.py` green. `./gradlew check` is red until phase 2: the existing
      `TenantsApi` gains a method.
- [ ] 2 backend. In this order:
      1. **The migration alone** (decision 2). Nothing reads the column yet, so this step ends green.
      2. **Then the live defect:** with a quota written on the row, a member `start` over it is admitted today. It
         must refuse every item `quota_exceeded` and leave no session. Record the red on the assertion. Control: a
         start under the quota is admitted.
      3. The unit's tenant sum, and every start through the locked transaction (decisions 3–4).
      4. `setTenantStorageQuota` and the two read fields.

      **Injection bites.** Each term of the tenant sum gets a case that goes red when its lever drops the term,
      with a positive control each:
      - trashed media counts;
      - a trashed node counts;
      - a scope in its deletion grace counts;
      - a cover counts;
      - an open session counts;
      - after a purge the usage drops (the tombstone is not counted).

      **Independence:**
      - a link start within its link limit into a tenant over its quota → refused;
      - a link start over its link limit into a tenant with room → refused.

      The lever: evaluate only one of the two checks.

      **Parallel:** concurrent member starts with room for exactly one → exactly one admitted. The lever: drop
      the lock from the member path. The same H2 caveat applies as in the first CR.

      **Channel and gate:**
      - `setTenantStorageQuota` on the public port → `404`; on the listener → `200`. The admin-channel sweep
        already holds the marker.
      - A tenant owner calling it on the listener → refused by the Server-Owner gate.
      - Clearing the quota admits the next start.

      · Done when: `./gradlew check` green, every red above recorded in this file before it goes.
- [ ] 3 web — decision 5. Tests in `uploadQueue.test.ts` and the tray's tests:
      - `quota_exceeded` → `failed` after one attempt, with its reason shown.
      - **Control:** a transient error is still retried once. The lever: route `quota_exceeded` through the retry
        path.
      - The storage panel renders the quota when set, and omits it when null.

      · Done when: `npm run check` (web) green.
- [ ] 4 admin-web — decision 6. Tests beside `TenantDetailPanel.test.ts` and `cli.test.ts`: set, clear,
      GiB → bytes, and the list columns. · Done when: `npm run check` (admin-web) green.
- [ ] 5 docs + coverage
      - `uc.admin.tenant-storage-quota.yaml`: drop `status: planned`; give every criterion its refs on `backend`,
        `web` and `admin-web`; drop the gaps.
      - The `admin/_index.md` entry moves to **built**.
      - `infra/README.md`'s `veyq-admin` example block gains the quota command, if it lists commands at all
        (`grep -n 'veyq-admin' infra/README.md`).
      - Read ADR 0073 and both use-cases end to end against what was built.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The phase-2 live defect was seen red on its assertion; each injection bite was seen red with its named lever.
- **Still one SUM:** `grep -rniE 'sum\(|sumOf|\bSUM\b' backend/src/main/kotlin` lists the unit, plus at most
  `verifyBlob`'s per-blob check. `listTenants` asks the unit for its figures; it does not sum on its own.
- `docs/coverage/` updated, with `python3 scripts/validate_coverage.py` green.
- Harvest before deleting this file: the #155 row → DONE with its commits; any trap met goes to a comment at its
  line.

**Not proven:** the MariaDB lock, as in the first CR. And that a SUM stays cheap on a tenant with very many
blobs: ADR 0073 names a counter only as the fallback against a *measured* cost, so nothing here builds one.

## Retrospective
