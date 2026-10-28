// uc.account.rotate-keys (web) — what the ceremony's entry says before any field (cr-secure-the-account).
// Read from the account rotation's plan (decision 5), which serves every scope the account holds a membership on in every
// tenant, Host-independently: which spaces cannot get their new lock yet and why, and whether this account signed open
// invitations its rotation ends (§ Order, `factInvites`). Under ADR 0111 no space waits for an owner, so nothing here
// asks who owns what (the former sole-owner warning went with the mechanism it described).
import { RotationPlanScopeKindEnum, type RotationPlanScope } from '../../../api/generated/account'
import { scopesApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { account } from '../../../app/account'
import { readRotationPlan } from './finishRotation'
import { unreadableOnThisDevice } from './rotateKeys'

/** Why a space cannot get its new lock now: the plan's own reasons, or that this device cannot open its grant. */
export type EntryBlockReason = 'unreadable' | 'tenant_suspended' | 'scope_deleting'

/** A space the entry names under "These spaces can't get their new lock yet". */
export type EntryBlocked = { scopeId: string; kind: RotationPlanScope['kind']; tenantId: string; reason: EntryBlockReason }

export type RotationEntry = {
  /** The plan as read — the panel names its rows (kind, tenant) and the links' spaces from it. */
  plan: RotationPlanScope[]
  blocked: EntryBlocked[]
  /** This account signed at least one open member invitation in a tenant it is in: those die with its rotation
   *  (§ Order — an owner's open invites do not survive the owner's rotation). */
  signedOpenInvites: boolean
}

/**
 * The entry's facts. A space this device cannot open (judged by the stage's own rule, a dry run that writes nothing) is
 * already closed to the account — `unreadable` wins over a plan reason, because it does not pass. A read that fails
 * leaves that part empty rather than guessing: the entry is information, and the publish judges for itself. `read` is a
 * plan the caller already holds (the panel names its rows from it before the dry run is done).
 */
export async function readRotationEntry(read?: RotationPlanScope[]): Promise<RotationEntry> {
  const plan = read ?? (await readRotationPlan())
  const unreadable = new Set(await unreadableOnThisDevice(plan).catch((): string[] => []))
  const blocked: EntryBlocked[] = []
  for (const row of plan) {
    const reason: EntryBlockReason | undefined = unreadable.has(row.scopeId) ? 'unreadable' : row.blocked ?? undefined
    if (reason) blocked.push({ scopeId: row.scopeId, kind: row.kind, tenantId: row.tenantId, reason })
  }
  return { plan, blocked, signedOpenInvites: await signedOpenInvites(plan) }
}

/** Whether an open member invite of a tenant this account is in was signed by this account (`signerUserId`). Every
 *  member reads a tenant's open invites (`listOpenInvites`, no email); a tenant whose read fails counts as none. */
async function signedOpenInvites(plan: RotationPlanScope[]): Promise<boolean> {
  const me = account.userId
  if (!me) return false
  const tenants = plan.filter((r) => r.kind === RotationPlanScopeKindEnum.Tenant)
  const signed = await Promise.all(
    tenants.map((r) =>
      withRefresh(() => scopesApi().listOpenInvites({ scopeId: r.scopeId }))
        .then((invites) => invites.some((i) => i.signerUserId === me))
        .catch(() => false),
    ),
  )
  return signed.some(Boolean)
}
