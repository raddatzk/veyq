# Running your own instance

This page is for whoever **operates** a veyq instance. It is technical and English-only — the rest of this
wiki is written for the people using the app, and is available in German too.

If you are here to find out whether your memories are safe, you want
[Is my data safe?](is-my-data-safe.md) instead.

## The honest state, first

**There is no supported production deployment yet.** veyq is pre-V1. There are now two Compose files — a
*development* stack (database, an S3-compatible object store, a throwaway mailbox, the latter two opt-in
*profiles*) and a *production* one that adds the application itself and terminates TLS in front of it. Do not
read "optional profile" as "optional in a real instance": without a store there is no media, and without
working email nobody can be invited and nobody can reset a password.

A **backup-and-restore procedure is written down and tested**, and there is now an **upgrade runbook** beside
it — but the two are not equally proven, and the difference matters more than either heading. The backup is
rehearsed automatically: every build captures a real instance, destroys it, restores it, and checks that what
came back works. The upgrade runbook has been **performed by nobody**, because no version of veyq has been
published yet, so there is no second release to move between. It is a careful procedure derived from how the
system is built, not a path anyone has walked.

What has not changed is the shape of the risk: veyq migrates its database on start with no way back, so moving
to a newer version means taking a backup first and restoring it if the new version does not work out.

So running veyq still means reading the repository and making your own operational decisions. Please do not put
memories you cannot afford to lose on an instance you set up from the dev stack.

## What you are responsible for

Running an instance makes you the **operator**, and veyq is deliberately built so that the operator cannot read
content. That has a consequence people are often surprised by: **you cannot help a user who has lost their
keys.** There is no master password, no admin override, and no escrow. If someone loses their password and
their Emergency Kit, their memories are gone — including if that someone is you.

So the things you own are:

- **Availability.** Whether the service is up, whether backups exist, whether an upgrade breaks. All of this is
  yours, and none of it is veyq's to promise.
- **A working mailer.** Two of the things veyq mails have no substitute at all: an invitation to join a space,
  and a password-reset link. Both are addressed to the person concerned, so there is no link for
  you to hand over instead — an instance with no email set up simply refuses those actions, and says so out
  loud. Configuring SMTP is not a nice-to-have; it is part of what makes an instance usable at all.
- **Nothing else.** Not the content, not the keys, not who verified whom. Read
  [Is my data safe?](is-my-data-safe.md) — the "what we can see" table there is what *you* can see.

## Backups — read this twice

A veyq backup has **two halves**, and the server only holds one of them.

1. **The server's half** — and it is *three* things, not two. The database, the object store's contents, and a
   small **key file** the server manages for itself. The third is the one that gets forgotten, and its absence
   is the one that hides: an instance restored without it starts up, serves every page, and then refuses to
   invite anybody or send a password-reset link. Nothing in it lets anyone read your members' memories. But
   the database plus the key file do unlock the SMTP passwords you configured, and the database alone holds your
   members' email addresses. So the backup tool keeps the key file apart from the rest, and you should too:
   encrypt the backup before it leaves the server, and keep the key file on a different stick or disk.
2. **The users' half** — their passwords and Emergency Kits. You do not have these and cannot back them up.

A perfectly restored server whose users lost their keys is not a recovered instance. It is unreadable data.
Tell your users to keep their Emergency Kit somewhere safe, and mean it. This is why key recovery is treated
as a durability requirement in veyq, not a convenience feature.

The commands, and the one precondition that makes them correct — **stop the application first, and leave the
database and the store running** — are in
[`infra/README.md`](https://github.com/raddatzk/veyq/blob/main/infra/README.md) § Backup & restore. Take one
before every upgrade.

## Where the material is

These are the real sources:

- **[`infra/README.md`](https://github.com/raddatzk/veyq/blob/main/infra/README.md)** — both stacks: the dev
  profiles, the object store, the mail setup (including the throwaway mailbox to test it against), running in
  production, and the backup-and-restore runbook.
- **[`README.md`](https://github.com/raddatzk/veyq/blob/main/README.md)** — repository shape and what is built.
- **[`docs/architecture/README.md`](https://github.com/raddatzk/veyq/blob/main/docs/architecture/README.md)** —
  deployment topology and the runtime view.
- **[`SECURITY.md`](https://github.com/raddatzk/veyq/blob/main/SECURITY.md)** — how to report a vulnerability.

## Moderation

There is no central moderation service, because there is no central service. If someone reports an abusive
public share on your instance, the report includes the link — and therefore the key — so you can open it and
judge it. veyq ships no moderation tooling today; what you have is control over reachability. You still cannot
read anything private, which is the design working as intended.
