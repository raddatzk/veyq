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
// (cr-secure-the-account, inbox #556) has B secure their account and abandon the ceremony right after the publish: a fresh
// sign-in with the NEW Emergency Kit finishes it — B's own device commits G's next epoch — and E, a co-member whose
// grant B's old key signed, reads what B posts afterwards with no review: B's new key is reached by B's key chain.
//
// The tenant domain is `veyq.localhost` — a valid dotted domain AND an RFC 6761 loopback name, so it serves as
// both the tenant domain and the fetch Host with no manual Host juggling. Run against a FRESH instance:
//   docker compose -f infra/docker/docker-compose.yml down -v && up -d  → restart backend  →
//   VEYQ_E2E=1 npx vitest run scopeRotation.e2e
// Or let `python3 scripts/run_web_e2e.py` do all of that, which is what CI runs.
import { beforeAll, describe, it, expect } from 'vitest'
import { tasksApi } from '../../api/client'
import { addGroupMember, listScopeMembers, removeTenantMember, resolveGroupMember } from '../spaces/members'
import { mintSecretKey, rotateKeys } from '../account/rotation/rotateKeys'
import { listRotationStates } from '../account/rotation/finishRotation'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { clearVault } from '../../app/vault'
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
  await addGroupMember(scopeId, who.username, candidate.userId, candidate)
}

/** Every request after the publish's answer fails as a closed tab's would, until the returned restore: the ceremony is
 *  abandoned right after the publish committed, before anything that follows it (the vault's re-seals, the finish). */
function abandonAfterThePublish(): () => void {
  const real = globalThis.fetch
  let gone = false
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    if (gone) throw new TypeError('Failed to fetch')
    const response = await real(input, init)
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if ((init?.method ?? 'GET').toUpperCase() === 'POST' && new URL(url).pathname === '/api/account/rotation') gone = true
    return response
  }) as typeof fetch
  return () => {
    globalThis.fetch = real
  }
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

    // --- cr-secure-the-account (inbox #556): securing the account loses no space, even abandoned after the publish ---
    // B adds E to G (the add carries E's grant, signed by B's current key, which E pins through B's chain).
    await become(A)
    const E = await onboardMember(tenant.tenantId, mkActor('e'), salt)
    await become(B)
    await addToGroup(gG, E)
    await become(E)
    expect(await openScopeById(gG), 'E opens the grant B signed, pinning B\'s key').toBeTruthy()

    // B secures the account; the tab is gone the moment the publish answered.
    await become(B)
    const newKit = mintSecretKey()
    const restore = abandonAfterThePublish()
    try {
      await rotateKeys(B.password, B.built.emergencyKit.secretKey, { secretKeyB64: newKit })
    } finally {
      restore()
    }
    // A fresh sign-in: the old kit opens nothing any more, the new one opens the account and G reads `waiting`.
    clearVault()
    await login(B.email, B.password)
    await expect(unlockMasterKey(B.password, B.built.emergencyKit.secretKey, { noteDevice: false }), 'the old kit is refused').rejects.toBeTruthy()
    await unlockMasterKey(B.password, newKit, { noteDevice: false })
    expect((await listRotationStates()).find((s) => s.scopeId === gG)?.state, 'G waits for its next epoch').toBe('waiting')
    expect((await listMyScopes()).find((s) => s.id === gG)?.grantedEpoch, 'the publish re-granted G\'s current epoch to the new identity').toBe(2)

    // The worker's first pass finishes it: B's own device commits G's next epoch, granting E.
    await runScopeRotationWorker()
    const finished = (await listMyScopes()).find((s) => s.id === gG)
    expect(finished?.currentEpoch, 'B committed G\'s next epoch itself').toBe(3)
    expect(finished?.grantedEpoch, 'B holds it').toBe(3)
    expect((await listRotationStates()).find((s) => s.scopeId === gG)?.state, 'G is no longer waiting').not.toBe('waiting')
    const openedAfter = await openScopeById(gG)
    await createMemory(gG, openedAfter!.epochs, 'Posted after securing')

    // E reads it: B's new key signed E's grant, and E accepts it because B's chain reaches it from the head E pinned.
    await become(E)
    const openedE = await openScopeById(gG)
    expect(openedE?.epochs.current, 'E holds the epoch B committed').toBe(3)
    expect((await listTree(gG, openedE!.epochs)).some((n) => n.name === 'Posted after securing'), 'E reads what B posted').toBe(true)
  }, 360_000)
})
