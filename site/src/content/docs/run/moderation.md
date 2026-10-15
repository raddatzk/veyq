---
title: Moderation
description: What a Server Owner can do about abuse on an instance they cannot read, and what is not built.
sources:
  - docs/features/admin/use-cases/uc.admin.suspend-tenant.md
  - docs/decisions/0066-tenant-suspension.md
  - docs/crosscutting/security-model.md
---

There is no central moderation service, because there is no central service. And an instance's operator cannot
read what its members keep: private content is unscannable by design. Moderation on a veyq instance therefore
works where content is distributed, at the public share, and with one lever on the whole tenant
([security model § Sharing & share-boundary Trust & Safety](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#sharing--share-boundary-trust--safety)).

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
docker compose -f infra/docker-compose.prod.yml exec \
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

## Not built

- **Report intake.** There is no form or address that collects abuse reports; a report reaches you however your
  members reach you.
- **An operator action on a single share.**
- **Switching a feature off for the whole instance** (for example public links). The register of switches is
  decided and not built.

These are on the security model's target; see the [Security roadmap](/veyq/security/roadmap/).

**How far this is proven.** Suspension and reactivation are covered by the backend's tests of the use-case. No
moderation path has run on a deployed instance.
