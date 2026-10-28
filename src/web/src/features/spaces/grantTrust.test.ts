import 'fake-indexeddb/auto'
// The recipient's half of the anchor gate (ADR 0028 Decision 2), end to end on real keys: a grant from
// another member opens only when that member's key chain reaches the key that signed it from the head this account
// pinned — the pin store (`identityPins.ts`), the chain walk (`@veyq/crypto`'s `verifyKeyChain`) and this device's epoch
// floor (`trustMarks.ts`) are all REAL here; only the server is faked: the pin store's endpoint, the key-chain read, and
// the grant row as a read serves it. Every chain is built by `@veyq/crypto`'s own statements and signed by real keys.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { fakePinServer, pinOf, pinsOn, sealPinStore } from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, headOf, step, type BuiltChain } from '../../test/keyChain'

vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn(), accountApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))

import { verifyOwnGrant, GrantRefused, type Me } from './grantTrust'
import { sealGrant, type GrantScope } from './grant'
import { forgetVerifiedPins } from '../account/identityPins'
import { raiseEpochFloor } from '../account/trustMarks'
import { masterKey } from '../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const MK = v1.randomBytes(32)
const G = '6a000000-0000-4000-8000-0000000000a1'
const GRANTER = '6a000000-0000-4000-8000-0000000000b1'

// The caller as the recipient: its own identity, from the vault.
const MINE = v1.generateIdentity()
const ME: Me = { userId: '6a000000-0000-4000-8000-0000000000c1', pub: MINE.pub, pubB64: b64std(v1.serializeIdentityPublic(MINE.pub)) }

// The granter's keys: born with OLD, then securing the account to NEW.
const OLD = chainKeys()
const NEW = chainKeys()

let chains: ReturnType<typeof fakeKeyChains>

/** A grant of a fresh key on G at `epoch` to the caller, signed by `signer`, as the caller's grant row serves it. */
function grantAt(epoch: number, signer: v1.Identity) {
  const scope: GrantScope = { scopeId: G, kind: 'GROUP', epoch }
  const signed = sealGrant(v1.randomBytes(32), scope, { userId: ME.userId, identityPublicKeyB64: ME.pubB64 }, signer.priv.sign)
  const row = { granterUserId: GRANTER, grantSignature: signed.grantSignature, granterIdentityPublicKey: b64std(v1.serializeIdentityPublic(signer.pub)) }
  return { scope, wrappedGrant: signed.wrappedGrant, row }
}

const verify = (g: ReturnType<typeof grantAt>) => verifyOwnGrant(g.scope, g.wrappedGrant, g.row, ME)
const refusal = (g: ReturnType<typeof grantAt>) =>
  verify(g).then(() => 'opened', (e: unknown) => (e instanceof GrantRefused ? e.reason : `threw ${String(e)}`))

/** This account has pinned the granter at `chain`'s newest head. */
function pinnedAt(chain: BuiltChain) {
  return fakePinServer(sealPinStore(MK, { [GRANTER]: pinOf(bundleB64(chain.keys.at(-1)!), 'pinned', 1, headOf(chain)) }, 1))
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  forgetVerifiedPins()
  globalThis.indexedDB = new IDBFactory() // a device that has held no epoch and no store yet
  chains = fakeKeyChains()
})

