// The tenant level of the capability register (uc.admin.tenant-switch-capabilities, ADR 0088 decisions 2, 3, 12) — a
// tenant-OWNER surface beside the mail settings and terms, in `web/` for the same reason: the switches are gated with
// `403 not_tenant_owner`, and the owner is an ordinary member of their own tenant (ADR 0065). The effective set is
// every member's.
import { tenantsApi } from '../../api/client'
import type { CapabilityText, TenantCapabilitySwitch } from '../../api/generated/tenants'

export type { CapabilityText, TenantCapabilitySwitch } from '../../api/generated/tenants'

/** Any member: the ids this tenant serves, the instance level ANDed with the tenant's along the path (server-computed). */
export async function getTenantCapabilities(tenantId: string): Promise<string[]> {
  return (await tenantsApi().getTenantCapabilities({ tenantId })).capabilities.map((c) => c.id)
}

/** Owner-only: the entries a tenant may switch, with the catalog's words and both levels' state. */
export function listTenantCapabilitySwitches(tenantId: string): Promise<TenantCapabilitySwitch[]> {
  return tenantsApi().listTenantCapabilitySwitches({ tenantId })
}

/** Owner-only: switch one entry off or back on for this tenant, and resolve to the entry as it now stands. */
export function setTenantCapabilitySwitch(tenantId: string, capabilityId: string, off: boolean): Promise<TenantCapabilitySwitch> {
  return tenantsApi().setTenantCapabilitySwitch({ tenantId, capabilityId, tenantCapabilitySwitchUpdate: { off } })
}

/** A language the catalog writes every entry's words in; any other locale reads the English. */
export function inLocale(text: CapabilityText, locale: string): string {
  return locale === 'de' ? text.de : text.en
}

/**
 * Where an entry stands, as the tenant owner reads it:
 *   - `on`         — on at both levels, for it and every ancestor;
 *   - `off`        — the tenant's own switch is off;
 *   - `offThrough` — its own switch is on, but an ancestor's (the tenant's) is off; `through` is the NEAREST one;
 *   - `serverOff`  — the Server Owner has it, or an ancestor, off: the tenant's switch cannot bring it back.
 * The tenant's own `off` comes first, because it is the one the owner can change here.
 */
export type TenantCapabilityState =
  | { kind: 'on' }
  | { kind: 'off' }
  | { kind: 'offThrough'; through: TenantCapabilitySwitch }
  | { kind: 'serverOff' }

export type TenantCapabilityNode = { entry: TenantCapabilitySwitch; depth: number; state: TenantCapabilityState }

/**
 * The entries as a tree, in the served order (a parent before its children, so a flat list with a depth IS the tree;
 * the id is the path). The state follows the server's `effective` and `serverOff`, never an AND recomputed here — the
 * client only names which ancestor an entry is off through. Ported from admin-web's `capabilityTree` (the Server
 * Owner's register), which this package cannot import.
 */
export function tenantCapabilityTree(entries: TenantCapabilitySwitch[]): TenantCapabilityNode[] {
  const byId = new Map(entries.map((e) => [e.id, e]))
  return entries.map((entry) => {
    const segments = entry.id.split('.')
    const ancestors: TenantCapabilitySwitch[] = []
    for (let n = segments.length - 1; n >= 2; n -= 1) {
      const ancestor = byId.get(segments.slice(0, n).join('.'))
      if (ancestor) ancestors.push(ancestor)
    }
    return { entry, depth: ancestors.length, state: stateOf(entry, ancestors) }
  })
}

function stateOf(entry: TenantCapabilitySwitch, ancestors: TenantCapabilitySwitch[]): TenantCapabilityState {
  if (entry.off) return { kind: 'off' }
  if (entry.effective) return { kind: 'on' }
  if (entry.serverOff) return { kind: 'serverOff' }
  const through = ancestors.find((a) => a.off)
  return through ? { kind: 'offThrough', through } : { kind: 'off' }
}

/** Whether any entry hangs below this one — its descendants' `effective` changes when it is switched. */
export function hasDescendants(id: string, entries: TenantCapabilitySwitch[]): boolean {
  return entries.some((e) => e.id.startsWith(`${id}.`))
}
