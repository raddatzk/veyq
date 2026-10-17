# ADR 0097 — An instance looks for new releases by default and mails its Server Owner

- Status: Accepted 2026-10-16; built: decisions 1–6 for release notices to the Server Owner (`core/admin/instance/`,
  `core/mail/`, admin-web's Server → Release and Server → Mail). The GitHub source reads each candidate major's
  `manifest.json` release asset, which exists once `veyqctl`'s publish uploads it; until then a new major on GitHub
  is a failed check, a mirror is not. Not built: moderation notices to the moderators, which decisions 3, 5 and 6
  carry for [ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 7.
- Date: 2026-10-16

## Context

A Server Owner learns about a new release today only by looking: nothing in the instance compares its own release
with the published ones. The running release is known — the image carries `VEYQ_VERSION`, and the Server Owner reads
it signed in ([uc.admin.identify-instance-release](../features/admin/use-cases/uc.admin.identify-instance-release.md)).
[ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) lets the operator pin a release line
(`VEYQ_RELEASE=1`, or an exact release) and upgrades only by hand, so an instance on a pinned line hears of a new
major from nowhere unless the instance tells its owner.

Majors are not all alike ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)): a **planned** major comes on the
yearly train (1 May), and an **unscheduled** one only when a security fix cannot be made without a removal. A
store-shipped client speaks the current major and the two before it (decision 5), so a planned major starts a clock
— two years until the running major leaves that window — while an unscheduled one means "now".

Mail exists only per tenant: [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) §4 deliberately has **no
server-level mail settings and no instance-wide secret**, so each tenant sends from its own identity. There is no
channel by which the instance can reach its Server Owner. The Server Owner's account has an email address, entered
at first-run setup ([uc.admin.setup-server](../features/admin/use-cases/uc.admin.setup-server.md), main flow 2).

## Decision

**1. The backend looks for new releases, on by default, and the Server Owner can switch it off.** Once a day it reads
the published releases from the same source `veyqctl` uses (ADR 0096 decision 3: the immutable GitHub Releases, or
the operator's mirror) and compares them with its own `VEYQ_VERSION` **locally**. The request is anonymous and names
no version, so the source learns that this address polls veyq — not which release it runs. The switch and the source
are instance settings in admin-web ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)).
A failed check is logged and retried the next day; it never blocks anything.

**2. What it finds is a notice, never an action.** Nothing is pulled; upgrading stays `veyqctl`'s, by hand. A forged
release list can at worst produce a false notice. admin-web shows, and the mail of decision 4 sends:

| Found | Notice |
|---|---|
| a newer release within the running major | "release X is available" |
| a **planned** new major | "major N is out", with the date the running major leaves the client window |
| an **unscheduled** new major | marked **urgent**: a security fix that needed a removal; upgrade now |
| the running major's window end, 90 and 30 days ahead | a reminder with the date |

The release manifest (ADR 0096 decision 3) marks an unscheduled major; the window end follows ADR 0085 decision 5
from the release dates the check already reads.

**3. The instance gets its own mail settings, for the Server Owner and the moderators only.** A narrow exception to
ADR 0048 §4: admin-web holds one instance-level SMTP configuration (the same fields as a tenant's), its password
sealed under the config-secret key exactly as a tenant's is ([ADR 0050](0050-operator-config-secret-encryption.md)).
It sends **only** instance notices, and **only** to the Server Owner's account address and — for moderation notices
alone — to the account address of each accepted moderator and the instance contact's notification address
([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 7,
[ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) §§ 2 and 11). It is
never a default or fallback for a tenant's mail, so ADR 0048's reason — each tenant sends from its own identity —
still holds.

**4. Each notice is mailed once, and a dated one carries its date as a calendar entry.** When the check first finds a
row of decision 2, and instance mail is configured, the Server Owner gets one mail: what was found, the link to the
Run area's upgrade page, and — for a planned major and the reminders — the window's end date written out **and** as
an attached `.ics` event, so a mail client offers it as a reminder rather than having to guess it from the text. No
notice is mailed twice. Without instance mail, admin-web shows them and that is all. The mail carries no user data
([ADR 0049](0049-email-delivery-boundary.md)).

**5. The address is confirmed by the first mail.** Saving the instance mail settings sends a test mail to the Server
Owner's address carrying a short one-time code, which the Server Owner types into the admin console; notices are
mailed only after that. One mail proves both that the SMTP settings work and that setup's address — collected there,
never confirmed — reaches its owner. A changed address or changed settings ask again. A code and not a link, because
the admin console is reachable on localhost only ([ADR 0065](0065-admin-ui-cli-separate-service.md)) and the instance
has no address of its own: a link opened on another device would reach nothing. **A moderator's address is confirmed
the same way, where they can reach it:** accepting the appointment sends the code to their account address, and they
type it into web/'s moderation surface (ADR 0099 § 7); moderation notices reach that address only once it is
confirmed, and a changed address asks again.

**6. The channel is built for instance notices, and carries release notices and moderation notices.** Nothing in
decisions 3–5 is specific to releases, so another kind of notice (a certificate about to expire, a failed backup) is
one decision away — but each needs its own, because each is a new thing the instance watches. Moderation notices —
a new case — are the second kind, decided by ADR 0099 § 7, and the only one that reaches anyone but the Server Owner.
**Rotation due** is the third, to the Server Owner alone: after a moderator steps down or rotates their identity, reports
keep sealing to an epoch that moderator held until the Server Owner mints the next one in admin-web, which shows the same
warning (owner 2026-10-17, ADR 0099 § 4); it is mailed once per epoch. The first tenant owner's invite is
not an instance notice and stays ADR 0048 §5's copy-link (inbox #465).

## Consequences

- **The instance makes an outbound call by default.** It is the first one to a service the Server Owner did not
  configure. *Self-hostable by default* holds because it can be switched off and pointed at a mirror, and nothing
  depends on it; the owner chose default-on over opt-in (2026-10-16) so that forgotten instances hear of fixes.
- **A second server-held mail secret.** ADR 0048's "no instance-wide secret" becomes "no instance-wide secret for
  tenant mail"; ADR 0048 §4 is edited in place to say so.
- **The window reminders count only once a store-shipped client exists.** The web client and admin-web always match
  their server (ADR 0085), and the window itself is not built yet (ADR 0085 § Consequences). Until then the
  planned-major notice is the one that matters, and its date says when the window *would* close.
- **Offline runs switch it off.** The end-to-end lanes ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md))
  boot with no internet; the check must stay quiet there, and a run must not count its failure as a defect.
- **Rejected — opt-in.** No outbound call unless asked, but the instances that most need the notice are the ones
  nobody configures.
- **Rejected — `veyqctl check` only.** No outbound call from the instance at all, but nobody is told unless they run it.
- **Rejected — the registry's tag list as the source.** It works without GitHub Releases, but it cannot say whether a
  major was unscheduled, and `veyqctl` reads the releases anyway.
- **Rejected — reuse a tenant's SMTP for the Server Owner.** There may be no tenant, and it would send instance notices
  under a tenant's identity.

On the docs site: [Operations](../../site/src/content/docs/run/operations.md) (the check, switching it off, a mirror),
[Storage and email](../../site/src/content/docs/run/storage-and-email.md) (the instance mail) and
[What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).
