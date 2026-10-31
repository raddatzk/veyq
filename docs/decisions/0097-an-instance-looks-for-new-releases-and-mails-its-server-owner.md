# ADR 0097 — An instance looks for new releases by default and mails its Server Owner

- Status: Accepted
- Built: yes
- Date: 2026-10-16
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

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

Mail was per tenant only: [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) § 4 gives tenant mail no
server-level default, so each tenant sends from its own identity, and nothing let the instance reach its Server Owner. The Server Owner's account has an email address, entered
at first-run setup ([uc.admin.setup-server](../features/admin/use-cases/uc.admin.setup-server.md), main flow 2).

## Decision

**1. The backend looks for new releases, on by default, and the Server Owner can switch it off.** Daily by default,
it reads the published releases from the same source `veyqctl` uses (ADR 0096 decision 3: the immutable GitHub
Releases, or the operator's mirror) and compares them with its own `VEYQ_VERSION` **locally**. The request is anonymous
and names no version, so the source learns that this address polls veyq — not which release it runs. The switch and
the source are instance settings in admin-web ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)).
A failed check is logged and a later run retries it; it never blocks anything.

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

**3. The instance gets its own mail settings, for the host's own duties only.** A narrow exception to ADR 0048 §4:
admin-web holds one instance-level SMTP configuration (the same fields as a tenant's), its password sealed under the
config-secret key exactly as a tenant's is ([ADR 0050](0050-operator-config-secret-encryption.md)). It sends **only**
instance notices — a duty the host has, not a tenant — and each kind's recipients are decided by the ADR that owns
the duty, never widened here; [cc.mail](../crosscutting/cc.mail.md) § Which mails exist lists them.

The duty is the host's, so the host's channel: a tenant's mail would speak in the tenant's name for the host's
decision. It is never a default or fallback for a tenant's mail, so ADR 0048's reason — each tenant sends from its own
identity — still holds. A new kind or recipient is a decision of the ADR that owns its duty.

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
has no address of its own: a link opened on another device would reach nothing. **Every other address an instance
notice reaches is confirmed by the same rule:** a moderator's (where and when they type the code is ADR 0099 § 7's)
and the instance contact's notification address (ADR 0093 § 11). Each receives notices only once confirmed, and a
changed address asks again. The contact's code is sent only once the Server Owner's own address is confirmed, because
it goes through instance mail.

**6. The channel is built for instance notices, and carries release notices and moderation notices.** Nothing in
decisions 3–5 is specific to releases, so another kind of notice (a certificate about to expire, a failed backup) is
one decision away — but each needs its own, because each is a new thing the instance watches. Release notices are the
first kind. Moderation notices are the second (ADR 0099 § 7), and **rotation due** the third (ADR 0099 § 4), to the
Server Owner alone, mailed once per epoch. An erasure's notice reaches the account being erased (ADR 0101). Moderation
and erasure notices are the ones that reach anyone but the Server Owner; who receives each kind is
[cc.mail](../crosscutting/cc.mail.md) § Which mails exist's list. The first tenant owner's invite is
not an instance notice and stays ADR 0048 § 5's copy-link: instance mail is optional, and the bootstrap must work
without it.

## Consequences

- **The instance makes an outbound call by default.** It is the first one to a service the Server Owner did not
  configure. *Self-hostable by default* holds because it can be switched off and pointed at a mirror, and nothing
  depends on it; the owner chose default-on over opt-in so that forgotten instances hear of fixes.
- **The GitHub source needs a published manifest.** It reads each candidate major's `manifest.json` release asset,
  which a release's publish uploads ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision
  3); without one, a new major on GitHub is a failed check.
- **A second server-held mail secret**, the instance's own; tenant mail still has no instance-wide one
  (ADR 0048 § 4).
- **The window reminders count only once a store-shipped client exists.** The web client and admin-web always match
  their server (ADR 0085 decision 6), and the window is ADR 0085 decision 5's. Until then the planned-major notice is
  the one that matters, and its date says when the window *would* close.
- **Offline runs stay quiet without a switch.** The end-to-end lanes ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md))
  boot with no internet, and a run must not count a failed check as a defect. An image without a release stamp never
  checks; a lane that stamps one points the check at a local mirror.
- **Rejected — opt-in.** No outbound call unless asked, but the instances that most need the notice are the ones
  nobody configures.
- **Rejected — `veyqctl check` only.** No outbound call from the instance at all, but nobody is told unless they run it.
- **Rejected — the registry's tag list as the source.** It works without GitHub Releases, but it cannot say whether a
  major was unscheduled, and `veyqctl` reads the releases anyway.
- **Rejected — reuse a tenant's SMTP for the Server Owner.** There may be no tenant, and it would send instance notices
  under a tenant's identity.

On the docs site: [Operations](../../site/src/content/docs/run/operations.md) (the check, switching it off, a mirror),
[Storage and email](../../site/src/content/docs/run/storage-and-email.md) (the instance mail),
[Running your own instance](../../site/src/content/docs/run/index.md) (what mail is the Server Owner's) and
[What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).
