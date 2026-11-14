---
title: "Running your own instance"
sources:
  - docs/decisions/0026-client-trust-tiers.md
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md
  - docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md
  - docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md
  - docs/crosscutting/security-model.md
---

This page is for whoever **operates** a veyq instance. It is technical and English-only — the rest of this
site is written for the people using the app, and is available in German too.

If you are here to find out whether your memories are safe, you want
[Is my data safe?](/privacy/is-my-data-safe/) instead.

## What self-hosting means

Running an instance yourself is veyq's default, and it is also its strongest security posture. Content is encrypted
and decrypted in the users' browsers, and the server never holds a key that opens it. Whoever runs the server, you
included, sees only ciphertext and operational metadata: who belongs to which tenant and space, sizes, times and ids,
never a photo, a name or a note ([What the server sees](/security/what-the-server-sees/)).

Who serves the web client decides how far that holds
([ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md)):

- **On your own instance** the operator is you, so nobody can serve you tampered code. This is the case veyq is built
  for.
- **For the people you host,** the instance is someone else's, and you serve their web client. Against an operator
  who only reads the database and logs they are protected; against one who changes the code you serve them, they are
  not. veyq says so in the client and never claims otherwise.

The [Security](/security/start-here/) area explains the model, its threat model and its known limits in full.

## What runs

| | Docker Compose | Kubernetes (Helm) |
|---|---|---|
| **Application** | `app`: serves the web client and the API; reaches the database and the store; a second, internal listener (8090) answers only Server-Owner operations | Deployment `veyq-app`, one replica, Service `veyq-app` (8080) and Service `veyq-app-admin` (8090) |
| **Admin surface** | `admin`: the Server-Owner console and the `veyq-admin` CLI, published on `127.0.0.1` only | Deployment `veyq-admin`, a ClusterIP Service, no route unless you set `admin.domain` |
| **Database** | `mariadb`, on a network with no route out | StatefulSet `veyq-mariadb`, or your own database |
| **Object store** | `garage` (Garage, S3-compatible) by default, or a store of your own with Garage left out; browsers upload and download straight to it through presigned links | A store of your own by default; opted in (`garage.enabled`), StatefulSet `veyq-garage`, with an `HTTPRoute` on its own host |
| **Proxy and TLS** | `traefik`, the only container with public ports (80, 443); it routes what the application writes into its one routing file | A Gateway API implementation of your choice serving the chart's `Gateway`; the application writes one `HTTPRoute` (and under per-domain TLS a `ListenerSet`) per tenant into its own namespace |
| **One-shot** | `volume-ownership` hands the Garage and certificate volumes to their non-root writers and exits | — |

The application provisions the bundled store itself once it is chosen (bucket, access key, layout, CORS), and routes every
tenant's domain without a restart. There is no Docker socket mounted anywhere and no cluster-wide permission: on
Kubernetes the application may manage `HTTPRoute`s and `ListenerSet`s in its own namespace and nothing else.

## The honest state, first

**v1.0 is the first stable release** (2026-11-10). There
is a production Compose recipe (the application, its separate administration surface, MariaDB, the Garage object
store unless you leave it out, and a Traefik that terminates TLS in front of them), installed and upgraded by `veyqctl`, and a Helm chart for
Kubernetes. Without the store there
is no media, and a space whose owner has not set up email can invite nobody and none of its members can reset a
password, so neither is optional in a real instance.

Every procedure in this area says how far it has been proven. In short:

| Procedure | Rehearsed | Not rehearsed |
|---|---|---|
| [Set up a new instance](/run/new-instance/) | `veyqctl install` of a release produced from the tree, images built on the host and unverified, on every run of the stack lane; under Helm, v1.0 by digest on the maintainer's single-node k3s (2026-11-10) | `veyqctl install` of a published release |
| [Backup and restore](/run/backup-and-restore/) | Capture, destroy, restore and a test suite against the result, on every run of the end-to-end orchestrator; `veyqctl`'s capture inside an upgrade and its restore onto a fresh install, once | `veyqctl backup` on its own; a capture or restore under Helm |
| [Upgrade](/run/upgrade/) | `veyqctl upgrade` between two releases produced from the tree, once, with a Memory open after it; the data half: the upgrade proof seals content with the previous build, restores it under this one, lets it migrate and reads it back — from jars, not images | Pulling a published image, an upgrade that migrates through `veyqctl`, a rollback; `helm upgrade` in a cluster |
| [Kubernetes](/run/kubernetes/) | The rendered chart, and what a chart upgrade could not apply (`chart_meta_check.py`, `chart_upgrade_check.py`); v1.0 installed by digest through Argo CD on single-node k3s, its schema migrated and both hosts certified (2026-11-10) | An upgrade or a backup in a cluster |
| [Verify a release](/run/verify-a-release/) | Every command on the page against v1.0 as written, and `veyqctl verify --release 1.0` (2026-11-10) | — |

