// A member's name and picture across a tenant rotation, live (VEYQ_E2E=1; ADR 0023 § The epoch of a sealed name, inbox
// #573): against a REAL backend + MariaDB + the bundled store, with REAL crypto and the real readers. Four actors on one
// instance, serialized (`become()` swaps the session and the vault):
//   A = tenant owner; sets a name and a picture before the rotation, under the tenant's epoch 0.
//   B = a member who secures the account: the publish and its finish mint the tenant's epoch 1 (ADR 0111 decision 8).
//   C = a member who set a name before the rotation and never comes back.
//   D = a newcomer, onboarded after the rotation.
//
// What only this layer proves: that the server's rotation really leaves the profile rows under epoch 0 while every reader
// holds epoch 1, that the readers open them anyway, that the owner's own client moves its row forward through the real
// profile endpoints, and that a newcomer reads exactly what its grant's peeled chain opens. The unit layer
// (`core/people/profileAcrossRotation.test.ts`, `features/account/profile/carryProfileForward.test.ts`) proves the same
// readers and the step against a faked transport.
//
//   VEYQ_INFRA_DIR=<main checkout>/infra/docker VEYQ_JAR=<jar> python3 scripts/run_web_e2e.py \
//     src/web/src/features/account/profile/profileAcrossRotation.e2e.test.ts
import { beforeAll, describe, it, expect } from 'vitest'
import { effectScope } from 'vue'
import { birthInstance, become, mkActor, onboardMember } from '../../../test/e2eBootstrap'
import { recordCanaries } from '../../../test/canaries'
import { registry } from '../../../core/applications/registry'
import { memoriesLifecycle } from '../../memories/lifecycle'
import { listMyScopes, openScope } from '../../spaces/scopes'
import { listScopeMembers } from '../../spaces/members'
import { ScopeKind } from '../../../api/generated/scopes'
import { mintSecretKey, rotateKeys } from '../rotation/rotateKeys'
import { avatarUrlOf, loadTenantPeople, refreshPeopleDirectory, resetPeopleDirectory } from '../../../core/people/directory'
import { getMyProfile, openOwnNameKey, sealAvatar, sealName, setMyAvatar, unsealName, updateMyProfile } from './profile'
import { openAvatar } from './memberAvatar'
import { useNameAndPicture } from './useNameAndPicture'
import { carryProfileForward } from './carryProfileForward'
import { tenantsApi } from '../../../api/client'

// `rotateKeys` fails closed without a registered `onMasterKeyRotated` (a node file gets no registry): the real Memories one.
beforeAll(() => registry.registerLifecycle('memories', memoriesLifecycle))

const A_NAME = { firstName: 'Annegret', lastName: 'Vorderwuelbecke' }
const C_NAME = { firstName: 'Cornelius', lastName: 'Haselgruber' }
// A tiny PNG (the header `sniffImage` recognises, then filler), padded to the avatar's constant length by `sealAvatar`.
const PICTURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...Array.from({ length: 64 }, (_, i) => (i * 37) & 0xff)])

/** Set the signed-in member's name (and picture) under the tenant key its grant opens now. */
async function saveProfile(name: { firstName: string; lastName: string }, picture?: Uint8Array): Promise<void> {
  const own = (await openOwnNameKey())!
  await updateMyProfile({ sealedName: sealName(own.key, own.address, name) })
  if (picture) await setMyAvatar(sealAvatar(own.key, own.address, picture))
}

const fullName = (n: { firstName: string; lastName: string }) => `${n.firstName} ${n.lastName}`

