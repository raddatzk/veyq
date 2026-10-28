// Live e2e (VEYQ_E2E=1): a member who DECLINED a group invitation and is then added again opens the group as soon as
// they accept the second one (uc.spaces.consent-group-add) — against a REAL backend + MariaDB with REAL crypto.
//
// Since ADR 0111 decision 4 a proposal holds the group's grant from the add on: the target could open the key, so a
// decline queues the group's rotation (ADR 0111 decision 4: the proposal held the group's key), which the owner's worker completes, and the second
// proposal carries a grant of the NEW epoch. Accepting changes nothing about keys and queues nothing — the control on
// the same worker call. First seen on a live walk (2026-10-16) as a re-added member declined by the old admission gate;
// that gate is gone, and what this suite keeps is the decline → re-add → accept path end to end.
// Run against a FRESH instance — `python3 scripts/run_web_e2e.py <this file>` does that.
import { describe, it, expect } from 'vitest'
import {
  acceptGroupInvitation, addGroupMember, declineGroupInvitation, listMyGroupInvitations, listScopeMembers, resolveGroupMember,
} from './members'
import { createGroup, listMyScopes, openScopeById, wrapGroupNameKeyForInvitee } from './scopes'
import { updateMyProfile } from '../account/profile/profile'
import { runScopeRotationWorker } from '../tasks/scopeRotationWorker'
import { become, birthInstance, mkActor, onboardMember, type Actor } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'

/** The owner's add as the members panel makes it for a consent-on target: resolve → seal the name-key → propose with
 *  the grant of the group's current epoch to the identity key the resolve served. */
async function propose(scopeId: string, who: Actor): Promise<void> {
  const candidate = await resolveGroupMember(scopeId, who.username)
  expect(candidate.requiresConsent, 'the target asked to be consulted').toBe(true)
  const sealedNameKey = await wrapGroupNameKeyForInvitee(scopeId, candidate.userId, candidate.identityPublicKey)
  await addGroupMember(scopeId, who.username, candidate.userId, candidate.identityPublicKey, sealedNameKey!)
}

describe.skipIf(!process.env.VEYQ_E2E)('consent group-add — re-adding a member who declined (live, real crypto)', () => {
  it('a decline rotates the group; the second invitation, once accepted, opens at once', async () => {
    const { tenant, salt } = await birthInstance('E2E Consent re-add')
    const S = await onboardMember(tenant.tenantId, mkActor('sam'), salt)
    const A = await onboardMember(tenant.tenantId, mkActor('alex'), salt)

    // A asks to be consulted before being added to a group.
    await become(A)
    await updateMyProfile({ requireGroupAddConsent: true })

    // S proposes A; A declines — the proposal held the group's key, so the decline queues the group's rotation.
    await become(S)
    const g = await createGroup('Gartenclub Süd')
    await propose(g, A)
    await become(A)
    expect((await listMyGroupInvitations()).map((i) => i.scopeId), 'A sees the first invitation').toEqual([g])
    await declineGroupInvitation(g)

    // S's device completes the queued rotation: the key A could have opened is retired.
    await become(S)
    expect(await runScopeRotationWorker(), 'the decline queued one rotation, S completes it').toBe(1)
    expect((await listMyScopes()).find((s) => s.id === g)?.currentEpoch, 'G moved past the epoch A was proposed at').toBe(1)

    // S proposes A again — at the new epoch; this time A accepts.
    await propose(g, A)
    expect((await listScopeMembers(g)).find((m) => m.userId === A.userId)?.pendingConsent, 'a proposal until A accepts').toBe(true)
    await become(A)
    expect((await listMyGroupInvitations()).map((i) => i.scopeId), 'A sees the second invitation').toEqual([g])
    await acceptGroupInvitation(g)

    // A opens the group at once — no worker ran for it: the proposal held the grant.
    const openedA = await openScopeById(g)
    expect(openedA?.name, 'A opens the group with the grant the second proposal carried').toBe('Gartenclub Süd')
    expect(openedA?.epochs.current, 'at the epoch after the decline\'s rotation').toBe(1)

    // Control on the same worker call: an accept queues nothing.
    await become(S)
    expect(await runScopeRotationWorker(), 'an accepted proposal queues no rotation').toBe(0)

    // The wire assertion's derivation (ADR 0053): the one plaintext this flow sealed, as the member recovered it.
    recordCanaries(openedA!.name)
  }, 240_000)
})
