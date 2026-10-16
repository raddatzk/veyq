---
id: cr-capability-requirements
title: Requirements on capability entries, the strictest of instance and tenant, enforced at once
status: in-progress
satisfies: ["uc.admin.capability-requirements"]
adrs: ["0088", "0032", "0004"]
invariants:
  - "Operator-blind: a check reads only what the server already holds (a verifier's presence, dates, a session's start)"
  - "A tenant only tightens: the effective value is the strictest of both levels (ADR 0088 decision 8)"
---

## Goal

Inbox #439 step 3. Four requirements whose enforcement the server can do today: a link needs a password, a link lives
at most N days, being added to a group needs consent, a device asks for the password again after N days. Each is set by
the Server Owner (admin-web, CLI) and, on a tenant-switchable entry, by the tenant owner (web), is delivered with the
capability (`[{ id, …values }]`) and enforced at once, on what exists too (ADR 0088 decision 5). The use case
[uc.admin.capability-requirements](../docs/features/admin/use-cases/uc.admin.capability-requirements.md) holds the table
of what each refuses and what it does to existing data.

## Context & constraints

- Built on cr-tenant-capability-switches (merged on this branch): the catalog's `levels`, `CapabilityRegistry.isActive(id,
  tenantId)`, `isActiveInScope`, the tenant owner's panel, the members' effective set.
- The gates: `SharingHandler.create/update/reissue` (`backend/core/.../sharing/SharingHandler.kt`), the guest gate
  `ShareGuestGate.activeShare` (before the password, as the share switch), `ScopesHandler.addUnderLocks` and
  `resolveGroupMember` (`requiresGroupAddConsent`), `SessionService.rotate` (the family's `createdAt`).
- Not built, recorded: "share only with verified identities" (no verified-identity concept the server could check, ADR
  0088's table says so) and `core.offline-cache` (client-only, the server cannot enforce it).

## Decisions

- **D1 — the catalog declares requirements per entry**: `"requirements": { "<name>": { "kind": "flag" | "days",
  "title": {en, de}, "whenSet": {en, de} } }`. A requirement is settable at the levels of its entry. Names are unique
  across the catalog because they become properties of the wire's `Capability`; `validate_capabilities.py` holds both,
  and that every name is a `Capability` property of the matching type (flag → boolean, days → integer).
- **D2 — the four**: `core.public-shares` `linkPassword` (flag), `linkMaxDays` (days); `core.group-space` `addConsent`
  (flag); `core.remember-device` `passwordMaxDays` (days, instance-only like its entry).
- **D3 — the effective value is the strictest**: a flag is set if either level sets it; days is the minimum. A tenant
  value looser than the instance's is stored and shown as not in effect.
- **D4 — values**: an integer per requirement and level, absent = not required; a flag is stored as `1`; days 1–3650.
  Two tables beside the switches: `veyq_capability_requirement(requirement PK, value)` and
  `veyq_tenant_capability_requirement(id, tenant_id, requirement, value)` (unique, cascade). One cache generation with
  the switches' pattern.
- **D5 — the link's lifetime is measured from when it was made**: `linkMaxDays` N means `expiresAt ≤ createdAt + N days`,
  at create (createdAt = now), at update (the link's own createdAt) and at the guest gate. A moving "within N days from
  now" would refuse a link today and serve it again later. A link older than N days cannot be fixed by an edit; it is
  renewed (a renewed link is a new link with a new start).
- **D6 — what exists**: a link without a verifier (`linkPassword`) or outside its lifetime (`linkMaxDays`) answers every
  guest path exactly as an unknown link, before the password is asked, at `ShareGuestGate.activeShare`; nothing is
  written, so clearing the requirement or fixing the link serves it again. Re-activating such a link is allowed (it
  serves no guest until fixed; its owner fixes it with an edit). `addConsent` leaves members already added alone,
  including a direct add whose key is still on its way: the server cannot turn it into a consent request (it holds no
  sealed name key) and removing it would delete a membership. `passwordMaxDays` refuses the renewal of a session family
  whose `createdAt` is more than N days ago (`401`, as any failed renewal), without revoking it.
- **D7 — the codes**: `422 link_password_required`, `422 link_expiry_required` (create, reissue, update);
  `400 consent_required` is the existing one.
- **D8 — the wire**: `Capability` gains optional `linkPassword`, `linkMaxDays`, `addConsent`, `passwordMaxDays`, set
  only on their entry and only when in effect; both deliveries carry them (the pre-auth one the instance level).
  `InstanceCapability.requirements` and `TenantCapabilitySwitch.requirements` list each with its words and values;
  `PUT …/capabilities/{capabilityId}/requirements/{requirement}` at both levels sets `{ value }` (null clears).
- **D9 — the web**: the share dialogs read the link rules from the effective set through one helper
  (`core/sharing/linkRules.ts`): a required password turns the password card on and locks it, a maximum lifetime turns
  the expiry card on and limits the picker; the drop-box dialog gains the two cards only when required. The profile's
  consent checkbox says when the open space requires consent anyway. Both owner panels set requirements; the CLI gains
  `capabilities require <id> <requirement> <value|off>` and `list` prints them.

## Phases

- [ ] 1 contracts + catalog — D1, D2, D8; the guard. · Done when: contracts guards and `validate_capabilities.py` green,
      its bites recorded.
- [ ] 2 backend — D3-D7. Tests that bite, each with its control: a password requirement refuses a create without one and
      serves nothing of an existing link without one, in that tenant only; a lifetime requirement refuses a create
      beyond N days and the guest path of a link outside its lifetime; consent turns a direct add into
      `consent_required`; a session older than N days is not renewed while a young one is; a looser tenant value is not
      in effect. · Done when: `./gradlew --no-watch-fs check` green.
- [ ] 3 web + admin-web — D9. · Done when: `npm run check` green in both.
- [ ] 4 docs — ADR 0088 § Consequences, ADR 0032 §2, the Run page, the use case's coverage, the share Use pages,
      inbox #439.

## Definition of done

Checks green on every surface, the guard glob green, coverage for the use case, site pages updated, CR harvested and
deleted.

## Retrospective

(filled at close)
