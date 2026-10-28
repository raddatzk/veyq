// ADR 0025 § 2 live (VEYQ_E2E=1): a tenant owner removes a member with "also give this space a new lock" ticked — the
// removal first, then the plain rotation of the TENANT over the members that remain (`rekeySpace`) and the node re-key,
// as `TenantMembersPanel` runs them — against a REAL backend + MariaDB + the bundled store, with REAL crypto. Four actors
// on one instance, serialized (`become()` swaps the session and the vault):
//   A = tenant owner; the remover and the rotator.
//   B = a member who stays; saved a name and a picture under epoch 0.
//   C = a member A removes; saved a name under epoch 0.
//   D = an open invite while A re-keys, accepted after.
//
// What only this layer proves: that the real server takes the owner's rotation right after the removal cascade (the
// directory read after it covers every membership), that the epoch moves only with the opt-in, that the remaining member
// and a newcomer still read every name, picture and node under the new epoch, and that the removed member is outside.
// The unit layer (`members.test.ts`, `TenantMembersPanel.test.ts`) and `TenantRekeySliceTest` prove the steps apart.
//
//   VEYQ_INFRA_DIR=<main checkout>/infra/docker VEYQ_JAR=<jar> python3 scripts/run_web_e2e.py \
//     src/web/src/features/spaces/tenantRekey.e2e.test.ts
import { beforeAll, describe, it, expect } from 'vitest'
import { birthInstance, become, mkActor, onboardMember } from '../../test/e2eBootstrap'
import { recordCanaries, recordPii } from '../../test/canaries'
import { mailCursor, waitForMail } from '../../test/mailpit'
import { memberInviteFragment, registerThroughMemberInvite } from '../../test/memberJoin'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { generateInviteCode } from '@veyq/crypto/invite'
import { registry } from '../../core/applications/registry'
import { memoriesLifecycle } from '../memories/lifecycle'
import { inviteMember, listScopeMembers, rekeySpace, removeTenantMember } from './members'
import { listMyScopes, openScope } from './scopes'
import { createFolder, listTree } from '../../core/node/tree'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { avatarUrlOf, loadTenantPeople, refreshPeopleDirectory, resetPeopleDirectory } from '../../core/people/directory'
import { openOwnNameKey, sealAvatar, sealName, setMyAvatar, updateMyProfile } from '../account/profile/profile'
import { scopesApi } from '../../api/client'
import { ScopeKind } from '../../api/generated/scopes'
import { decodeHttpError } from '../../shared/httpError'

beforeAll(() => registry.registerLifecycle('memories', memoriesLifecycle))

const A_NAME = { firstName: 'Hildegunde', lastName: 'Ostermeier' }
const B_NAME = { firstName: 'Bartholomaeus', lastName: 'Kleinschmidt' }
const C_NAME = { firstName: 'Cosima', lastName: 'Wendelstein' }
const FOLDER_NAME = 'Sommerfest Tegernsee'
// A tiny PNG (the header `sniffImage` recognises, then filler), padded to the avatar's constant length by `sealAvatar`.
const PICTURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...Array.from({ length: 64 }, (_, i) => (i * 41) & 0xff)])

/** Set the signed-in member's name (and picture) under the tenant key its grant opens now. */
async function saveProfile(name: { firstName: string; lastName: string }, picture?: Uint8Array): Promise<void> {
  const own = (await openOwnNameKey())!
  await updateMyProfile({ sealedName: sealName(own.key, own.address, name) })
  if (picture) await setMyAvatar(sealAvatar(own.key, own.address, picture))
}

const fullName = (n: { firstName: string; lastName: string }) => `${n.firstName} ${n.lastName}`
const tenantRow = async () => (await listMyScopes()).find((s) => s.kind === ScopeKind.Tenant)

