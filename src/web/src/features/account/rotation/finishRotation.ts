// uc.account.rotate-keys (web), the half after the publish — cr-secure-the-account, ADR 0111 decision 8.
//
// The publish granted every scope the account holds a grant on, at its CURRENT epoch, to the new identity, and wrote a
// pending re-key row per scope. From that moment nothing can be lost; what is left is to close the old identity out of
// what comes next: the account mints each such scope's next epoch itself, from any device of it that unlocks — the
// rotating tab right after its publish, and every device's scope-rotation worker on start and every 45 s while unlocked
// (`tasks/scopeRotationWorker.ts`), one opened from the new Emergency Kit included. No old key is needed for it: the
// vault's (published) identity opens the self-grant the publish wrote, whatever tenant the scope lives in.
import { accountRotationApi } from '../../../api/client'
import type { RotationPlanScope, RotationScopeState } from '../../../api/generated/account'
import { withRefresh } from '../../../app/sessionRefresh'
import { identityPrivate } from '../../../app/vault'
import { rekeyScope } from '../../../core/node/rekey/rekeyScope'
import { listScopeMembers } from '../../spaces/members'
import { commitRotation, EpochMovedOn, isStaleEpoch, planOpener } from '../../spaces/scopes'

/**
 * Where every scope of this account's last rotation stands, as the server derives it (decision 8): `waiting` (its next
 * epoch is not in yet), `rekeying`, `secured`, or `blocked` with a reason. Host-independent; empty for an account that
 * never rotated.
 */
export function listRotationStates(): Promise<RotationScopeState[]> {
  return withRefresh(() => accountRotationApi().listRotationScopeStates())
}

/**
 * The account rotation's plan (decision 5): every scope the account holds a membership on, in every tenant, with the
 * caller's own grant row — sorted by scope id. Host-independent.
 */
export function readRotationPlan(): Promise<RotationPlanScope[]> {
  return withRefresh(() => accountRotationApi().getAccountRotationPlan())
}

let running: Promise<RotationScopeState[]> | null = null

/**
 * Commit the next epoch of every scope this account's rotation left `waiting`, then answer the states as they read
 * after. One run at a time per tab: the rotating tab and the worker may ask together, and the second waits for the
 * first rather than racing it into a `stale_epoch`.
 *
 * Per waiting scope: its plan row's grant — the self-grant the publish wrote — is opened with the vault's identity
 * ({@link planOpener}), the members are read now (whoever is a member then is granted, one who joined after the publish
 * included; an anomaly is declared pending by `commitRotation`), and the epoch is committed. A `409 stale_epoch`, or a
 * rebuild that finds the epoch already moved, is someone else's commit: it could not grant the old identity (the server
 * refuses a grant to a retired key, ADR 0111 decision 10), so the row's purpose is met and it reads `secured` or
 * `rekeying`. Any other failure leaves the row `waiting` for the next pass — the self-grant still opens, so nothing is
 * lost meanwhile. A committed scope's nodes are re-keyed best-effort (ADR 0109; any member finishes it, decision 11).
 *
 * Needs an unlocked vault holding the PUBLISHED identity: a locked one finishes nothing and answers no states.
 */
export function finishRotation(): Promise<RotationScopeState[]> {
  running ??= finishOnce().finally(() => {
    running = null
  })
  return running
}

async function finishOnce(): Promise<RotationScopeState[]> {
  const unlocked = identityPrivate()
  if (!unlocked) return []
  unlocked.kemSeed.fill(0)
  unlocked.sign.fill(0)
  const states = await listRotationStates()
  const waiting = states.filter((s) => s.state === 'waiting').map((s) => s.scopeId)
  if (!waiting.length) return states
  const plan = await readRotationPlan()
  const reread = (scopeId: string) => async () => (await readRotationPlan()).find((r) => r.scopeId === scopeId)
  for (const scopeId of waiting) {
    const row = plan.find((r) => r.scopeId === scopeId)
    if (!row || row.blocked) continue
    try {
      const members = await listScopeMembers(scopeId)
      // The scope's tenant is read through the plan's own TENANT row, so a scope in any tenant is judged in its tenant.
      // Read afresh at each open, never the row of the pass's first read: the TENANT scope is usually committed earlier
      // in this same pass, which raises this device's epoch floor past the self-grant that row carries — opened from it,
      // the tenant would read `unavailable` and every member of every later group would be declared pending.
      const openTenant = async (tenantId: string) => {
        const tenantRow = await reread(tenantId)()
        return tenantRow && !tenantRow.blocked ? planOpener(tenantRow, async () => tenantRow)(tenantId) : null
      }
      await commitRotation(scopeId, members, { open: planOpener(row, reread(scopeId)), openTenant })
    } catch (e) {
      if (!(e instanceof EpochMovedOn) && !(await isStaleEpoch(e))) continue // stays waiting; the next pass retries
    }
    await rekeyScope(scopeId).catch(() => undefined)
  }
  return listRotationStates().catch(() => states)
}
