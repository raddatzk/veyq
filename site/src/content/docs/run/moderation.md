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

## Not built

- **Report intake.** There is no form or address that collects abuse reports; a report reaches you however your
  members reach you.
- **An operator action on a single share.**
- **Moderators.** Decided, not built
  ([ADR 0099](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)):
  you will appoint existing accounts as moderators, who share one moderation key. They review and decide in the web
  app on any tenant's address, with only that key open, never the rest of their account. A moderator can block a
  share, its content, or a guest uploader with the link they came through; blocking a member and taking a tenant
  offline stay yours, and a moderator can only propose them. You are a moderator too, and can step down while
  another moderator holds the key. Where there are two or more, a different moderator reviews an objection.

These are on the security model's target, decided in
[ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md);
see the [Security roadmap](/veyq/security/roadmap/).

**How far this is proven.** Suspension, reactivation and the feature switches are covered by the backend's tests of
their use-cases. No moderation path has run on a deployed instance.
