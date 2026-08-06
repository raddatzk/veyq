// cr-0092 live e2e (VEYQ_E2E=1): the async group-key-rotation backstop, end-to-end against a REAL backend +
// MariaDB with REAL crypto — no mocks. Three actors on one instance (serialized: each `become()` swaps the
// session token + unlocks that actor's vault):
//   A = tenant OWNER (server owner + tenant owner) — the remover; NOT a key-holder of the group.
//   B = group key-holder (owns group G, holds K_SC).
//   C = co-sharer of G (a key-holder after grant-delivery) — the removed member.
//
// Flow: A removes C from the tenant → the cascade cuts C's co-shared G membership and originates a
// SCOPE_ROTATION job (A can't rotate G — A holds no key there). A/removed-C cannot claim it; B (the remaining
// key-holder) claims it via runScopeRotationWorker, mints E_1 + re-grants the survivors on-device, completes.
// Asserts the rotation genuinely happened on the live backend: epoch 0 → 1, a real chain-link at epoch 1, B
// re-granted at epoch 1, C gone, and the job cleared. The pure ex-member lockout crypto (E_0 can't derive E_1)
// is unit-tested in scopes.test.ts / epochs.test.ts; this proves the whole mechanism is wired live.
//
// The tenant domain is `veyq.localhost` — a valid dotted domain AND an RFC 6761 loopback name, so it serves as
// both the tenant domain and the fetch Host with no manual Host juggling. Run against a FRESH instance:
//   docker compose -f infra/docker-compose.yml down -v && up -d  → restart backend  →
//   VEYQ_E2E=1 npx vitest run scopeRotation.e2e
// Or let `python3 scripts/run_web_e2e.py` do all of that, which is what CI runs.
import { describe, it, expect } from 'vitest'
import { tasksApi } from '../../api/client'
import { addGroupMember, listScopeMembers, removeTenantMember } from '../spaces/members'
import { createGroup, listMyScopes, listScopeEpochs, openScopeById } from '../spaces/scopes'
import { createMemory, listTree } from '../memory/tree'
import { become, birthInstance, mkActor, onboardMember } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { runGrantDeliveryWorker } from './grantDeliveryWorker'
import { runScopeRotationWorker } from './scopeRotationWorker'
import { SCOPE_ROTATION } from './capabilityTags'

async function claimRotation() {
  return (await tasksApi().claimTask({ claimRequest: { capabilityTags: [SCOPE_ROTATION] } })) ?? null
}

describe.skipIf(!process.env.VEYQ_E2E)('cr-0092 async rotation backstop (live, real crypto)', () => {
  it('a tenant-member removal queues a rotation a remaining key-holder completes; A/ex-member cannot', async () => {
    // --- A: cold-birth the server owner, then create + accept the tenant as its OWNER ---
    const { owner: A, tenant, salt } = await birthInstance('E2E Rotation')

    // --- B, C: onboard as tenant MEMBERS (grant-less cold-birth accept via an owner-minted invite) ---
    const B = await onboardMember(tenant.tenantId, mkActor('b'), salt)
    const C = await onboardMember(tenant.tenantId, mkActor('c'), salt)

    // --- B: create group G, add C, deliver K_SC to C (real foreign wrap) so C is a genuine co-holder ---
    await become(B)
    const gG = await createGroup('Shared G')
    await addGroupMember(gG, C.username)
    const openedB = await openScopeById(gG)
    expect(openedB, 'B opens its own group').toBeTruthy()
    await runGrantDeliveryWorker({ scopeId: gG, key: openedB!.key }) // wraps K_SC (E_0) to C's identity
    const before = await listScopeMembers(gG)
    expect(before.find((x) => x.userId === C.userId)?.hasGrant, 'C is a co-holder before removal').toBe(true)
    expect((await listMyScopes()).find((s) => s.id === gG)?.currentEpoch, 'G starts at epoch 0').toBe(0)

    // --- A: remove C from the tenant → cascade cuts C's co-shared G membership + queues SCOPE_ROTATION for G ---
    await become(A)
    await removeTenantMember(tenant.tenantId, C.userId!)

    // A holds no grant in G (A was never in it) → the removing owner cannot rotate: nothing claimable.
    expect(await claimRotation(), 'the removing owner is not a key-holder → no rotation claimable').toBeNull()

    // The removed member no longer holds G's grant → cannot claim either.
    await become(C)
    expect(await claimRotation(), 'the removed member cannot claim the rotation').toBeNull()

    // --- B: the remaining key-holder drains the queued rotation on-device (claim → rotate → complete) ---
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

    // Onboard D and add them to the ALREADY-ROTATED group; grant-delivery wraps the current key E_1 to D.
    await become(A)
    const D = await onboardMember(tenant.tenantId, mkActor('d'), salt)
    await become(B)
    await addGroupMember(gG, D.username)
    const openedForDelivery = await openScopeById(gG)
    await runGrantDeliveryWorker({ scopeId: gG, key: openedForDelivery!.key })

    // D must be recorded at the CURRENT epoch (not 0) and therefore read the post-rotation memo. Before cr-0093,
    // complete left grantedEpoch=0, so D's client peeled E_1 as E_0 and listTree would fail on the epoch-1 node.
    await become(D)
    expect((await listMyScopes()).find((s) => s.id === gG)?.grantedEpoch, 'D granted at the current epoch').toBe(1)
    const openedD = await openScopeById(gG)
    const treeD = await listTree(gG, openedD!.epochs)
    expect(treeD.some((n) => n.name === 'Post-rotation memo'), 'D reads post-rotation content').toBe(true)

    // The wire assertion's derivation (ADR 0053) — every node name D recovered from the ROTATED group, opened
    // under E_1. The epoch keys and grants this suite is really about are `Uint8Array`s and drop by type; what
    // is left is the one plaintext this flow sealed into the group's tree.
    recordCanaries(treeD.map((n) => n.name))
  }, 240_000)
})
