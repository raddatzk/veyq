# ADR 0097 — An instance looks for new releases by default and mails its Server Owner

- Status: Proposed 2026-10-16 (owner's direction; the open questions below need answers before a CR)
- Date: 2026-10-16

## Context

A Server Owner learns about a new release today only by looking: nothing in the instance compares its own release
with the published ones. The running release is known — the image carries `VEYQ_VERSION`, and the Server Owner reads
it signed in ([uc.admin.identify-instance-release](../features/admin/use-cases/uc.admin.identify-instance-release.md)).
A forgotten instance therefore stays on an old release with known fixes unapplied. The host-side management tool of
[ADR 0096](0096-a-host-side-tool-manages-the-instance-the-recipe-comes-from-the-release-commit.md) can *answer* "is
there a newer release?" (`check`), but only when someone runs it.

Mail exists only per tenant: [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) §4 deliberately has **no
server-level mail settings and no instance-wide secret**, so each tenant sends from its own identity
(`TenantMailSender.settings(domain)` resolves per send). So there is no channel today by which the instance can
reach its Server Owner. The Server Owner's account has an email address, entered at first-run setup
([uc.admin.setup-server](../features/admin/use-cases/uc.admin.setup-server.md), step 2).

## Decision

**1. The backend looks for new releases, on by default, and the Server Owner can switch it off.** Once a day it lists
the app image's tags from its registry, keeps releases only (`<major>.<minor>`, never `-SNAPSHOT` or `sha-…`), and
compares them with its own `VEYQ_VERSION` **locally**. The request is anonymous and names no version, so the registry
learns that this address polls veyq — not which release it runs. The switch is an instance setting in admin-web
([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)); the registry is
configurable, so a mirror serves an instance that must not reach GitHub. A failed check is logged and retried the
next day; it never blocks anything.

**2. What it finds is a hint, never an action.** admin-web shows "release X is available" (and says when X is a new
major, which removes things — [ADR 0085](0085-one-major-moved-on-a-yearly-train.md)). Nothing is pulled: the upgrade
stays the management tool's, which verifies signatures. A forged tag list can at worst produce a false hint.

**3. The instance gets its own mail settings, for the Server Owner only.** A narrow exception to ADR 0048 §4:
admin-web holds one instance-level SMTP configuration (the same fields as a tenant's), its password sealed under
the config-secret key exactly as a tenant's is ([ADR 0050](0050-operator-config-secret-encryption.md)). It sends
**only** to the Server Owner's account address and **only** instance notices. It is never a default or fallback for
a tenant's mail, so ADR 0048's reason — each tenant sends from its own identity — still holds.

**4. A new release is mailed once.** When the check first sees a release newer than the running one, and instance
mail is configured, the Server Owner gets one mail: the version, whether it is a new major, and the link to the
Run area's upgrade page. No mail is repeated for the same release. Without instance mail, admin-web's hint is the
only notice. The mail carries no user data ([ADR 0049](0049-email-delivery-boundary.md)).

## Consequences

- **The instance makes an outbound call by default.** It is the first one that is not to a service the Server Owner
  configured (store, SMTP). *Self-hostable by default* holds because it can be switched off and pointed at a mirror,
  and nothing depends on it; the owner chose default-on over opt-in (2026-10-16) so that forgotten instances hear of
  fixes.
- **A second server-held mail secret.** ADR 0048's "no instance-wide secret" becomes "no instance-wide secret for
  tenant mail"; ADR 0048 §4 is edited in place to say so.
- **Offline runs switch it off.** The end-to-end lanes ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md))
  boot with no internet; the check must stay quiet there, and a run must not count its failure as a defect.
- **Rejected — opt-in.** No outbound call unless asked, but the instances that most need the notice are the ones
  nobody configures.
- **Rejected — the host tool only (`check`).** No outbound call from the instance at all, but nobody is told unless
  they run it.
- **Rejected — reuse a tenant's SMTP for the Server Owner.** There may be no tenant, and it would send instance
  notices under a tenant's identity.

### Open questions — answer before the CR

1. **Should instance mail also carry the first tenant owner's invite**, the one copy-link ADR 0048 §5 keeps because
   there was no server-level mailer? Possible now; not decided here.
2. **Is the Server Owner's address confirmed before mail goes to it?** Setup collects it but does not confirm it.
3. **What else counts as an instance notice** (a failed backup the tool reports, a certificate about to expire) —
   or is this channel for releases only.
