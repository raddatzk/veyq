// A member's name and picture across a tenant rotation, live (VEYQ_E2E=1; ADR 0115 decisions 1-2, inbox #573): against a
// REAL backend + MariaDB + the bundled store, with REAL crypto and the real readers. Four actors on one instance,
// serialized (`become()` swaps the session and the vault):
//   A = tenant owner; sets a name and a picture before the rotation, under the tenant's epoch 0.
//   B = a member who secures the account: the publish and its finish mint the tenant's epoch 1 (ADR 0111 decision 8),
//       whose commit re-seals every profile under the new key.
//   C = a member who set a name before the rotation and never comes back.
//   D = a newcomer, onboarded after the rotation.
//
// What only this layer proves: that the server's rotation really swaps every profile row for the one the rotator re-sealed
// under the new key — so every reader, the newcomer included, holds that one key and reads every name and picture under it
// — that nothing of a profile is left under the replaced key, and that the owner's own client writes nothing to get
// there. The unit layer (`core/people/profileAcrossRotation.test.ts`) proves the same readers against a faked transport.
//
//   VEYQ_INFRA_DIR=<main checkout>/infra/docker VEYQ_JAR=<jar> python3 scripts/run_web_e2e.py \
//     src/web/src/features/account/profile/profileAcrossRotation.e2e.test.ts
import { beforeAll, describe, it, expect } from 'vitest'
import { effectScope } from 'vue'
import { birthInstance, become, mkActor, onboardMember } from '../../../test/e2eBootstrap'
import { recordCanaries } from '../../../test/canaries'
import { registerMemoriesLifecycle } from '../../../test/memoriesLifecycle'
import { listMyScopes, openScope } from '../../spaces/scopes'
import { listScopeMembers } from '../../spaces/members'
import { ScopeKind } from '../../../api/generated/scopes'
import { mintSecretKey, rotateKeys } from '../rotation/rotateKeys'
import { avatarUrlOf, loadTenantPeople, refreshPeopleDirectory, resetPeopleDirectory } from '../../../core/people/directory'
import { getMyProfile, openOwnNameKey, sealAvatar, sealName, setMyAvatar, unsealName, updateMyProfile } from './profile'
import { openAvatar } from './memberAvatar'
import { useNameAndPicture } from './useNameAndPicture'
import { tenantsApi } from '../../../api/client'

// `rotateKeys` fails closed without a registered `onMasterKeyRotated` (a node file gets no registry): the real Memories one.
beforeAll(() => registerMemoriesLifecycle())

const A_NAME = { firstName: 'Annegret', lastName: 'Vorderwuelbecke' }
const C_NAME = { firstName: 'Cornelius', lastName: 'Haselgruber' }
// A tiny PNG (the header `sniffImage` recognises, then filler), padded to the avatar's constant length by `sealAvatar`.
const PICTURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...Array.from({ length: 64 }, (_, i) => (i * 37) & 0xff)])

/** Set the signed-in member's name (and picture) under the tenant key its grant opens now, stating its epoch (ADR 0115
 *  decision 3). Returns a copy of that key — the test's oracle for "under the replaced key". */
async function saveProfile(name: { firstName: string; lastName: string }, picture?: Uint8Array): Promise<Uint8Array> {
  const own = (await openOwnNameKey())!
  await updateMyProfile({ sealedName: sealName(own.key, own.address, name), epoch: own.epochs.epoch })
  if (picture) await setMyAvatar(sealAvatar(own.key, own.address, picture), own.epochs.epoch)
  return own.key.slice()
}

const fullName = (n: { firstName: string; lastName: string }) => `${n.firstName} ${n.lastName}`

