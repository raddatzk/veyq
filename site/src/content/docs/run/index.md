---
title: "Running your own instance"
sources:
  - docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md
  - docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md
  - docs/crosscutting/security-model.md
---

This page is for whoever **operates** a veyq instance. It is technical and English-only — the rest of this
site is written for the people using the app, and is available in German too.

If you are here to find out whether your memories are safe, you want
[Is my data safe?](/veyq/privacy/is-my-data-safe/) instead.

## The honest state, first

**There is no supported production deployment yet.** veyq is pre-V1, and no release has been published. There
is a production Compose recipe (the application, its separate administration surface, MariaDB, the Garage object
store and a Traefik that terminates TLS in front of them) and a Helm chart for Kubernetes. Without the store there
is no media, and a space whose owner has not set up email can invite nobody and none of its members can reset a
password, so neither is optional in a real instance.

Every procedure in this area says how far it has been proven. In short:

| Procedure | Rehearsed | Not rehearsed |
|---|---|---|
| [Backup and restore](/veyq/run/backup-and-restore/) | Capture, destroy, restore and a test suite against the result, on every run of the end-to-end orchestrator; a root restore from the production key volume once, in containers | The `sudo -E` form on a Linux host; a capture or restore under Helm |
| [Upgrade](/veyq/run/upgrade/) | The data half: the upgrade proof seals content with the previous build, restores it under this one, lets it migrate and reads it back — from jars, not images | Pulling a published image, the recipe diff, the `up`, a rollback; `helm upgrade` in a cluster |
| [Kubernetes](/veyq/run/kubernetes/) | The rendered chart, and what a chart upgrade could not apply (`chart_meta_check.py`, `chart_upgrade_check.py`) | An install, an upgrade or a backup in a cluster |
| [Verify a release](/veyq/run/verify-a-release/) | The commands against another keyless-signed image, and the chart half against a local registry | A real veyq release |

None of this has run in CI: the repository has no remote yet, so every rehearsal above is a run on a developer
machine. The shape of the risk is fixed: veyq migrates its database on start with no way back, so moving to a
newer version means taking a backup first and restoring it if the new version does not work out.

So running veyq still means making your own operational decisions. Please do not put memories you cannot afford
to lose on an instance you are not prepared to back up and restore yourself.

## What you are responsible for

Running an instance makes you the **operator**, and veyq is deliberately built so that the operator cannot read
content. That has a consequence people are often surprised by: **you cannot help a user who has lost their
keys.** There is no master password, no admin override, and no escrow. If someone loses their password and
their Emergency Kit, their memories are gone — including if that someone is you.

So the things you own are:

- **Availability.** Whether the service is up, whether backups exist, whether an upgrade breaks. All of this is
  yours, and none of it is veyq's to promise.
- **The key that keeps mail working — not the mail itself.** You do not configure SMTP: there is no
  instance-wide mailer. Each space's owner sets up that space's own relay in the app (Settings → Email), and
  two of the things veyq mails have no substitute at all — an invitation to join that space, and a
  password-reset link. Both are addressed to the person concerned, so there is no link for you to hand over
  instead; a space with no email set up refuses those actions and says so out loud. What *is* yours is the
  server's key file that seals those owners' SMTP passwords (below): lose it, and every space whose relay needs
  a password stops sending. The one mail you trigger yourself, the invitation of a new space's first owner,
  falls back to a link you copy and pass on, because that space has no relay yet.
- **Nothing else.** Not the content, not the keys, not who verified whom. Read
  [Is my data safe?](/veyq/privacy/is-my-data-safe/) — the "what we can see" table there is what *you* can see.

## Backups — read this twice

A veyq backup has **two halves**, and the server only holds one of them.

1. **The server's half** — and it is *three* things, not two. The database, the object store's contents, and a
   small **key file** the server manages for itself. The third is the one that gets forgotten, and its absence
   is the one that hides: an instance restored without it starts up, serves every page, and then, in every
   space whose mail relay needs a password, refuses to invite anybody or send a password-reset link. Nothing in
   it lets anyone read your members' memories. But the database plus the key file do unlock the SMTP passwords
   your spaces' owners entered, and the database alone holds your members' email addresses. So the backup tool keeps the key file apart from the rest, and you should too:
   encrypt the backup before it leaves the server, and keep the key file on a different stick or disk.
2. **The users' half** — their passwords and Emergency Kits. You do not have these and cannot back them up.

A perfectly restored server whose users lost their keys is not a recovered instance. It is unreadable data.
Tell your users to keep their Emergency Kit somewhere safe, and mean it. This is why key recovery is treated
as a durability requirement in veyq, not a convenience feature.

The commands, and the one precondition that makes them correct — **stop the application first, and leave the
database and the store running** — are in [Backup and restore](/veyq/run/backup-and-restore/). Take one before
every [upgrade](/veyq/run/upgrade/).

## The procedures

- [Verify a release](/veyq/run/verify-a-release/) — before anything runs it: the signatures, and the digests you
  deploy.
- [Set up a new instance](/veyq/run/new-instance/) — the production compose recipe or the Helm chart, and the Server
  Owner created before DNS and TLS exist.
- [Kubernetes](/veyq/run/kubernetes/) — what is specific to the Helm chart.
- [Upgrade](/veyq/run/upgrade/) — with the backup as the way back.
- [Backup and restore](/veyq/run/backup-and-restore/) — the three legs, captured cold and checked.

## Further reading

- **[`README.md`](https://github.com/raddatzk/veyq/blob/main/README.md)** — repository shape and what is built.
- **[Security](/veyq/security/start-here/)** — what the operator can and cannot see, and
  [how to report a vulnerability](/veyq/security/reporting/).

## Moderation

There is no central moderation service, because there is no central service. If someone reports an abusive
public share on your instance, the report includes the link — and therefore the key — so you can open it and
judge it. veyq ships no moderation tooling today, and you cannot switch off a single share — only its creator
can revoke it. What you have is the whole tenant: **Take offline** in the administration surface (or
`tenant suspend` in its CLI) makes the tenant unreachable for everyone, its members and every share included,
until you bring it back; nothing inside is changed or deleted. You still cannot read anything private, which is
the design working as intended.