describe.skipIf(!process.env.VEYQ_E2E)('a tenant owner gives the space a new lock after a removal (ADR 0025 § 2, live, real crypto)', () => {
  it('the removal lands first, the tenant moves to the next epoch without the removed member, and who remains reads on', async () => {
    // --- the instance: A owns the tenant, B and C join, each saves a name; A keeps a folder in the tenant ---------------
    const { owner: A, tenant, salt } = await birthInstance('E2E Tenant Rekey')
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)
    recordCanaries(fullName(A_NAME), fullName(B_NAME), fullName(C_NAME), FOLDER_NAME)
    await become(B)
    await saveProfile(B_NAME, PICTURE)
    await become(C)
    await saveProfile(C_NAME)
    await become(A)
    await saveProfile(A_NAME)
    const tenantScopeId = (await tenantRow())!.id
    const opened = (await openScope(ScopeKind.Tenant))!
    const folderId = await createFolder(tenantScopeId, opened.epochs, FOLDER_NAME)

    // --- D: an invite still open when A re-keys; its link and code are kept for later ---------------------------------
    const D = mkActor('d')
    D.built = await buildAccount(recordPii(D.email), D.password, salt)
    const cursor = await mailCursor(D.email)
    const code = generateInviteCode()
    await inviteMember(tenant.tenantId, D.email, { mode: 'code', code })
    const fragment = memberInviteFragment(await waitForMail(D.email, { after: cursor }))!
    expect(fragment, 'a member invite link in the mail to D').toBeTruthy()

    // --- A removes C: the cascade alone moves no epoch (ADR 0025 § 2) -------------------------------------------------
    await removeTenantMember(tenant.tenantId, C.userId!)
    expect((await tenantRow())?.currentEpoch, 'a removal alone rotates nothing').toBe(0)

    // --- ... and, the opt-in ticked, A's device gives the tenant its new lock: the rotation, then the node re-key ------
    expect(await rekeySpace(tenantScopeId, C.userId)).toBe(1)
    expect((await rekeyScope(tenantScopeId)).done, 'every node of the tenant re-keyed on A\'s device').toBe(true)
    const rowA = (await tenantRow())!
    expect([rowA.currentEpoch, rowA.grantedEpoch, rowA.rekeying]).toEqual([1, 1, false])
    const remaining = await listScopeMembers(tenantScopeId)
    expect(remaining.map((m) => m.userId).sort()).toEqual([A.userId, B.userId].sort())
    expect(remaining.every((m) => !m.pending), 'B\'s key is anchored: granted, not declared pending').toBe(true)

    // --- B, who stays: holds epoch 1 and peels epoch 0 from it, reads every name, the picture and the folder -------------
    await become(B)
    resetPeopleDirectory()
    const heldB = (await openScope(ScopeKind.Tenant))!
    expect(heldB.epochs.current, 'B was granted the new epoch').toBe(1)
    expect(heldB.epochs.keys.has(0), 'and opens the one before it through the chain link').toBe(true)
    const seenByB = await loadTenantPeople()
    expect(seenByB.get(A.userId!)?.displayName).toBe(fullName(A_NAME))
    expect(seenByB.get(B.userId!)?.displayName).toBe(fullName(B_NAME))
    await refreshPeopleDirectory()
    expect(avatarUrlOf(B.userId!), 'B\'s picture from before the rotation').not.toBeNull()
    const treeB = await listTree(tenantScopeId, heldB.epochs)
    expect(treeB.find((n) => n.id === folderId)?.name).toBe(FOLDER_NAME)
    expect(treeB.find((n) => n.id === folderId)?.epoch, 'the folder carries the new epoch').toBe(1)

    // --- C, removed: outside the scope, no epoch chain to read ---------------------------------------------------------
    await become(C)
    const refused = await scopesApi().listScopeEpochs({ scopeId: tenantScopeId }).then(() => null, decodeHttpError)
    expect(refused?.status, 'the removed member reads no epoch of the tenant').toBe(404)

    // --- D accepts after the re-key: the invite was carried into epoch 1, and D reads the names from its first sign-in -
    const joined = await registerThroughMemberInvite(fragment, code, D.username, D.built)
    D.userId = joined.userId
    await become(D)
    resetPeopleDirectory()
    const heldD = (await openScope(ScopeKind.Tenant))!
    expect(heldD.epochs.current, 'the open invite was re-granted the new epoch').toBe(1)
    const seenByD = await loadTenantPeople()
    expect(seenByD.get(A.userId!)?.displayName).toBe(fullName(A_NAME))
    expect(seenByD.get(B.userId!)?.displayName).toBe(fullName(B_NAME))
  }, 360_000)
})
