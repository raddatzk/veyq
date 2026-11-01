// The tenant level of the capability register (uc.admin.tenant-switch-capabilities, ADR 0088 decisions 2, 3, 12) — a
// tenant-OWNER surface beside the mail settings and terms, in `src/web/` for the same reason: the switches are gated with
// `403 not_tenant_owner`, and the owner is an ordinary member of their own tenant (ADR 0065). The effective set is
// every member's.
import { tenantsApi } from '../../api/client'
import type { CapabilityText, TenantCapabilities, TenantCapabilitySwitch } from '../../api/generated/tenants'

export type { CapabilityText, TenantCapabilityRequirement, TenantCapabilitySwitch } from '../../api/generated/tenants'

/** Any member: the entries this tenant serves, the instance level ANDed with the tenant's along the path, each with the
 *  requirement values in effect (the strictest of both levels), and the day each switched-off application is erased
 *  (`applicationErasures`) — all server-computed. */
export function getTenantCapabilities(tenantId: string): Promise<TenantCapabilities> {
  return tenantsApi().getTenantCapabilities({ tenantId })
}

/** Owner-only: the entries a tenant may switch, with the catalog's words and both levels' state. */
export function listTenantCapabilitySwitches(tenantId: string): Promise<TenantCapabilitySwitch[]> {
  return tenantsApi().listTenantCapabilitySwitches({ tenantId })
}

/** Owner-only: switch one entry off or back on for this tenant, and resolve to the entry as it now stands. */
export function setTenantCapabilitySwitch(tenantId: string, capabilityId: string, off: boolean): Promise<TenantCapabilitySwitch> {
  return tenantsApi().setTenantCapabilitySwitch({ tenantId, capabilityId, tenantCapabilitySwitchUpdate: { off } })
}

/** Owner-only: set one requirement for this tenant — `1` for a flag, else a number of days (ADR 0088 decision 8). The
 *  value in effect is the strictest of the tenant's and the server's; the answer says which it is. */
export function setTenantCapabilityRequirement(
  tenantId: string, capabilityId: string, requirement: string, value: number,
): Promise<TenantCapabilitySwitch> {
  return tenantsApi().setTenantCapabilityRequirement({
    tenantId, capabilityId, requirement, tenantCapabilityRequirementUpdate: { value },
  })
}

/** Owner-only: clear the tenant's value of one requirement; the server's, if any, stays in effect. */
export function clearTenantCapabilityRequirement(tenantId: string, capabilityId: string, requirement: string): Promise<TenantCapabilitySwitch> {
  return tenantsApi().clearTenantCapabilityRequirement({ tenantId, capabilityId, requirement })
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
    // Every dotted prefix that is an entry, a one-segment one included: an application (`memories`) is the parent of
    // its functions (`memories.places`), as in the backend's `selfAndAncestors`; `core` is no entry, so no parent.
    for (let n = segments.length - 1; n >= 1; n -= 1) {
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

/**
 * What an application's row tells its tenant owner about the day its content is erased
 * (uc.platform.erase-switched-off-application), or null when it names none:
 *   - `running`  — the day has come: the erasure is committed and no switch cancels it, so the row says it runs;
 *   - `byServer` — the instance holds the application off: only whoever runs the server can switch it on in time;
 *   - `day`      — switching it on here before the day cancels the erasure.
 * The day is the server's (`erasureDueAt`); an entry in effect names no coming day, because switching on before the day
 * already ended it. Only what is made after a switch-on past the day stays, so no state promises more than `running`.
 */
export type ErasureNote = { kind: 'running' | 'byServer' | 'day'; day: Date }

export function erasureNote(entry: TenantCapabilitySwitch, now: Date): ErasureNote | null {
  const day = entry.erasureDueAt
  if (!day) return null
  if (day.getTime() <= now.getTime()) return { kind: 'running', day }
  if (entry.effective) return null
  return { kind: entry.serverOff ? 'byServer' : 'day', day }
}

/** Whether any entry hangs below this one — its descendants' `effective` changes when it is switched. */
export function hasDescendants(id: string, entries: TenantCapabilitySwitch[]): boolean {
  return entries.some((e) => e.id.startsWith(`${id}.`))
}
