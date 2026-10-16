---
id: cr-tenant-capability-switches
title: A tenant owner switches register entries off for their tenant, and every gate refuses per tenant
status: in-progress
satisfies: ["uc.admin.tenant-switch-capabilities", "uc.platform.capability-discovery"]
adrs: ["0088", "0032", "0065"]
invariants:
  - "Operator-blind: a switch is tenant configuration; nothing here is member data, and no gate reads content"
  - "A tenant only narrows: the effective state is the AND of the instance level and the tenant level along the path (ADR 0088 decision 2)"
---

## Goal

Inbox #439 step 2. A tenant owner opens the tenant settings in `web/` (beside terms and mail), sees the register
entries a tenant may switch, with the catalog's words, and switches one off for their tenant. The server then
delivers the tenant's effective set after sign-in, and every gate refuses per tenant: a share switch reaches the
tenant's existing links at once, a feature switch refuses only what is new (ADR 0088 decision 5).

## Context & constraints

- [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decisions
  2, 3, 5, 12, 13; [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md) §2-§4.
- The built instance level is the exemplar: `CapabilityRegistry`, `StoredCapabilitySwitches`
  (`backend/core/.../platform/capabilities/`), `CapabilitySwitchHandler`, admin-web's `CapabilitiesPanel.vue`.
- The tenant-owner settings are the exemplar for the gate: `TenantMailSettingsHandler` (`access.check(...)
  .refusal(NotFound, Suspended)`, then `store.isOwner` → `403 not_tenant_owner`), path-addressed
  `/api/tenants/{tenantId}/…`, no Host needed.
- The tenant of a request is resolved by Host only (`RequestTenant`); a gate that acts on a scope takes that
  scope's tenant (`ScopeEntity.tenantId`, non-null), which is the tenant the content lives in.

## Decisions

- **D1 — the catalog marks who may switch an entry**: `"levels": ["instance"]` (`*`) or `["instance", "tenant"]`
  (`*+`). `validate_capabilities.py` requires it; `GenerateCapabilityRegister` generates `CapabilityIds.TENANT_SWITCHABLE`.
- **D2 — an entry a client offers before sign-in is instance-only.** `core.remember-device`, its `.passwordless`,
  and `core.device-linking` stay `*`: the login screen shows "stay signed in" and "sign in with code" from the
  pre-auth manifest (`LoginView.vue`), the key material is per account, not per tenant, and the pairing channel
  carries no tenant (`PairingHandler`). A tenant switch the login screen cannot see would be a button that fails.
  ADR 0088's register marks are edited to match (`device-linking` moves from `*+` to `*`, with this reason).
  Tenant-switchable: `core.public-shares` and its two children, `core.group-space`.
- **D3 — storage**: `veyq_tenant_capability_switch(tenant_id UUID, capability_id VARCHAR(64))`, PK on both, FK
  `ON DELETE CASCADE` to `veyq_tenant` (as `veyq_tenant_mail_settings`). One row per entry the tenant switched off. A
  new migration, not a baseline edit, so no dev database must be recreated. Read through a cache keyed by tenant and
  tagged with one generation that every write bumps after commit (the `StoredCapabilitySwitches` pattern).
- **D4 — the registry**: `isActive(id, tenantId: String?)` replaces `isActive(id)`, so every call site states its
  tenant; `null` is the instance level alone (pre-auth, and the instance-only entries). The effective state is the
  instance AND along the path AND the tenant AND along the path; a tenant row for an instance-only entry is never
  stored (the write refuses it) and would be ignored.
- **D5 — every gate gets the tenant of the scope it acts in**: share create/update/reactivate/reissue and the
  guest gate take the share's (or node's) scope tenant; `createGroup` its `tenantId`; node creation its scope's
  tenant. `KeyMaterialHandler` and `PairingHandler` pass `null` (D2). `createShare` keeps its instance-level check
  before any lookup (it reads as "the operator turned sharing off") and adds the tenant's after the node/access
  check, because only the node names the tenant: a non-member gets the 404 it gets anyway and learns nothing of
  another tenant's policy.
- **D6 — the wire** (tag `tenants`):
  - `GET /api/tenants/{tenantId}/capabilities` → `TenantCapabilities { capabilities: [Capability] }`, every member
    (READ), the effective set (decision 3). `Capability` moves from `setup` to `_common`, so both deliveries are
    one type and grow together in step 3.
  - `GET /api/tenants/{tenantId}/capability-switches` → `[TenantCapabilitySwitch]`, owner only: the tenant-switchable
    entries in catalog order with `title`, `purpose`, `whenOff`, `off` (the tenant's own switch), `serverOff` (the
    instance level has it off, the entry or an ancestor) and `effective`.
  - `PUT /api/tenants/{tenantId}/capability-switches/{capabilityId}` `{ "off": bool }` → the entry, owner only;
    `404 capability_not_found` for an id that is unknown or not tenant-switchable, nothing stored.
  - `InstanceCapability` gains `tenantSwitchable` so the Server Owner's console says which entries tenant owners can
    narrow further.
- **D7 — the web client** keeps the pre-auth instance set and, on every scope entry (`enterScope`, beside the terms),
  loads the tenant's set; `hasCapability` reads the tenant set while one is loaded. On a failed load it keeps the
  instance set (the server refuses anyway). Sign-out drops the tenant set.
- **D8 — the tenant owner's panel** `TenantCapabilitiesPanel.vue` in the settings hub's tenant category, owner only,
  ported from admin-web's `CapabilitiesPanel` (the same tree, states and confirm-before-off with `whenOff`), plus a
  fourth state "Off for the whole server" when `serverOff`. A small tree helper is ported to web (admin-web is not
  importable from web).
- **D9 — the words are one text for both levels** (decision 13). `core.group-space`'s "so the instance offers personal
  spaces only" becomes level-neutral. The texts say what is built.

## Locators (run at refine, 2026-10-16, on this branch's tip)

- Gates: `grep -rn "isActive(" backend/core/src/main backend/apps/*/src/main --include='*.kt'`
- Callers of the manifest in web: `grep -rn "hasCapability(" web/src --include='*.ts' --include='*.vue'`
- Tests that switch capabilities: `grep -rln "CapabilitySwitchFixtures\|isActive(" backend/*/src/test* backend/apps/*/src/test*`

## Phases

- [ ] 1 contracts + catalog — D1, D6; `validate_capabilities.py` levels check (bite: an entry without `levels`).
- [ ] 2 backend — D3-D5, the three operations, `OperatorBlindProbeTest` classification. Tests that bite: a tenant
      switch refuses that tenant's create and not another tenant's (control); a tenant switch on `core.public-shares`
      makes that tenant's existing link answer `404` like an unknown one and leaves another tenant's link served;
      member reads the set, owner switches, member `403`, outsider `404`, instance-only id `404`; the instance off
      wins over a tenant on. · Done when: `cd backend && ./gradlew --no-watch-fs check` green.
- [ ] 3 web + admin-web — D7, D8, the console's hint. · Done when: `npm run check` green in both.
- [ ] 4 docs — ADR 0088 (§ Consequences, register marks, D2), ADR 0032 §4, `uc.admin.tenant-switch-capabilities` +
      coverage, `uc.platform.capability-discovery`, the Run page, a space-owner Use page (EN+DE), inbox #439.

## Definition of done

Backend check, `npm run check` in web/ and admin-web/, the guard glob green; coverage for both use cases; the site
pages updated; this CR harvested and deleted.

## Retrospective

(filled at close)
