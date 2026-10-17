---
title: Storage and email
description: The object store as the console sets it, the bundled store, setting a store up, quotas, each tenant's mail and the instance's own mail to you, and the config-secret key.
sources:
  - docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md
  - docs/decisions/0067-store-provisioning-from-a-declared-type.md
  - docs/decisions/0050-operator-config-secret-encryption.md
  - docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md
  - docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md
  - docs/features/admin/use-cases/uc.admin.learn-of-new-releases.md
---

## The object store

Media are stored, encrypted, in an S3-compatible object store. Browsers upload to it and download from it directly,
through short-lived presigned links; the application never carries the bytes. Which store is a setting of the admin
console, **Server → Object store**. A saved setting waits beside the one in use until it passes its check, and then
applies at once, without a restart — so a mistyped key never reaches your media
([ADR 0089](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)).
How to set it and its check: [First-run setup](/veyq/run/first-run/#connect-the-object-store).

Until a store is set and has passed its check, the instance runs and answers, refuses uploads with a reason, and
refuses to create a tenant. Once the first file is stored, the store's type, endpoint and bucket are fixed; moving an
instance's media to another store is not supported.

**The bundled store.** Both recipes bring a Garage and hand the application a hint to it: its addresses and its admin
token (`GARAGE_ADMIN_TOKEN` / `secrets.garageAdminToken`). The console then offers **Use the bundled store**, which
stores a reference to that hint, not a copy of its secrets; the store's access keys are derived from the token.
On Compose the browser reaches it under a path of each tenant's own domain, so no CORS rule is needed. On Kubernetes
it has a host of its own (`storeDomain`), and the application writes a CORS rule that allows any origin.

**A store of your own.** An S3-compatible service, or a Garage you run:

- **S3-compatible:** the bucket must exist; veyq never creates a bucket or writes a CORS rule on a store you bring.
  Give it a **Public endpoint** when browsers reach the store at another address than the server does. The bucket's
  CORS rule must allow `PUT` and `GET` from the instance's origin with the `Content-Type` header, and expose `ETag`.
  The check tests the instance's own domain only; a tenant on a domain of its own is a further origin the rule must
  allow, which the check does not see. The access key must also be allowed to **list** the bucket: the application
  compares the store with its database in the background, and without the list right the console reports that the
  store refused it and counts nothing ([Operations](/veyq/run/operations/#objects-no-record-claims)). Give veyq a
  bucket of its own — another server or tool writing into the same bucket is why the reconcile deletes nothing until
  you confirm the bucket is this server's alone.
- **Garage:** veyq sets it up — layout, bucket, access key, grant and CORS rule — from the admin endpoint and token
  you enter
  ([ADR 0067](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0067-store-provisioning-from-a-declared-type.md)).

**Setting a Garage up.** For a Garage store (the bundled one included) the application sets the store up in the
background at every start, with a few retries over about two minutes; it never holds the start back. The console's
**Object store** panel shows the state: in progress, ready, or failed, and the server log names the step that
failed. A passing **Check store** runs the set-up again.

**Quotas.** A tenant's quota and what counts towards it: [Plan your instance](/veyq/run/plan/#storage-and-quotas).

## Email

A tenant's mail is never yours to configure: each tenant's owner sets up that tenant's own relay
in the app (Settings → Email): host, port, sender, username, password and STARTTLS
([uc.admin.tenant-mail-settings](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md)).
Two things veyq mails have no substitute, because they are addressed to the person concerned: an invitation to join
the tenant, and a password-reset link. A tenant without working mail refuses both, and says so.

What is yours:

- **Let the application send mail out:** outbound SMTP to the hosts the tenant owners choose (and yours, below), usually port 587. Both
  recipes leave the application's outbound traffic open for this; a firewall of yours must too.
- **The first owner's invitation** of a new tenant is never mailed: the console gives you a link to pass on
  ([First-run setup](/veyq/run/first-run/#tenants)).
- **The key that seals the relays' passwords** (below).

### The instance's own mail, to you

The one relay that is yours is optional: the instance's mail to **you**, for its notices about new releases
([Operations](/veyq/run/operations/#new-releases)). Set it on the console's **Server → Mail** — the same fields as a
tenant's relay. It sends only those notices and only to the address of your Server Owner account, and it never sends
a tenant's mail, nor stands in for a tenant without one
([ADR 0097](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)).

1. Enter the relay and save. The instance mails an eight-digit code to your account's address.
2. Type the code into **Server → Mail** within an hour. A wrong code counts; five spend it, and a save sends a new one.
3. From then on each notice is mailed once, with the link to [Upgrade](/veyq/run/upgrade/); one with a date carries
   it as a calendar entry (`.ics`).

Every save asks for a code again, and so does a changed address on your account. Its password is sealed under the
same key as the tenants' (below); while that key cannot open it, nothing is mailed and the panel says why. Without
this relay the notices are shown on the console and that is all.

## The config-secret key

The application seals the secrets it must keep — each tenant's SMTP password, the instance mail's password, and the
secret key and Garage token of a store of your own — under a key file it generates on its first start
([ADR 0050](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0050-operator-config-secret-encryption.md)).
The bundled store needs nothing sealed. The key defends a leaked database backup: the dump alone opens no secret. It
does not defend a compromised host.

| | Docker Compose | Kubernetes |
|---|---|---|
| Where it lives | the `veyq-config-secret` volume, `/var/lib/veyq/config-secret.key` | the `veyq-config-secret` claim, kept when the release is uninstalled |
| Owned by | the application's user, uid 10001, mode `0600` | the same |

**Without that volume** every recreated container would generate a new key, and every secret sealed under the old
one would stop opening. `scripts/validate_config_secret_persistence.py` keeps the image, the backend and the compose
file agreeing on the path.

**Its state** is on the console's **Server → Config-secret key**, or `veyq-admin config-secret`:

- **OK** — the key is in place, and it is the one the stored secrets were sealed with (or nothing has been sealed
  yet).
- **ABSENT** — no key is available: it could not be created, or the application cannot read the file (after a
  restore, the file must belong to uid 10001). Nothing can be sealed.
- **MISMATCH** — the key is not the one some stored secret was sealed with: lost, replaced or not restored.

A secret that cannot be opened degrades only what needs it: a tenant whose relay needs a password refuses invitations
and password resets until its owner re-enters the password; a store of your own is unreachable until you re-enter its
credentials, in the console. Content is never affected. Putting the original key file back and restarting opens
everything again.

Back the key up apart from the database and the store, on a medium of its own
([Backup and restore](/veyq/run/backup-and-restore/)).

## How far this is proven

The store setting, its check and the bundled hint are covered by the backend's tests; the backup rehearsal restores
an instance onto a fresh install that sets its bundled store up from the hint. No S3 service other than Garage is
recorded as tried, and no mail relay is exercised beyond a test mailbox — the instance mail's code, notices and
calendar entry included, which the backend's tests send through an SMTP server inside the test run.
