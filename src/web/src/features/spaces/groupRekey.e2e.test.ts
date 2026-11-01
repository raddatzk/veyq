// ADR 0025 § 1 live (VEYQ_E2E=1): a group owner gives the group a new lock with nobody removed — the plain rotation of the
// GROUP over the members it has (`rekeySpace`) and the node re-key, as `GroupMembersPanel` runs them — against a REAL
// backend + MariaDB + the bundled store, with REAL crypto. Three actors on one instance, serialized (`become()` swaps the
// session and the vault):
//   A = tenant owner and owner of group G; the rotator.
//   B = a plain member of G; refused the re-key, reads on after it.
//   C = a member of G who joins between A's directory read and A's commit — the rebuild's re-read grants them
//       (inbox #661).
//
// What only this layer proves: that the real server takes a group owner's rotation without `removesUserId` and refuses a
// plain member's, that a membership made between the directory read and the commit is met by the one rebuild and not
// left out, and that every member reads the group's folder under the new epoch. The unit layer (`members.test.ts`,
// `scopes.test.ts`, `GroupMembersPanel.test.ts`) and `GroupRekeySliceTest` prove the steps apart.
//
//   VEYQ_INFRA_DIR=<main checkout>/infra/docker VEYQ_JAR=<jar> python3 scripts/run_web_e2e.py \
//     src/web/src/features/spaces/groupRekey.e2e.test.ts
import { beforeAll, describe, it, expect } from 'vitest'
import { birthInstance, become, mkActor, onboardMember } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { registerMemoriesLifecycle } from '../../test/memoriesLifecycle'
import { addGroupMember, listScopeMembers, rekeySpace, resolveGroupMember } from './members'
import { commitRotation, createGroup, listMyScopes, openScopeById } from './scopes'
import { createFolder, listTree } from '../../core/node/tree'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { decodeHttpError } from '../../shared/httpError'

beforeAll(() => registerMemoriesLifecycle())

const GROUP_NAME = 'Chorprobe Ammersee'
const FOLDER_NAME = 'Notenblaetter Herbstkonzert'

const groupRow = async (scopeId: string) => (await listMyScopes()).find((s) => s.id === scopeId)

describe.skipIf(!process.env.VEYQ_E2E)('a group owner gives the group a new lock with nobody removed (ADR 0025 § 1, live, real crypto)', () => {
  it('a plain member is refused, the owner moves the group to the next epoch with everyone in it, a late joiner included', async () => {
    // --- the instance: A owns the tenant and group G, B is in G; C is a tenant member not yet in G ------------------
    const { owner: A, tenant, salt } = await birthInstance('E2E Group Rekey')
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)
    recordCanaries(GROUP_NAME, FOLDER_NAME)
    await become(A)
    const groupId = await createGroup(GROUP_NAME)
    const candidateB = await resolveGroupMember(groupId, B.username)
    await addGroupMember(groupId, B.username, candidateB.userId, candidateB)
    const openedA = (await openScopeById(groupId))!
    const folderId = await createFolder(groupId, openedA.epochs, FOLDER_NAME)

    // --- B, a plain member: the server refuses its re-key, nothing moves -------------------------------------------
    await become(B)
    const refused = await rekeySpace(groupId).then(() => null, decodeHttpError)
    expect(refused, 'a plain member may not give the group a new lock').toEqual({ status: 403, code: 'not_scope_owner' })
    expect((await groupRow(groupId))?.currentEpoch).toBe(0)

    // --- A re-keys from a directory read BEFORE C joins: the first commit meets C's membership, the rebuild reads the
    //     directory again and grants C too (inbox #661) --------------------------------------------------------------
    await become(A)
    const readBefore = await listScopeMembers(groupId)
    expect(readBefore.map((m) => m.userId).sort()).toEqual([A.userId, B.userId].sort())
    const candidateC = await resolveGroupMember(groupId, C.username)
    await addGroupMember(groupId, C.username, candidateC.userId, candidateC)
    // Without the re-read the rebuild carries the same list and meets the same `409 rotation_incomplete`: this throws.
    expect(await commitRotation(groupId, readBefore)).toBe(1)
    expect((await rekeyScope(groupId)).done, 'every node of the group re-keyed on A\'s device').toBe(true)
    const rowA = (await groupRow(groupId))!
    expect([rowA.currentEpoch, rowA.grantedEpoch, rowA.rekeying]).toEqual([1, 1, false])
    const members = await listScopeMembers(groupId)
    expect(members.map((m) => m.userId).sort(), 'a re-key removes nobody').toEqual([A.userId, B.userId, C.userId].sort())
    expect(members.every((m) => !m.pending), 'every key anchored: granted, none declared pending').toBe(true)

    // --- the owner's own action once more, on its own: the next epoch, nobody removed -----------------------------
    expect(await rekeySpace(groupId)).toBe(2)
    expect((await rekeyScope(groupId)).done).toBe(true)

    // --- B and C hold the newest epoch, peel the ones before it, and read the folder under it --------------------------
    for (const who of [B, C]) {
      await become(who)
      const held = (await openScopeById(groupId))!
      expect(held.epochs.current, `${who.username} holds the new epoch`).toBe(2)
      const folder = (await listTree(groupId, held.epochs)).find((n) => n.id === folderId)
      expect(folder?.name).toBe(FOLDER_NAME)
      expect(folder?.epoch, 'the folder carries the new epoch').toBe(2)
    }
  }, 360_000)
})
