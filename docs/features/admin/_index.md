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
- ★ [Connect and check the instance's object store](use-cases/uc.admin.configure-object-store.md) — **built
  (backend + admin-web)** (ADR 0089): the store set and checked in the admin console instead of environment
  variables; a checked store gates tenant creation.
- ★ [Switch the instance's capabilities on and off](use-cases/uc.admin.switch-capabilities.md) — **built (backend +
  admin-web + web; browser pass run 2026-10-15, inbox #447)** (ADR 0088 decisions 5, 12, 13;
  built 2026-10-15): the register in the admin console and the CLI, each entry with its
  purpose and effect-when-off, stored in the database and acting without a restart; a device switch withholds its
  wraps at once, and the web client keeps the device and asks for the password and the Secret Key.
- ★ [Create a tenant & invite its owner](use-cases/uc.admin.create-tenant.md) — **built (backend + admin-web; the owner accepts in web)**
- [Delete a tenant](use-cases/uc.admin.delete-tenant.md) — **built** (cr-account-erasure;
  [ADR 0101](../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 10):
  an active or suspended tenant, offline at once and erased after the retention, its members mailed and their accounts
  kept; an ownerless tenant's two ways on — an owner invite to a member, or the deletion. The erasure ledger's entry is
  open.
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
- ★ [Set tenant terms (a new revision on each change)](use-cases/uc.admin.tenant-terms.md) — **built (web, cr-0114)** (ADR 0035; per-locale markdown + `terms_revision`; the member accept-gate consumes the revision)
- ★ [Configure per-tenant mail (SMTP + invite template)](use-cases/uc.admin.tenant-mail-settings.md) — **built (backend + web)** (cr-email-smtp-transport; the tenant owner's Mail section in the web settings, `TenantMailSettingsPanel`; ADR 0048/0049/0050; per-tenant SMTP, password sealed at rest, invite templates over a closed variable set)
- ★ [Switch capabilities off for a tenant](use-cases/uc.admin.tenant-switch-capabilities.md) — **built (backend +
  web)** (ADR 0088 decisions 2, 3, 12): the tenant level of the register, in the tenant settings beside terms and mail;
  a tenant only narrows what the Server Owner allows, and every gate refuses per tenant
- ★ [Set requirements on capabilities](use-cases/uc.admin.capability-requirements.md) — **built (backend + web +
  admin-web)** (ADR 0088 decision 8): a link needs a password, a link lives at most N days, being added
  to a group needs a yes, a device asks for the password again after N days; the strictest of both levels
- ★ [Configure the external services a browser calls](use-cases/uc.admin.configure-external-services.md) — **built
  (backend + web + admin-web)** (ADR 0088 decision 7, ADR 0089): the map tiles, the geocoder and the
  breached-password check as Server-Owner settings and register entries; the served policy follows them
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
  the operator's, the pre-auth `major`/`build` is the client's; they share the major
  ([ADR 0085](../../decisions/0085-one-major-moved-on-a-yearly-train.md), generated from `VERSION`) and the precise release never goes pre-auth)
- ★ [Install an instance and keep it on its release line](use-cases/uc.admin.install-and-upgrade-instance.md) —
  **built** (`scripts/veyqctl.py`, [ADR 0096](../../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md);
  coverage stays `planned`, a host tool being no coverage surface — `scripts/veyqctl_selftest.py` and the stack lane
  prove it). A release is an immutable GitHub Release whose assets are the recipe; `veyqctl install` and `upgrade`
  verify its images before anything is pulled, and an upgrade begins with the backup above by decision, not by advice —
  the schema migrates at boot with no gate and the migration set has no downgrade path
  ([ADR 0056](../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) §6). The commands, each also
  by hand, are the Run area's [Upgrade](../../../site/src/content/docs/run/upgrade.mdx). **Run live only against
  releases produced from the tree with images built on the host**: nothing is published, so no signature, pull or
  migrating upgrade of a published release has run
- ★ [Learn of a new release without looking for one](use-cases/uc.admin.learn-of-new-releases.md) — **built**
  ([ADR 0097](../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md); a daily check,
  console notices, and instance mail for the Server Owner only)
- ★ [Choose the operating mode and publish who runs the instance](use-cases/uc.admin.operator-details.md) —
  **planned** ([ADR 0093](../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 11 and 14, [ADR 0035](../../decisions/0035-server-terms-of-service.md) decisions 6–7; the mode a preset over
  the register's switches, the contact and the provider identification published, admin-web warning only in the
  *club, organisation or company* mode)
- [Read the instance's privacy notice](use-cases/uc.admin.privacy-notice.md) — **planned**
  (cr-instance-privacy-notice; [ADR 0035](../../decisions/0035-server-terms-of-service.md) decision 8, GDPR Art. 13):
  fixed text with the instance's values, public on every tenant host.
- Act on a reported public share — planned, under [Moderation](../moderation/_index.md): who moderates and the key a
  report is sealed to are [uc.moderation.appoint-moderators](../moderation/use-cases/uc.moderation.appoint-moderators.md)
  (the Server Owner's side, on admin-web)