describe('a grant from another member opens when the chain reaches its key from the pinned head (decision 9)', () => {
  it('advanced: the granter secured their account; a grant signed by the new key opens and the pin moves', async () => {
    const h0 = chains.born(GRANTER, OLD)
    const h1 = step(h0, NEW)
    chains.serve(h1)
    const srv = pinnedAt(h0)
    expect(await refusal(grantAt(1, NEW.identity))).toBe('opened')
    expect(pinsOn(MK, srv.state)[GRANTER]).toMatchObject({ head: headOf(h1), fingerprint: pinOf(bundleB64(NEW)).fingerprint })
  })

  it('anomaly: a "new key" whose step the retired Secret Key did not sign is refused as granter_key_unanchored', async () => {
    const h0 = chains.born(GRANTER, OLD)
    // A thief with OLD's identity (a stolen device) but not its Secret Key signs a step to a key of their own.
    const thief = chainKeys()
    chains.serve(step(h0, thief, { proofPrivateKey: chainKeys().proof.privateKey }))
    const srv = pinnedAt(h0)
    expect(await refusal(grantAt(1, thief.identity))).toBe('granter_key_unanchored')
    expect(pinsOn(MK, srv.state)[GRANTER].head).toBe(headOf(h0))
  })

  it('anomaly: a key no chain of the granter holds is refused, and so is any key when no chain is served', async () => {
    const h0 = chains.born(GRANTER, OLD)
    pinnedAt(h0)
    expect(await refusal(grantAt(1, v1.generateIdentity()))).toBe('granter_key_unanchored')
    chains = fakeKeyChains() // the server now serves no chain for the granter
    expect(await refusal(grantAt(1, NEW.identity))).toBe('granter_key_unanchored')
  })

  it('control: the pinned key itself opens with no chain read', async () => {
    pinnedAt(chains.born(GRANTER, OLD))
    expect(await refusal(grantAt(3, OLD.identity))).toBe('opened')
    expect(chains.getKeyChain).not.toHaveBeenCalled()
  })

  it('a first-seen granter is pinned at the head of their chain once the signature verified', async () => {
    const h0 = chains.born(GRANTER, OLD)
    const srv = fakePinServer()
    expect(await refusal(grantAt(0, OLD.identity))).toBe('opened')
    expect(pinsOn(MK, srv.state)[GRANTER].head).toBe(headOf(h0))
  })
})

describe('a grant signed by a RETIRED key of the granter\'s chain opens only at or below this device\'s floor', () => {
  // This account already follows the granter's new key (the pin is at h1): OLD is a retired key of the pinned chain.
  function retiredOld() {
    const h0 = chains.born(GRANTER, OLD)
    const h1 = step(h0, NEW)
    chains.serve(h1)
    pinnedAt(h1)
  }

  it('above the floor it is refused — a new epoch from a retired key is what a thief with the old identity forges', async () => {
    retiredOld()
    await raiseEpochFloor(G, 1) // this device has held epoch 1 of G
    expect(await refusal(grantAt(2, OLD.identity))).toBe('granter_key_retired')
    // control on the same predicate: the granter's current key at that epoch opens
    expect(await refusal(grantAt(2, NEW.identity))).toBe('opened')
  })

  it('at the floor it opens — the grant is from before the rotation, an epoch this device already held', async () => {
    retiredOld()
    await raiseEpochFloor(G, 2)
    expect(await refusal(grantAt(2, OLD.identity))).toBe('opened')
  })

  it('below the floor it opens too', async () => {
    retiredOld()
    await raiseEpochFloor(G, 3)
    expect(await refusal(grantAt(1, OLD.identity))).toBe('opened')
  })

  it('a device that never held the scope (floor 0) opens a retired key\'s grant only at epoch 0', async () => {
    retiredOld()
    expect(await refusal(grantAt(0, OLD.identity))).toBe('opened')
    expect(await refusal(grantAt(1, OLD.identity))).toBe('granter_key_retired')
  })

  it('a pin still at the old head does not know the key is retired: the pinned key matches (no chain read)', async () => {
    // The residual of a pin that never saw the rotation: the device judges against what it pinned (decision 9's
    // "retired key of the PINNED chain"); the next directory read that serves the new head moves the pin.
    const h0 = chains.born(GRANTER, OLD)
    chains.serve(step(h0, NEW))
    pinnedAt(h0)
    expect(await refusal(grantAt(2, OLD.identity))).toBe('opened')
    expect(chains.getKeyChain).not.toHaveBeenCalled()
  })
})
