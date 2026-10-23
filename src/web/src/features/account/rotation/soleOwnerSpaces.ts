// The shared spaces where this account is the only owner (uc.account.rotate-keys E4, inbox #519): a rotation queues
// their new lock for another owner (ADR 0060 Decision 4 — the rotating account is excluded from its own job), and there
// is none, so they keep the old lock. The panel lists them before the button. Derived from what the client already
// reads — the active tenant's scope rows and each one's member list — never a server field.
import { ScopeKind, ScopeMemberRoleEnum, ScopeSummaryRoleEnum } from '../../../api/generated/scopes'
import { listScopeMembers } from '../../spaces/members'
import { listMyGroups, listMyScopes, listMyTenants } from '../../spaces/scopes'

/** A space this account owns alone while someone else is in it; `name` is null where this device cannot open it. */
export type SoleOwnerSpace = { scopeId: string; name: string | null }

/**
 * Every live shared space (the tenant included) of the active tenant where this account is an owner, nobody else is,
 * and at least one other member is in it. A space this account is alone in is not listed: the rotation re-keys that one
 * itself. A member list that does not load leaves its space out — the list is a warning, and a read that fails says
 * nothing either way.
 */
export async function soleOwnerSpaces(): Promise<SoleOwnerSpace[]> {
  const owned = (await listMyScopes()).filter(
    (s) => s.role === ScopeSummaryRoleEnum.Owner && s.kind !== ScopeKind.Personal && !s.deletedAt,
  )
  if (!owned.length) return []
  const [groups, tenants] = await Promise.all([listMyGroups().catch(() => []), listMyTenants().catch(() => [])])
  const groupNames = new Map(groups.map((g) => [g.scopeId, g.name]))
  const tenantNames = new Map(tenants.map((tn) => [tn.tenantId, tn.name]))
  const rows = await Promise.all(
    owned.map(async (s): Promise<SoleOwnerSpace | null> => {
      const members = await listScopeMembers(s.id).catch(() => null)
      if (!members || members.length < 2) return null
      if (members.filter((m) => m.role === ScopeMemberRoleEnum.Owner).length !== 1) return null
      const name = s.kind === ScopeKind.Tenant ? tenantNames.get(s.tenantId) : groupNames.get(s.id)
      return { scopeId: s.id, name: name || null }
    }),
  )
  return rows.filter((r): r is SoleOwnerSpace => r !== null)
}