Where a row says "every run", it is a pipeline stage on GitHub (since 2026-11-05); the rest ran once, by hand. The shape of the risk is fixed: veyq migrates its database on start with no way back, so moving to a
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
- **The instance's own mail, and the key that keeps every relay working — not a space's mail.** Each space's
  owner sets up that space's own relay in the app (Settings → Email), and a space with no email set up refuses to
  invite anyone into it, or to send a password-reset link, and says so out loud. A reset link and an invitation by
  email are addressed to the person concerned, so there is no link for you to hand over instead; an invitation the
  space's owner shares as a link is the owner's to hand over, and still needs the space's mail, because the account it
  creates is sent its address confirmation at once. Yours is the instance's own relay, which is optional (**Server → Mail**,
  [Storage and email](/run/storage-and-email/#the-instances-own-mail-to-you)): it mails you of new releases,
  the moderators of moderation's notices, a reporter and the people a decision concerns of its receipt and
  statement, and the people the host has to tell — an account being erased, the members of a
  space being deleted, and the members of a space where an application was switched off, with the day it is erased.
  Without it none of these is mailed, and it never stands in for a space's relay. Also yours is the server's key file
  that seals every relay's SMTP password (below): lose it, and every relay that needs a password stops sending. The
  one mail you trigger yourself, the invitation of a new space's first owner, falls back to a link you copy and pass
  on, because that space has no relay yet.
- **Nothing else.** Not the content, not the keys, not who verified whom. Read
  [Is my data safe?](/privacy/is-my-data-safe/) — the "what we can see" table there is what *you* can see.

## Backups — read this twice

A veyq backup has **two halves**, and the server only holds one of them.

1. **The server's half** — and it is *three* things, not two. The database, the object store's contents, and a
   small **key file** the server manages for itself. A backup holds the database and an *index* of the stored
   objects; the objects themselves are kept by the store or by an object pool you name — on the bundled store, which
   keeps no versions, only the pool does, and `veyqctl status` warns until there is one. The third is the one that gets forgotten, and its absence
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
database and the store running** — are in [Backup and restore](/run/backup-and-restore/). Take one before
every [upgrade](/run/upgrade/).

## The procedures

- [Plan your instance](/run/plan/) — Compose or Kubernetes, sizing, storage growth and quotas, the domains.
- [Verify a release](/run/verify-a-release/) — `veyqctl` itself once, then what it checks before anything runs:
  the signatures, the digests you deploy, the release's files.
- [Set up a new instance](/run/new-instance/) — `veyqctl install` on Docker Compose or the Helm chart, and the
  Server Owner created before DNS and TLS exist.
- [Upgrade](/run/upgrade/) — `veyqctl upgrade`, with the backup as the way back.
- [Kubernetes](/run/kubernetes/) — what is specific to the Helm chart.
- [Backup and restore](/run/backup-and-restore/) — the three legs, captured cold and checked: `veyqctl backup`
  and `restore`.
- [Hardening](/run/hardening/) — what is hardened by default, and what is yours to keep that way.
- [Moderation](/run/moderation/) — a reported public share, and taking a tenant offline.
- [Data protection](/run/data-protection/) — your duties as the controller: a breach, your record of processing,
  your providers' contracts and an access request, with what the instance processes.

## Further reading

- **[`README.md`](https://github.com/raddatzk/veyq/blob/main/README.md)** — repository shape and what is built.
- **[Security](/security/start-here/)** — what the operator can and cannot see, and
  [how to report a vulnerability](/security/reporting/).
