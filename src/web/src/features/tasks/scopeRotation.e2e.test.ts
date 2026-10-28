// cr-0092 live e2e (VEYQ_E2E=1): the async group-key-rotation backstop, end-to-end against a REAL backend +
// MariaDB with REAL crypto — no mocks. Three actors on one instance (serialized: each `become()` swaps the
// session token + unlocks that actor's vault):
//   A = tenant OWNER (server owner + tenant owner) — the remover; NOT a key-holder of the group.
//   B = group key-holder (owns group G, holds K_SC).
//   C = co-sharer of G (a key-holder from the add on: the add carries the grant, ADR 0111) — the removed member.
//
// Flow: A removes C from the tenant → the cascade cuts C's co-shared G membership and originates a
// SCOPE_ROTATION job (A can't rotate G — A holds no key there). A/removed-C cannot claim it; B (the remaining
// owner, the only one the job is offered to) claims it via runScopeRotationWorker, mints E_1 + re-grants the survivors
// on-device, and completes — on its own commit, the only one the completion accepts.
// Asserts the rotation genuinely happened on the live backend: epoch 0 → 1, a real chain-link at epoch 1, B
// re-granted at epoch 1, C gone, and the job cleared. The pure ex-member lockout crypto (E_0 can't derive E_1)
// is unit-tested in scopes.test.ts / epochs.test.ts; this proves the whole mechanism is wired live. Its last leg
// (inbox #233) has B rotate their account keys and a co-owner E, whose grant B signed, complete the covering rotation.
//
// The tenant domain is `veyq.localhost` — a valid dotted domain AND an RFC 6761 loopback name, so it serves as
// both the tenant domain and the fetch Host with no manual Host juggling. Run against a FRESH instance:
//   docker compose -f infra/docker/docker-compose.yml down -v && up -d  → restart backend  →
//   VEYQ_E2E=1 npx vitest run scopeRotation.e2e
// Or let `python3 scripts/run_web_e2e.py` do all of that, which is what CI runs.
import { beforeAll, describe, it, expect } from 'vitest'
import { tasksApi } from '../../api/client'
import { addGroupMember, listScopeMembers, removeTenantMember, resolveGroupMember, setMemberRole, SetMemberRoleRequestRoleEnum } from '../spaces/members'
import { rotateKeys } from '../account/rotation/rotateKeys'
import { login } from '../account/login/login'
import { createGroup, isStaleEpoch, listMyScopes, listScopeEpochs, openScopeById, rotateGroupForRemoval } from '../spaces/scopes'
import { listTree } from '../../core/node/tree'
import { createMemory } from '../memories/memoryNodes'
import { become, birthInstance, mkActor, onboardMember, type Actor } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { runScopeRotationWorker } from './scopeRotationWorker'
import { SCOPE_ROTATION } from './capabilityTags'
import { registry } from '../../core/applications/registry'
import { memoriesLifecycle } from '../memories/lifecycle'
import { MEMORY_KIND } from '../memories/kinds'
import { rekeyMemoryContent } from '../memories/rekey'

// A node-environment file is given no registry (`src/test/setup.ts`), and `rotateKeys` fails closed without a registered
// `onMasterKeyRotated`; register the real Memories lifecycle, as the other node suites that rotate do (inbox #340).
// The worker's re-key (ADR 0109 point 7) fails closed too, on a kind nobody registered (`rekeyScope`: "no application
// re-keys a node of kind MEMORY"), and this suite's group holds Memories once the covering rotation runs: register the
// kind with Memories' real `rekeyContent`. Without it E's worker failed its job and completed none (heavy stage
// 2026-10-23). The rest of the descriptor is the kind's identity only; `register.ts` is not imported here, as its
// components need a window.
beforeAll(() => {
  registry.registerLifecycle('memories', memoriesLifecycle)
  if (!registry.kind(MEMORY_KIND).supported) registry.registerKind({ kind: MEMORY_KIND, icon: 'images', shareable: true, rekeyContent: rekeyMemoryContent })
})

