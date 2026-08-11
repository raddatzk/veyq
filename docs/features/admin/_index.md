# Feature: Administration & Operations

Server setup, tenant/member administration, branding & legal terms, backup/upgrade, and Trust & Safety
handling of reported shares.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share-boundary T&S),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md).
- **Note:** the procedural operator **runbook** (install, env, backup, upgrade) belongs in the repo's
  infrastructure docs, not here — this feature is the *product* surface of administration. Where a runbook has a
  use-case, the split is: the use-case owns the **goal, the flow's shape, the failure modes and the acceptance
  criteria**; [`infra/`](../../../infra/README.md) owns the **commands**. A procedure with no stated failure
  modes is the thing that gets executed wrong.

## Use-cases

- ★ [First-run server setup](use-cases/uc.admin.setup-server.md) — **refined**
- ★ [Create a tenant & invite its owner](use-cases/uc.admin.create-tenant.md) — **refined**
- Invite members, grant access & assign roles — planned
- Manage server users & roles — planned
- ★ [Set tenant terms (versioned)](use-cases/uc.admin.tenant-terms.md) — **built (web, cr-0114)** (ADR 0035; per-locale markdown + `terms_version`; the member accept-gate consumes the version)
- ★ [Configure per-tenant mail (SMTP + invite template)](use-cases/uc.admin.tenant-mail-settings.md) — **planned** (cr-email-smtp-transport; ADR 0048/0049/0050; per-tenant SMTP, password sealed at rest, invite templates over a closed variable set)
- Set tenant branding — planned
- ★ [Back up & restore the instance](use-cases/uc.admin.back-up-and-restore-instance.md) — **refined**
  ([ADR 0056](../../decisions/0056-instance-durable-state-and-cold-logical-backup.md); three legs not two, a cold
  capture that stops only the writer, and a store leg captured as objects rather than volumes)
- ★ [Know which release runs, and which a backup came from](use-cases/uc.admin.identify-instance-release.md) —
  **refined** ([ADR 0057](../../decisions/0057-release-identity-and-deployment-recipe.md); the release version is
  the operator's, the pre-auth `major`/`build` is the client's, and they are never merged)
- Upgrade the instance — planned (runbook). Begins with the backup above, by decision rather than by advice:
  the schema migrates at boot with no gate and the migration set has no downgrade path
  ([ADR 0056](../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) §6). Blocked on the
  release identity above: an upgrade whose rollback target cannot be named is advice, not a procedure
- Act on a reported public share — planned
