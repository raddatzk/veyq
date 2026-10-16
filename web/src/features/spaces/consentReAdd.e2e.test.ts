// Live e2e (VEYQ_E2E=1): a member who DECLINED a group invitation and is then added again gets the group key once
// they accept the second one (uc.spaces.consent-group-add) — against a REAL backend + MariaDB with REAL crypto.
//
// Seen on a live walk (2026-10-16) as "a re-added member is declined `admission_unverified`". This suite held green
// before the fix: the decline, the re-add and its fresh admission are sound end to end. The cause was the worker
// reading the active space's key while leaving the space zeroed it, pinned in `grantDeliveryWorker.test.ts`.
// Run against a FRESH instance — `python3 scripts/run_web_e2e.py <this file>` does that.
import { describe, it, expect, vi } from 'vitest'
import { TasksApi } from '../../api/generated/tasks'
import {
  acceptGroupInvitation, addGroupMember, declineGroupInvitation, listMyGroupInvitations, listScopeMembers, resolveGroupMember,
} from './members'
import { createGroup, openScopeById, wrapGroupNameKeyForInvitee } from './scopes'
import { updateMyProfile } from '../account/profile/profile'
import { runGrantDeliveryWorker } from '../tasks/grantDeliveryWorker'
import { become, birthInstance, mkActor, onboardMember, type Actor } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ScopeKind } from '../../api/generated/scopes'

/** The owner's add as the members panel makes it for a consent-on target: resolve → seal the name-key → propose. */
async function propose(scopeId: string, who: Actor): Promise<void> {
  const candidate = await resolveGroupMember(scopeId, who.username)
  expect(candidate.requiresConsent, 'the target asked to be consulted').toBe(true)
  const sealedNameKey = await wrapGroupNameKeyForInvitee(scopeId, candidate.userId, candidate.identityPublicKey)
  await addGroupMember(scopeId, who.username, candidate.userId, sealedNameKey!)
}

describe.skipIf(!process.env.VEYQ_E2E)('consent group-add — re-adding a member who declined (live, real crypto)', () => {
  it('the second invitation, once accepted, is delivered to', async () => {
    const { tenant, salt } = await birthInstance('E2E Consent re-add')
    const S = await onboardMember(tenant.tenantId, mkActor('sam'), salt)
    const A = await onboardMember(tenant.tenantId, mkActor('alex'), salt)

    // A asks to be consulted before being added to a group.
    await become(A)
    await updateMyProfile({ requireGroupAddConsent: true })

    // S proposes A; A declines — the proposal is dropped, nothing was ever wrapped.
    await become(S)
    const g = await createGroup('Gartenclub Süd')
    await propose(g, A)
    await become(A)
    expect((await listMyGroupInvitations()).map((i) => i.scopeId), 'A sees the first invitation').toEqual([g])
    await declineGroupInvitation(g)

    // S proposes A again; this time A accepts.
    await become(S)
    await propose(g, A)
    await become(A)
    expect((await listMyGroupInvitations()).map((i) => i.scopeId), 'A sees the second invitation').toEqual([g])
    await acceptGroupInvitation(g)

    // S's device runs the worker, as the open group polls it — it must deliver, not decline.
    await become(S)
    const spy = vi.spyOn(TasksApi.prototype, 'declineTask')
    const opened = await openScopeById(g)
    const delivered = await runGrantDeliveryWorker({ scopeId: g, kind: ScopeKind.Group, key: opened!.key, epoch: opened!.epochs.current })
    const reasons = spy.mock.calls.map(([req]) => req.declineRequest?.reason)
    spy.mockRestore()
    expect(reasons, 'no job was declined').toEqual([])
    expect(delivered, 'A\'s grant was delivered').toBe(1)
    expect((await listScopeMembers(g)).find((m) => m.userId === A.userId)?.hasGrant, 'A holds the group key').toBe(true)

    // And A can open the group.
    await become(A)
    const openedA = await openScopeById(g)
    expect(openedA?.name, 'A opens the group with the delivered grant').toBe('Gartenclub Süd')

    // The wire assertion's derivation (ADR 0053): the one plaintext this flow sealed, as the member recovered it.
    recordCanaries(openedA!.name)
  }, 240_000)
})