/** Add [who] to the group as the members panel does (ADR 0111 decision 4): resolve, then wrap the group's current key
 *  to the identity key the resolve served — the member holds it from the add on, no grant delivery behind it. */
async function addToGroup(scopeId: string, who: Actor): Promise<void> {
  const candidate = await resolveGroupMember(scopeId, who.username)
  await addGroupMember(scopeId, who.username, candidate.userId, candidate.identityPublicKey)
}

async function claimRotation() {
  return (await tasksApi().claimTask({ claimRequest: { capabilityTags: [SCOPE_ROTATION] } })) ?? null
}

describe.skipIf(!process.env.VEYQ_E2E)('cr-0092 async rotation backstop (live, real crypto)', () => {
  it('a tenant-member removal queues a rotation a remaining owner completes; A/ex-member cannot', async () => {
    // --- A: cold-birth the server owner, then create + accept the tenant as its OWNER ---
    const { owner: A, tenant, salt } = await birthInstance('E2E Rotation')

    // --- B, C: onboard as tenant MEMBERS (a code invite each, accepted with the self-grant, ADR 0111) ---
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)

    // --- B: create group G and add C — the add wraps K_SC (E_0) to C's identity, so C is a genuine co-holder ---
    await become(B)
    const gG = await createGroup('Shared G')
    await addToGroup(gG, C)
    const openedB = await openScopeById(gG)
    expect(openedB, 'B opens its own group').toBeTruthy()
    expect((await listMyScopes()).find((s) => s.id === gG)?.currentEpoch, 'G starts at epoch 0').toBe(0)
    await become(C)
    expect(await openScopeById(gG), 'C is a co-holder before removal: it opens G with the grant the add carried').toBeTruthy()

    // --- A: remove C from the tenant → cascade cuts C's co-shared G membership + queues SCOPE_ROTATION for G ---
    await become(A)
    await removeTenantMember(tenant.tenantId, C.userId!)

    // A holds no grant in G (A was never in it) → the removing owner cannot rotate: nothing claimable.
    expect(await claimRotation(), 'the removing owner is not a key-holder → no rotation claimable').toBeNull()

    // The removed member no longer holds G's grant → cannot claim either.
    await become(C)
    expect(await claimRotation(), 'the removed member cannot claim the rotation').toBeNull()

    // --- B: the remaining owner drains the queued rotation on-device (claim → rotate → complete) ---
    await become(B)
    const rotated = await runScopeRotationWorker()
    expect(rotated, 'B completed exactly one queued rotation').toBe(1)

    // The rotation genuinely advanced the epoch on the live backend, re-granted the survivor, and left a chain-link.
    const gAfter = (await listMyScopes()).find((s) => s.id === gG)
    expect(gAfter?.currentEpoch, 'G advanced to epoch 1').toBe(1)
    expect(gAfter?.grantedEpoch, 'B was re-granted at the new epoch').toBe(1)
    const links = await listScopeEpochs(gG)
    expect(links.some((l) => l.epoch === 1 && l.wrappedPrev.length > 0), 'a real E_1←E_0 chain-link exists').toBe(true)
    const after = await listScopeMembers(gG)
    expect(after.some((x) => x.userId === C.userId), 'the ex-member is gone from G').toBe(false)

    // The job is DONE — idempotent + coalescing: nothing more is claimable.
    expect(await runScopeRotationWorker(), 'the rotation job is cleared').toBe(0)

    // --- cr-0093: a member added AFTER the rotation reads post-rotation content (grantedEpoch recorded right) ---
    // B (key-holder at E_1) writes a memory sealed under E_1, tagged epoch 1.
    await become(B)
    const openedForWrite = await openScopeById(gG)
    await createMemory(gG, openedForWrite!.epochs, 'Post-rotation memo')

    // Onboard D and add them to the ALREADY-ROTATED group; the add wraps the current key E_1 to D.
    await become(A)
    const D = await onboardMember(tenant.tenantId, mkActor('d'), salt)
    await become(B)
    await addToGroup(gG, D)

    // D must be recorded at the CURRENT epoch (not 0) and therefore read the post-rotation memo. Before cr-0093, a
    // grant's write left grantedEpoch=0, so D's client peeled E_1 as E_0 and listTree would fail on the epoch-1 node.
    await become(D)
    expect((await listMyScopes()).find((s) => s.id === gG)?.grantedEpoch, 'D granted at the current epoch').toBe(1)
    const openedD = await openScopeById(gG)
    const treeD = await listTree(gG, openedD!.epochs)
    expect(treeD.some((n) => n.name === 'Post-rotation memo'), 'D reads post-rotation content').toBe(true)

    // The wire assertion's derivation (ADR 0053) — every node name D recovered from the ROTATED group, opened
    // under E_1. The epoch keys and grants this suite is really about are `Uint8Array`s and drop by type; what
    // is left is the one plaintext this flow sealed into the group's tree.
    recordCanaries(treeD.map((n) => n.name))

    // --- ADR 0024 Decision 4: an owner's removal and its rotation are ONE request ---
    // B removes D from the directory B picked from; the server deletes D's membership and commits epoch 2 together.
    await become(B)
    const picked = await listScopeMembers(gG)
    expect(await rotateGroupForRemoval(gG, picked, D.userId!), 'the removal-rotation commits the next epoch').toBe(2)
    expect((await listScopeMembers(gG)).some((x) => x.userId === D.userId), 'D is gone, in the same request').toBe(false)
    expect((await listMyScopes()).find((s) => s.id === gG)?.grantedEpoch, 'B re-granted at the new epoch').toBe(2)

    // --- Decision 6, the server half: a write still sealed under the superseded epoch is refused ---
    const stale = await createMemory(gG, openedForWrite!.epochs, 'Sealed under E_1').catch((e: unknown) => e)
    expect(await isStaleEpoch(stale), 'a create tagged with epoch 1 after the rotation to 2 is 409 stale_epoch').toBe(true)
    // Control on the same predicate: re-opened at the current epoch, the same write lands.
    const current = await openScopeById(gG)
    expect(current!.epochs.current).toBe(2)
    await createMemory(gG, current!.epochs, 'Sealed under E_2')

    // --- inbox #233: a grant outlives its granter's account rotation (ADR 0028 § What is still open) ---
    // B adds E to G (the add carries E's grant) and makes E a co-owner, then rotates their account keys (ADR 0060). E's grant is
    // signed by B's OLD identity, so E — the owner who must complete the covering rotation — can open G only while
    // the server serves the key that signed it. Served B's new key, E's worker failed the job on every attempt
    // (`cannot open scope to rotate`) and G stayed at the epoch B's old identity holds.
    await become(A)
    const E = await onboardMember(tenant.tenantId, mkActor('e'), salt)
    await become(B)
    await addToGroup(gG, E)
    await setMemberRole(gG, E.userId!, SetMemberRoleRequestRoleEnum.Owner)
    await become(E)
    expect(await openScopeById(gG), 'E opens the grant B signed, pinning B\'s key').toBeTruthy()

    await become(B)
    await rotateKeys(B.password, B.built.emergencyKit.secretKey)

    // The tenant's own rotation is A's to complete (E owns no tenant), so A drains it first and E's pass meets G alone.
    await become(A)
    await runScopeRotationWorker()

    await become(E)
    expect(await runScopeRotationWorker(), 'E completes the rotation B\'s account rotation queued for G').toBe(1)
    const covered = (await listMyScopes()).find((s) => s.id === gG)
    expect(covered?.currentEpoch, 'G advanced past the epoch B\'s old identity holds').toBe(3)
    expect(covered?.grantedEpoch, 'E re-granted at the new epoch').toBe(3)
    // B's new key reads `changed` to E, so the covering rotation skips B: re-admission waits for E's accept.
    await login(B.email, B.password)
    expect((await listMyScopes()).find((s) => s.id === gG)?.grantedEpoch, 'B is not re-granted by the covering rotation').toBe(2)
  }, 360_000)
})
