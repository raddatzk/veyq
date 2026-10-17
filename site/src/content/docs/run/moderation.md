---
title: Moderation
description: What a Server Owner can do about abuse on an instance they cannot read, and what is not built.
sources:
  - docs/features/admin/use-cases/uc.admin.suspend-tenant.md
  - docs/features/admin/use-cases/uc.admin.switch-capabilities.md
  - docs/decisions/0066-tenant-suspension.md
  - docs/crosscutting/security-model.md
  - docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md
  - docs/decisions/0032-capability-discovery-version-gating.md
  - docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md
  - docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md
  - docs/features/moderation/use-cases/uc.moderation.appoint-moderators.md
  - docs/features/admin/use-cases/uc.admin.operator-details.md
  - docs/features/moderation/use-cases/uc.moderation.report.md
  - docs/features/moderation/enablers/en.moderation.hold.md
---

There is no central moderation service, because there is no central service. And an instance's operator cannot
read what its members keep: private content is unscannable by design. Moderation on a veyq instance therefore
works where content is distributed, at the public share, and with one lever on the whole tenant
([security model § Sharing & share-boundary Trust & Safety](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#sharing--share-boundary-trust--safety)).

## What a report does

Anyone who sees something — a member of a space or a guest of a link — can choose **Report** on it
([Report something](/veyq/help/report-something/)). What happens on your server
([uc.moderation.report](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.report.md)):

- **It arrives sealed.** The reporter's device seals their text, their address and the keys to exactly what they
  reported to the current moderation key. The database holds the category, the time, the reported items' ids and who
  reported (an account, or the link a guest came through) — nothing it can open.
- **One case per item.** A further report of the same item joins its open case and raises its category if it is more
  urgent.
- **It is kept.** The rows of what was reported are copied, and its stored objects are pinned: the reclaim sweep leaves
  them alone, so a delete after the report takes the item away from everyone but not from the moderators — for 90
  days after the report ([en.moderation.hold](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.hold.md)).
  You store those bytes, and a backup includes them; the tenant's quota falls as its members expect. A space's erasure
  leaves the report and the copy.
- **Moderators are mailed** the category and "new case", with no id and no link: child sexual abuse material, terrorist
  content and a threat to life at once, the rest in one mail a day. The instance contact's notification address is
  mailed too once confirmed. A reporter who left an address gets a receipt.
- **Nothing is blocked.** The item stays where it was until a person decides.

A report a member sends counts against a cap of open reports per account (`veyq.moderation.report.open-cap`, 100); a
guest has no account, so a guest's reports are bounded only per address and per link (`veyq.moderation.report.per-share-max-requests`).
Someone rotating addresses can still send many; each costs you a moderator's look and the held bytes for 90 days.

## A reported public share

A public link carries its key in the URL fragment. So a report that includes the full link lets you open the share
in a browser, decrypt it like any guest, and judge it. Nothing else of the tenant opens with it.

What you cannot do today is act on that one share. Revoking a link belongs to its creator alone; the server has no
operator action on a single share. Ask the tenant's owner, who can ask the share's creator, or take the whole tenant
offline (below).

Review the link **before** you take its tenant offline: while the tenant is offline, every link of it answers as a
revoked link, including the one you were sent.

## Take a tenant offline

**Take offline** in the admin console, or the CLI:

```sh
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec \
  -e VEYQ_ADMIN_EMAIL=you@example.com -e VEYQ_ADMIN_PASSWORD \
  admin veyq-admin tenant suspend --tenant-id <id>
```

What it does ([uc.admin.suspend-tenant](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.suspend-tenant.md),
[ADR 0066](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0066-tenant-suspension.md)):

- Every member is refused with `403 tenant_suspended`, reads included, on any domain and with a valid session.
  Someone who is not a member gets the same `404` as before.
- Every public link answers as a revoked link, and no background job of the tenant runs.
- The tenant's routing is withdrawn as a second layer.
- Nothing inside is changed or deleted: no key, grant, content or session. **Bring back online** (or
  `veyq-admin tenant reactivate --tenant-id <id>`) restores everything as it was.
- It applies to an active tenant only, and it locks out your own membership in that tenant too.

You still cannot read anything private, which is the design working as intended.

## What else you have

- **A storage quota per tenant** — the most a tenant may keep on your store
  ([Plan your instance](/veyq/run/plan/#storage-and-quotas)).
- **Revoking a tenant** that was created but never accepted (a pending one) deletes it. There is no operation that
  deletes an active tenant.
- **Switching a feature off for the whole instance**, for example public links or devices that unlock without a
  password (below).

## Switch a feature off

You can switch a feature off for the whole instance, for example public links, drop boxes or devices that unlock
without a password, in the admin console or with `veyq-admin capabilities`. What each switch does to new and to
existing data, and how to switch it, is on [Capabilities](/veyq/run/capabilities/).

## The operating mode

At first-run setup you choose how the instance is run, and you can change it under **Server → Operating mode** or
with `veyq-admin mode set` ([uc.admin.operator-details](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.operator-details.md), [ADR 0093 § 14](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)):

- **Private (family, circle of friends)** — veyq leaves off what only the GDPR, the Digital Services Act and the EU
  regulation on terrorist content ask of a host: the contact and the provider identification become optional and
  nothing warns about them. Reporting, blocking and the moderators stay on in either mode: criminal law applies to
  every operator, and an operator who learns of illegal content has to act.
- **Club, organisation or company** — the contact and the provider identification are expected: the console warns
  while public links are on and no contact is set, and while the identification lacks a name, an address for service
  or an email. An instance where no mode was ever chosen runs as this one.

**It is your own assessment, not veyq's, and not legal advice.** The choice comes with a notice that says so: the
GDPR's household exemption covers only a purely personal or family activity and ends once a link is shared publicly
(CJEU *Lindqvist*, C-101/01; *Buivids*, C-345/17), and whether the Digital Services Act and the terrorist-content
regulation reach an unpaid private instance is legally uncertain. If you share links publicly, or run veyq for a club,
an organisation or a company, you are probably not exempt. Choosing *private* needs a tick in the console, and
`--accept-notice` with `veyq-admin mode set private`; the server does not record either.

**A mode is a preset.** Choosing one sets the switches it names to its values, after the console and the CLI have
shown which ones would change; each stays an ordinary switch you can set on its own
([Capabilities](/veyq/run/capabilities/)), and choosing the mode again sets them again. A tenant's own switches are
never touched. Nothing is required and nothing is refused in either mode: a warning is a warning.

## Who runs the instance

**Server → Operator** (or `veyq-admin operator set`) holds what a host publishes about itself; every field may stay
empty:

- **The instance contact** — the single point of contact for users and authorities, and for removal orders on
  terrorist content, with the **languages it answers in**;
- **the address statements of reasons name** — may be the same as the contact;
- **the provider identification** — **Name**, **Address for service**, **Email**, and for a club or a company who
  represents it;
- **the data-protection supervisory authority**.

These are public: the app reads them before sign-in on every address of the instance, and shows the identification,
where it is set, behind **Legal notice** on the sign-in and share pages. The **notification address** is not public:
new moderation cases will be mailed there. Saving it mails a code to it through the instance mail, once Server → Mail
is set up and confirmed (the panel says so until then); you type the code into the same panel, and nothing is mailed
to the address before. A changed address asks again. **Clear all** removes every value and keeps the mode.

## Moderators

You decide who acts on reports for the whole instance
([uc.moderation.appoint-moderators](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.appoint-moderators.md),
[ADR 0099](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)).
A moderator is an existing account, and every moderator holds the same **moderation key**, which reports will be
sealed to. The server stores the key's public half and a copy of the key sealed to each moderator's account; it can
open none of them. Reports themselves are not built yet (below), so today this sets up who will review them.

**From first-run setup on.** Setting the instance up also makes the first moderation key: the admin console seals it
to the account it has just created for you and forgets it. You are the first moderator, and reporting is on from the
first day. **Server → Moderation** shows the key's epoch and the roster.

**Appoint someone.** Enter the address of an existing account and choose **Appoint**, or:

```sh
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec \
  -e VEYQ_ADMIN_EMAIL=you@example.com -e VEYQ_ADMIN_PASSWORD \
  admin veyq-admin moderator appoint --address deputy@example.com
```

Someone who has no account yet is first invited into a space. The appointment stays **pending** until that person
opens `/moderation` on a space's address they sign in on, reads what accepting means and accepts. Accepting mails them
a code through the instance mail (Server → Mail must be set up) to confirm their address for notices. The key reaches
them from a moderator who holds it, the next time that moderator opens `/moderation`; the roster then shows they hold
it. `veyq-admin moderator list` prints the roster.

**Remove someone.** **Remove** on their row, or `veyq-admin moderator remove --address <address>`. Their access and
their copy of every key end at once, and the console then makes a new key for everyone who stays, so reports from then
on are sealed to a key they never held. A removal that would leave nobody holding the key is refused.

**Rotate the key by hand.** Choose a reason (by hand, suspected compromise, identity rotation, removal) and **Rotate
the key**, or `veyq-admin moderation rotate --reason compromise`. Reports already made stay sealed to the key they
were made under; only later ones use the new key.

**When a moderator steps down.** A moderator can step down in `/moderation`, and you can step down too while another
moderator holds the key. Their access ends at once, but until you rotate, reports are still sealed to the key they
held. The panel then says **rotation due**, and the instance mail tells you once. Rotate when you see it. The same
happens when a moderator replaces their account keys; they get the key again from another moderator.

**What is logged.** Every appointment, acceptance, handing on of the key, removal, step-down and new key is an entry
in the moderation log: who, when and for whom, never a key. Entries are kept until 31 December of the third year after
the moderator concerned left the roster, then deleted.

**What this does not protect against.** You own the server and the roster. A server that hands out a key of its own
choosing could read later reports; moderators' pages compare the key the server publishes with the one they hold and
stop when it differs, but a reporter cannot check it. Moderation keeps a team accountable to each other; it is not a
control against the operator.

## Not built

- **An operator action on a single share.**
- **The review and every decision.** Reports arrive and are kept (above), but the moderation page does not list or open
  them yet: the queue's content, ending a hold before its 90 days, blocking a share or its content, and the review of
  an objection by a second moderator are decided in
  [ADR 0099](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  and not built.

These are on the security model's target, decided in
[ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md);
see the [Security roadmap](/veyq/security/roadmap/).

**How far this is proven.** Suspension, reactivation and the feature switches are covered by the backend's tests of
their use-cases. No moderation path has run on a deployed instance.