describe.skipIf(!process.env.VEYQ_E2E)('a member\'s name and picture across a tenant rotation (inbox #573, live, real crypto)', () => {
  it('after a rotation every member reads every name and picture under the new key, because the rotation re-sealed them; under the old key nothing opens', async () => {
    // --- the instance: A owns the tenant, B and C join; A and C save their profiles under epoch 0 -------------------------
    const { owner: A, tenant, salt } = await birthInstance('E2E Profile Across Rotation')
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)
    recordCanaries(fullName(A_NAME), fullName(C_NAME))
    await become(A)
    const E0 = await saveProfile(A_NAME, PICTURE)
    await become(C)
    await saveProfile(C_NAME)
    expect((await listMyScopes()).find((s) => s.kind === ScopeKind.Tenant)?.currentEpoch).toBe(0)

    // --- B secures the account: the tenant moves to epoch 1, and its commit re-seals every profile row -------------------
    await become(B)
    await rotateKeys(B.password, B.built.emergencyKit.secretKey, { secretKeyB64: mintSecretKey() })
    const tenantRow = (await listMyScopes()).find((s) => s.kind === ScopeKind.Tenant)
    expect(tenantRow?.currentEpoch, 'securing the account rotated the tenant').toBe(1)
    const heldB = (await openScope(ScopeKind.Tenant))!
    expect(heldB.epochs.epoch).toBe(1)
    const aAddress = { scopeId: heldB.scopeId, userId: A.userId! }
    const aRow = (await listScopeMembers(heldB.scopeId)).find((m) => m.userId === A.userId)?.sealedName
    expect(unsealName(heldB.epochs.key, aAddress, aRow), 'A\'s row was re-sealed under epoch 1').toEqual(A_NAME)
    expect(unsealName(E0, aAddress, aRow), 'and nothing of it is left under epoch 0').toBeNull()

    // --- B (the rotator, its new identity) reads A's and C's names and A's picture -----------------------------------
    resetPeopleDirectory()
    const seenByB = await loadTenantPeople()
    expect(seenByB.get(A.userId!)?.displayName).toBe(fullName(A_NAME))
    expect(seenByB.get(C.userId!)?.displayName).toBe(fullName(C_NAME))
    await refreshPeopleDirectory()
    expect(avatarUrlOf(A.userId!), 'A\'s picture, re-sealed by the rotation').not.toBeNull()

    // --- A: the own profile editor reads the own name; the stored name and picture are under epoch 1 already, without
    //     any write of A's own client -----------------------------------------------------------------------------------
    await become(A)
    resetPeopleDirectory()
    const model = effectScope().run(() => useNameAndPicture())!
    await model.load()
    expect([model.firstName, model.lastName], 'the profile is not empty after the rotation').toEqual([A_NAME.firstName, A_NAME.lastName])
    const heldA = (await openScope(ScopeKind.Tenant))!
    const address = { scopeId: heldA.scopeId, userId: A.userId! }
    const stored = await getMyProfile()
    expect(unsealName(heldA.epochs.key, address, stored.sealedName), 'the name is under epoch 1').toEqual(A_NAME)
    expect(unsealName(E0, address, stored.sealedName)).toBeNull()
    const picture = await tenantsApi().getMemberAvatar({ tenantId: tenant.tenantId, userId: A.userId! })
    expect(openAvatar(heldA.epochs.key, address, picture.sealedAvatar), 'the picture is under epoch 1').toEqual(PICTURE)
    expect(openAvatar(E0, address, picture.sealedAvatar)).toBeNull()

    // --- D joins after the rotation: it holds epoch 1's key alone, and reads A's and C's names under it ---------------
    const D = await onboardMember(tenant.tenantId, mkActor('d'), salt)
    await become(D)
    resetPeopleDirectory()
    const heldD = (await openScope(ScopeKind.Tenant))!
    expect(heldD.epochs.epoch, 'D holds the current epoch').toBe(1)
    const seenByD = await loadTenantPeople()
    expect(seenByD.get(A.userId!)?.displayName).toBe(fullName(A_NAME))
    expect(seenByD.get(C.userId!)?.displayName, 'C never came back: the rotation re-sealed its name too').toBe(fullName(C_NAME))
  }, 360_000)
})