describe.skipIf(!process.env.VEYQ_E2E)('a member\'s name and picture across a tenant rotation (inbox #573, live, real crypto)', () => {
  it('every member still reads every name and picture after a rotation, the owner\'s client moves its own forward, a newcomer reads what its chain opens', async () => {
    // --- the instance: A owns the tenant, B and C join; A and C save their profiles under epoch 0 -------------------------
    const { owner: A, tenant, salt } = await birthInstance('E2E Profile Across Rotation')
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)
    recordCanaries(fullName(A_NAME), fullName(C_NAME))
    await become(A)
    await saveProfile(A_NAME, PICTURE)
    await become(C)
    await saveProfile(C_NAME)
    expect((await listMyScopes()).find((s) => s.kind === ScopeKind.Tenant)?.currentEpoch).toBe(0)

    // --- B secures the account: the tenant moves to epoch 1, the profile rows stay as they were --------------------------
    await become(B)
    await rotateKeys(B.password, B.built.emergencyKit.secretKey, { secretKeyB64: mintSecretKey() })
    const tenantRow = (await listMyScopes()).find((s) => s.kind === ScopeKind.Tenant)
    expect(tenantRow?.currentEpoch, 'securing the account rotated the tenant').toBe(1)
    const heldB = (await openScope(ScopeKind.Tenant))!
    const aRow = (await listScopeMembers(heldB.scopeId)).find((m) => m.userId === A.userId)?.sealedName
    expect(unsealName(heldB.epochs.keys.get(1)!, { scopeId: heldB.scopeId, userId: A.userId! }, aRow), 'A\'s row is still under epoch 0').toBeNull()

    // --- B (the rotator, its new identity) reads A's and C's names and A's picture -----------------------------------
    resetPeopleDirectory()
    const seenByB = await loadTenantPeople()
    expect(seenByB.get(A.userId!)?.displayName).toBe(fullName(A_NAME))
    expect(seenByB.get(C.userId!)?.displayName).toBe(fullName(C_NAME))
    await refreshPeopleDirectory()
    expect(avatarUrlOf(A.userId!), 'A\'s picture from before the rotation').not.toBeNull()

    // --- A: the own profile editor reads the own name, and the own client moves name and picture to epoch 1 -----------
    await become(A)
    resetPeopleDirectory()
    const model = effectScope().run(() => useNameAndPicture())!
    await model.load()
    expect([model.firstName, model.lastName], 'the profile is not empty after the rotation').toEqual([A_NAME.firstName, A_NAME.lastName])
    const heldA = (await openScope(ScopeKind.Tenant))!
    expect(await carryProfileForward({ tenantId: heldA.tenantId, scopeId: heldA.scopeId, epochs: heldA.epochs }, A.userId!)).toEqual({ name: true, picture: true })
    const address = { scopeId: heldA.scopeId, userId: A.userId! }
    const stored = await getMyProfile()
    expect(unsealName(heldA.epochs.keys.get(1)!, address, stored.sealedName), 'the name is under epoch 1 now').toEqual(A_NAME)
    expect(unsealName(heldA.epochs.keys.get(0)!, address, stored.sealedName)).toBeNull()
    const picture = await tenantsApi().getMemberAvatar({ tenantId: tenant.tenantId, userId: A.userId! })
    expect(openAvatar(heldA.epochs.keys.get(1)!, address, picture.sealedAvatar), 'the picture is under epoch 1 now').toEqual(PICTURE)
    expect(openAvatar(heldA.epochs.keys.get(0)!, address, picture.sealedAvatar)).toBeNull()

    // --- D joins after the rotation: it reads A's name (epoch 1) and C's (epoch 0, peeled from its grant's chain) ------
    const D = await onboardMember(tenant.tenantId, mkActor('d'), salt)
    await become(D)
    resetPeopleDirectory()
    const heldD = (await openScope(ScopeKind.Tenant))!
    expect(heldD.epochs.current, 'D holds the current epoch').toBe(1)
    expect(heldD.epochs.keys.has(0), 'and the one before it, through the chain link').toBe(true)
    const seenByD = await loadTenantPeople()
    expect(seenByD.get(A.userId!)?.displayName).toBe(fullName(A_NAME))
    expect(seenByD.get(C.userId!)?.displayName).toBe(fullName(C_NAME))
  }, 360_000)
})
