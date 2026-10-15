# Feature: Administration & Operations

Server setup, tenant/member administration, branding & legal terms, backup/upgrade, and Trust & Safety
handling of reported shares.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share-boundary T&S),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md).
- **Note:** the procedural operator **runbook** (install, env, backup, upgrade) belongs in the repo's
  infrastructure docs, not here — this feature is the *product* surface of administration. Where a runbook has a
  use-case, the split is: the use-case owns the **goal, the flow's shape, the failure modes and the acceptance
  criteria**; the docs site's [Run area](../../../site/src/content/docs/run/index.md) owns the **commands**. A procedure with no stated failure
  modes is the thing that gets executed wrong.

## Use-cases

- ★ [First-run server setup](use-cases/uc.admin.setup-server.md) — **built (backend + admin-web)**
- ★ [Administer only through the admin container](use-cases/uc.admin.admin-channel.md) — **built**
  ([ADR 0065](../../decisions/0065-admin-ui-cli-separate-service.md); the backend answers every Server-Owner
  operation, first-run setup included, only on an internal listener the admin container reaches — classified by
  the socket, never by a header — and 404s it on the public origin)
- ★ [Create a tenant & invite its owner](use-cases/uc.admin.create-tenant.md) — **built (backend + admin-web; the owner accepts in web)**
- ★ [Suspend & reactivate a tenant](use-cases/uc.admin.suspend-tenant.md) — **built**: both transitions,
  the `Host` filter on the tenant's own domain, step 4's routing withdrawal, which
  [tenant-reachability](use-cases/uc.admin.tenant-reachability.md) owns and this transition triggers, and the
  enforcement itself — the per-operation gate that holds on every domain, for shares and jobs too
  ([uc.spaces.scope-access](../spaces/use-cases/uc.spaces.scope-access.md), `ScopeAccess`). ([ADR 0066](../../decisions/0066-tenant-suspension.md);
  an immediate, per-operation lockout and deliberately **no** session revoke — sessions are account-global, so a
  revoke would sign a multi-tenant member out of unaffected tenants too; never a routing-only toggle, never a
  content-key operation)
- ★ [Reach a tenant at its own domain](use-cases/uc.admin.tenant-reachability.md) — **built**
  ([ADR 0063](../../decisions/0063-database-driven-tenant-ingress.md); the reverse proxy's routing is
  regenerated **in full** from the `tenant` table on every change that decides whether a domain routes — never
  patched, so a wiped volume or a hand-edited file repairs itself — and a presigned blob URL is signed for the
  domain the request arrived on, resolved through that same table and never from the `Host` header. The
  Kubernetes sink ([ADR 0064](../../decisions/0064-kubernetes-helm-deployment-target.md)) is a second sink for
  the same projection — **built**, deployed by `charts/veyq`, with a certificate per tenant domain obtained
  through the operator's issuer — or the chart's ACME opt-in — by a named TLS mode)
- Invite members, grant access & assign roles — invites and grants are **built** as
  [uc.spaces.add-member](../spaces/use-cases/uc.spaces.add-member.md) and
  [uc.spaces.manage-member-invites](../spaces/use-cases/uc.spaces.manage-member-invites.md); assigning a role in a
  tenant is **planned** (roles exist only inside a group, [uc.spaces.manage-group-members](../spaces/use-cases/uc.spaces.manage-group-members.md))
- Manage server users & roles — planned
- ★ [Set tenant terms (versioned)](use-cases/uc.admin.tenant-terms.md) — **built (web, cr-0114)** (ADR 0035; per-locale markdown + `terms_version`; the member accept-gate consumes the version)
- ★ [Configure per-tenant mail (SMTP + invite template)](use-cases/uc.admin.tenant-mail-settings.md) — **built (backend + web)** (cr-email-smtp-transport; the tenant owner's Mail section in the web settings, `TenantMailSettingsPanel`; ADR 0048/0049/0050; per-tenant SMTP, password sealed at rest, invite templates over a closed variable set)
- ★ [A tenant's storage quota](use-cases/uc.admin.tenant-storage-quota.md) — **built** (backend, web, admin-web)
  ([ADR 0073](../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md); the ceiling belongs to
  whoever pays for the store — for the instance's store the Server Owner, on the admin channel; none by default;
  everything the tenant holds in the store counts, trash until purged, and every `start` reserves its size under a
  lock on the tenant row)
- Set tenant branding — planned
- ★ [Back up & restore the instance](use-cases/uc.admin.back-up-and-restore-instance.md) — **built** (the operator tool
  `scripts/instance_backup.py`; capture and restore onto an empty instance are rehearsed in the web e2e run)
  ([ADR 0056](../../decisions/0056-instance-durable-state-and-cold-logical-backup.md); three legs not two, a cold
  capture that stops only the writer, and a store leg captured as objects rather than volumes)
- ★ [Know which release runs, and which a backup came from](use-cases/uc.admin.identify-instance-release.md) —
  **built (backend + admin-web, cr-release-identity)** ([ADR 0057](../../decisions/0057-release-identity-and-deployment-recipe.md); the release version is
  the operator's, the pre-auth `major`/`build` is the client's; they are to share the major
  ([ADR 0085](../../decisions/0085-one-major-moved-on-a-yearly-train.md), not built) and the precise release never goes pre-auth)
- Upgrade the instance — the runbook is **written** ([Upgrade](../../../site/src/content/docs/run/upgrade.mdx), the docs site's Run area) and no longer
  blocked: the release identity above is what let its rollback target be named, and an upgrade whose rollback
  target cannot be named is advice rather than a procedure. It begins with the backup above by decision, not by
  advice — the schema migrates at boot with no gate and the migration set has no downgrade path
  ([ADR 0056](../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) §6). **Only its capture
  step and the upgrade proof's data half have ever executed** (the proof moves a backup from a predecessor's jar to
  this tree's, `run_web_e2e.py --upgrade`); no image has been published, so there are not yet two releases to move between, and
  the runbook says so in its own first paragraph rather than reading as a proven path
- Act on a reported public share — planned
