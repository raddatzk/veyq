import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import {
  fakePinServer,
  pinIdOf as pinIdAt,
  pinOf,
  pinsOn as pinsAt,
  sealPinEntry,
  sealPinHead,
  sealPinStore,
  SOME_HEAD,
  type PinServerState,
} from '../../test/pinStore'
import { bundleB64, chainKeys, fakeKeyChains, genesis, headOf, served, servedIdentity, step, type BuiltChain, type ChainKeys } from '../../test/keyChain'

// The pin store's API + vault are mocked; the crypto (seal/open under the Master Key, the pin ids, the set digest, every
// key-chain step) is REAL, so the tests prove a genuine round-trip, the per-entry refusals, the revision-guarded merge
// and the walk of a chain from the pinned head, not a stub. The fake server below seals nothing itself: what it holds is
// what the module wrote, or what `storeOf` seals in the documented format (v1.json#identity_pin) — an independent writer
// of the wire, so a drift of either side is red here. The chains are built by `@veyq/crypto`'s own statements and signed
// by real keys (`src/test/keyChain.ts`); an attack is a chain assembled as the attacker would assemble it.
vi.mock('../../api/client', () => ({ accountIdentityPinsApi: vi.fn(), keyChainApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))

import {
  anchorIdentity,
  anchorChainKey,
  anchorServedChain,
  getPin,
  safetyNumber,
  safetyNumberOfFingerprint,
  myIdentityPublicKeyB64,
  readChain,
  readJoinedChain,
  forgetVerifiedPins,
  pinStoreDocument,
  ENTRY_BYTES,
  type Pin,
  type Standing,
  type TenantStandings,
} from './identityPins'
import { fingerprintDigestB64 } from './safetyCode'
import { masterKey, identityPrivate } from '../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type SymVector = { context: Record<string, string>; kek: string; blob: string; plaintext: string }
const vectors = JSON.parse(
  readFileSync(resolve(__dirname, '../../../../../contracts/crypto/vectors/v1.json'), 'utf8'),
) as {
  fingerprint: { kem: string; sign: string; digits: string; hash: string }
  identity_pin: { master_key: string; pin_ids: { userId: string; pinId: string }[]; entry_bytes: number }
  seal: { sym: Record<string, SymVector> }
}
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))

// One Master Key for the suite (the vector's, so the vector entry below opens as a stored one); the mocked vault hands
// out a FRESH copy each call because the data layer zeroes the copy it receives.
let MK = unhex(vectors.identity_pin.master_key)
const enc = new TextEncoder()

// Real identities with real proof keys: a pin is the FINGERPRINT of a served bundle and the head of a chain, so a test
// key must be a bundle the codec decodes and a chain step must be signable.
const A = chainKeys()
const B = chainKeys()
const C = chainKeys()
const KEY_A = bundleB64(A)
const KEY_B = bundleB64(B)
const KEY_C = bundleB64(C)
const fp = (key: string) => fingerprintDigestB64(key)

// Contacts are canonical UUIDs: a pin's id is derived from the userId, and a second spelling would be a second slot.
const U1 = 'c0ffee00-0000-4000-8000-0000000000a1'
const U2 = 'c0ffee00-0000-4000-8000-0000000000a2'
const U3 = 'c0ffee00-0000-4000-8000-0000000000a3'
const OTHER = 'c0ffee00-0000-4000-8000-0000000000b1'

/** The pin a device holds for `chain`'s newest step — its key and head. */
const pinAt = (chain: BuiltChain, firstSeenAt = 1, o: { replaced?: boolean } = {}): Pin =>
  pinOf(bundleB64(chain.keys.at(-1)!), firstSeenAt, headOf(chain), { index: chain.steps.length - 1, ...o })

// The server side is the shared fake (`src/test/pinStore.ts`): it seals the documented format independently of this module.
type ServerState = PinServerState
const fakeServer = fakePinServer
const storeOf = (pins: Record<string, Pin>, revision: number) => sealPinStore(MK, pins, revision)
const headFor = (entries: Map<string, string>, revision: number) => sealPinHead(MK, entries, revision)
const sealEntryAs = (userId: string, pin: Pin, at: string) => sealPinEntry(MK, userId, pin, at)
const pinsOn = (state: ServerState) => pinsAt(MK, state)
const idOf = (userId: string, mk = MK) => pinIdAt(mk, userId)

let chains: ReturnType<typeof fakeKeyChains>

/** `userId` born with `keys`, their chain served: what a directory serves for them, and the chain to step on. */
function born(userId: string, keys: ChainKeys): { served: ReturnType<typeof servedIdentity>; chain: BuiltChain } {
  const chain = chains.born(userId, keys)
  return { served: servedIdentity(chain), chain }
}

/** The wrap check of a first contact born with `keys`. */
const firstContact = (userId: string, keys: ChainKeys) => anchorIdentity(userId, born(userId, keys).served)

beforeEach(() => {
  vi.clearAllMocks()
  MK = unhex(vectors.identity_pin.master_key)
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  chains = fakeKeyChains()
  forgetVerifiedPins()
  // This device's local trust-state marks live in IndexedDB (ADR 0028 Decision 7): a fresh, empty one per test,
  // so every test starts as a device that has held nothing.
  globalThis.indexedDB = new IDBFactory()
})

describe('the stored format is the vectors\' (v1.json#identity_pin)', () => {
  it('an entry sealed by the independent generator opens as the pin of its contact, its head included', async () => {
    const v = vectors.seal.sym['identity-pin']
    const { userId, pinId } = vectors.identity_pin.pin_ids[0]
    expect(v.context.pinId).toBe(pinId)
    expect(ENTRY_BYTES).toBe(vectors.identity_pin.entry_bytes)
    const entries = new Map([[pinId, b64std(unhex(v.blob))]])
    fakeServer({ blob: headFor(entries, 4), revision: 4, entries })
    // The vector's head is synthetic: sha256("veyq/v1/key-chain/head" ‖ utf8(userId)).
    const head = b64std(v1.sha256Digest(enc.encode(`veyq/v1/key-chain/head${userId}`)))
    expect(await getPin(userId)).toEqual({
      fingerprint: b64std(unhex(vectors.fingerprint.hash)), head, index: 2, firstSeenAt: 1760000000000, replaced: true,
    })
  })

  it('this module seals the vector\'s plaintext: an entry it writes opens to the short keys, in their order', async () => {
    const srv = fakeServer()
    await firstContact(U1, A)
    const plain = v1.open({ purpose: 'identity-pin', pinId: unhex(idOf(U1)) }, MK, fromB64std(srv.state.entries.get(idOf(U1))!))
    expect(plain.length).toBe(ENTRY_BYTES)
    expect(Object.keys(JSON.parse(new TextDecoder().decode(plain)))).toEqual(['u', 'f', 'h', 'i', 't', 'r'])
  })
})

describe('anchorIdentity — first use and match (ADR 0028 Decision 2)', () => {
  it('first use reads the chain and pins its head with the key at it; the same key and head again is a local match', async () => {
    const srv = fakeServer()
    const { served, chain } = born(U1, A)
    expect(await anchorIdentity(U1, served)).toBe('firstUse')
    expect(srv.state.revision).toBe(1)
    expect(pinsOn(srv.state)).toEqual({ [U1]: { fingerprint: fp(KEY_A), head: headOf(chain), index: 0, firstSeenAt: expect.any(Number), replaced: false } })
    // A later wrap to the SAME key and head proceeds without a chain read and without a write.
    expect(await anchorIdentity(U1, served)).toBe('match')
    expect(chains.getKeyChain).toHaveBeenCalledTimes(1)
    expect(srv.writeIdentityPins).toHaveBeenCalledTimes(1)
  })

  it('a pin writes its one entry and the head — never the store whole (ADR 0104, inbox #491)', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B) }, 5))
    expect(await firstContact(U3, C)).toBe('firstUse')
    const w = srv.writeIdentityPins.mock.calls[0][0].identityPinsWrite
    expect(w.put.map((e) => e.pinId)).toEqual([idOf(U3)])
    expect(w.remove).toEqual([])
    expect(w.replaceAll).toBe(false)
    expect(w.revision).toBe(5)
    // Every entry seals to one length, whatever it holds.
    expect(fromB64std(w.put[0].entry).length).toBe(fromB64std(srv.state.entries.get(idOf(U1))!).length)
    expect(Object.keys(pinsOn(srv.state)).sort()).toEqual([U1, U2, U3].sort())
  })

  it('first use refuses a served head the chain does not end at, and pins nothing', async () => {
    const srv = fakeServer()
    const { chain } = born(U1, A)
    expect(await anchorIdentity(U1, { identityPublicKey: KEY_A, keyChainHead: SOME_HEAD })).toBe('anomaly')
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
    // control: the head the chain ends at
    expect(await anchorIdentity(U1, { identityPublicKey: KEY_A, keyChainHead: headOf(chain) })).toBe('firstUse')
  })

  it('first use refuses a served key that is not the chain\'s newest, and pins nothing', async () => {
    const srv = fakeServer()
    const { chain } = born(U1, A)
    expect(await anchorIdentity(U1, { identityPublicKey: KEY_B, keyChainHead: headOf(chain) })).toBe('anomaly')
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('a chain the server does not serve (404) is an anomaly on first use — never a pin of the bare served key', async () => {
    const srv = fakeServer()
    const { chain } = born(U1, A)
    expect(await anchorIdentity(U2, servedIdentity(chain))).toBe('anomaly') // U2 has no chain served
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('a served key this device cannot decode is an anomaly, read no chain for and never pinned', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 1))
    expect(await anchorIdentity(U1, { identityPublicKey: 'Z2FyYmxlZA==', keyChainHead: SOME_HEAD })).toBe('anomaly')
    expect(await anchorIdentity(U2, { identityPublicKey: 'Z2FyYmxlZA==', keyChainHead: SOME_HEAD })).toBe('anomaly')
    expect(chains.getKeyChain).not.toHaveBeenCalled()
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('reads an existing pin another device wrote: its head and key match, another key at that head is an anomaly', async () => {
    const { chain } = born(U1, A)
    const srv = fakeServer(storeOf({ [U1]: pinAt(chain) }, 3))
    expect(await anchorIdentity(U1, servedIdentity(chain))).toBe('match')
    expect(await anchorIdentity(U1, { identityPublicKey: KEY_B, keyChainHead: headOf(chain) })).toBe('anomaly')
    expect(chains.getKeyChain).not.toHaveBeenCalled() // both judged locally
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('the inviting owner, by the chain an invite serves: a first use pins the walked head at its REAL index, no chain fetched (decision 5)', async () => {
    const srv = fakeServer()
    const secured = step(genesis(U1, A), B)
    expect(await anchorServedChain(U1, served(secured), servedIdentity(secured))).toBe('firstUse')
    expect(chains.getKeyChain).not.toHaveBeenCalled()
    expect(pinsOn(srv.state)[U1]).toMatchObject({ fingerprint: fp(KEY_B), head: headOf(secured), index: 1 })
    // Again: a match, nothing written.
    expect(await anchorServedChain(U1, served(secured), servedIdentity(secured))).toBe('match')
    expect(srv.writeIdentityPins).toHaveBeenCalledTimes(1)
  })

  it('the inviting owner: a pinned head must lie on the served chain and carry the pinned key — else an anomaly, the pin kept', async () => {
    const born0 = genesis(U1, A)
    const srv = fakeServer(storeOf({ [U1]: pinAt(born0) }, 1))
    // Another chain for the same account (a key the operator holds), never through the pinned head.
    const forged = genesis(U1, C)
    expect(await anchorServedChain(U1, served(forged), servedIdentity(forged))).toBe('anomaly')
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
    // A served key that is not the chain's end: the walk does not end at it.
    const secured = step(born0, B)
    expect(await anchorServedChain(U1, served(secured), { ...servedIdentity(secured), identityPublicKey: KEY_A })).toBe('anomaly')
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
    // Control: the owner's genuine step from the pinned head advances the pin.
    expect(await anchorServedChain(U1, served(secured), servedIdentity(secured))).toBe('advanced')
    expect(pinsOn(srv.state)[U1]).toMatchObject({ fingerprint: fp(KEY_B), head: headOf(secured), index: 1 })
    expect(chains.getKeyChain).not.toHaveBeenCalled()
  })

  it('a head sealed at one revision does not open as another — a replayed head is refused as unreadable', async () => {
    const at3 = storeOf({ [U1]: pinOf(KEY_A) }, 3)
    fakeServer({ ...at3, revision: 4 })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    // control: the same head at its own revision opens
    fakeServer(at3)
    expect((await getPin(U1))?.fingerprint).toBe(fp(KEY_A))
  })

  it('a head of another version does not open — the single-blob store has no read path (owner Q3)', async () => {
    const old = b64std(v1.seal({ purpose: 'identity-pins', revision: 2 }, MK, enc.encode(JSON.stringify({ pins: {} }))))
    fakeServer({ blob: old, revision: 2, entries: new Map() })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
  })

  it('an entry without a head (the long-key format before decision 9) is refused as unreadable', async () => {
    const body = enc.encode(JSON.stringify({ userId: U1, fingerprint: fp(KEY_A), state: 'pinned', firstSeenAt: 1 }))
    const padded = new Uint8Array(ENTRY_BYTES).fill(0x20)
    padded.set(body)
    const entries = new Map([[idOf(U1), b64std(v1.seal({ purpose: 'identity-pin', pinId: unhex(idOf(U1)) }, MK, padded))]])
    fakeServer({ blob: headFor(entries, 2), revision: 2, entries })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
  })

  it('merges on a 409 stale_revision — a concurrent device pin is preserved, not clobbered', async () => {
    const srv = fakeServer()
    srv.racedBy(storeOf({ [OTHER]: pinOf(KEY_B) }, 1))
    expect(await firstContact(U1, A)).toBe('firstUse')
    // After the re-read + re-apply + retry: BOTH contacts are pinned (the concurrent one was merged, not lost).
    expect(Object.keys(pinsOn(srv.state)).sort()).toEqual([U1, OTHER].sort())
    expect(srv.state.revision).toBe(2)
  })
})

// The chain a rotation appends to: U1 born with A, then two securings — A → B → C — each signed by both keys it retires.
function twoSteps(): { h0: BuiltChain; h1: BuiltChain; h2: BuiltChain } {
  const h0 = chains.born(U1, A)
  const h1 = step(h0, B)
  const h2 = step(h1, C)
  return { h0, h1, h2 }
}

describe('anchorIdentity — a chain that advanced from the pinned head (ADR 0111 decisions 7, 15)', () => {
  it('two real steps from the pinned head are accepted: the pin moves to the new head, key and index', async () => {
    const { h0, h2 } = twoSteps()
    chains.serve(h2)
    const srv = fakeServer(storeOf({ [U1]: pinAt(h0, 42) }, 1))
    expect(await anchorIdentity(U1, servedIdentity(h2))).toBe('advanced')
    expect(pinsOn(srv.state)[U1]).toEqual({ fingerprint: fp(KEY_C), head: headOf(h2), index: 2, firstSeenAt: 42, replaced: false })
    // The pin moved: the same head and key again is a local match.
    expect(await anchorIdentity(U1, servedIdentity(h2))).toBe('match')
    expect(chains.getKeyChain).toHaveBeenCalledTimes(1)
  })

  it('a step signed by the retired identity only — its proof signature by another key — is an anomaly', async () => {
    const h0 = chains.born(U1, A)
    const forged = step(h0, B, { proofPrivateKey: chainKeys().proof.privateKey }) // a thief holds A's identity, not its Secret Key
    chains.serve(forged)
    const srv = fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    expect(await anchorIdentity(U1, servedIdentity(forged))).toBe('anomaly')
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(h0)) // the pin did not move
    // control: the same step signed by both retired keys advances
    const honest = step(h0, B)
    chains.serve(honest)
    expect(await anchorIdentity(U1, servedIdentity(honest))).toBe('advanced')
  })

  it('a step whose identity signature is by another key is an anomaly', async () => {
    const h0 = chains.born(U1, A)
    const forged = step(h0, B, { identitySignKey: chainKeys().identity.priv.sign })
    chains.serve(forged)
    fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    expect(await anchorIdentity(U1, servedIdentity(forged))).toBe('anomaly')
  })

  it('the prefix attack: a chain naming the pinned identity beside another proof key, behind an invented prefix, is an anomaly', async () => {
    // The member was pinned at their honest genesis: identity A with A's proof key.
    const honest = chains.born(U1, A)
    const srv = fakeServer(storeOf({ [U1]: pinAt(honest) }, 1))
    // The operator invents a genesis Z, a step Z → (A's identity, a proof key X it holds), and a thief holding A's
    // identity signs the next step with it and X: every signature in this chain verifies.
    const x: ChainKeys = { identity: A.identity, proof: chainKeys().proof }
    const mallory = chainKeys()
    const invented = step(step(genesis(U1, chainKeys()), x), mallory)
    expect(v1.verifyKeyChain({ userId: U1, links: invented.steps.map(toLink) }, null).ok).toBe(true)
    chains.serve(invented)
    expect(await anchorIdentity(U1, servedIdentity(invented))).toBe('anomaly')
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(honest))
    // control: the honest chain to a new key is advanced
    const rotated = step(honest, B)
    chains.serve(rotated)
    expect(await anchorIdentity(U1, servedIdentity(rotated))).toBe('advanced')
  })

  it('a rollback — a served head older than the pinned one — is an anomaly, served with its short chain or the whole one', async () => {
    const { h1, h2 } = twoSteps()
    const srv = fakeServer(storeOf({ [U1]: pinAt(h2) }, 1))
    chains.serve(h1) // the chain cut back to h1
    expect(await anchorIdentity(U1, servedIdentity(h1))).toBe('anomaly')
    chains.serve(h2) // the whole chain, but the older head served beside the key at it
    expect(await anchorIdentity(U1, servedIdentity(h1))).toBe('anomaly')
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(h2))
    // control: the pinned head itself is a match
    expect(await anchorIdentity(U1, servedIdentity(h2))).toBe('match')
  })

  it('a chain that advanced but whose newest key is not the served one is an anomaly', async () => {
    const { h0, h1 } = twoSteps()
    chains.serve(h1)
    fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    expect(await anchorIdentity(U1, { identityPublicKey: KEY_C, keyChainHead: headOf(h1) })).toBe('anomaly')
  })

  it('a chain served with a head field its steps do not make is an anomaly', async () => {
    const { h0, h1 } = twoSteps()
    chains.serve({ userId: U1, head: SOME_HEAD, steps: h1.steps })
    fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    expect(await anchorIdentity(U1, { identityPublicKey: KEY_B, keyChainHead: SOME_HEAD })).toBe('anomaly')
  })

  it('another account\'s chain is an anomaly, whatever it holds', async () => {
    const theirs = genesis(U2, A)
    fakeServer()
    chains.serve({ ...servedChain(theirs), userId: U1 })
    expect(await anchorIdentity(U1, servedIdentity(theirs))).toBe('anomaly')
  })

  it('a move keeps a newer move another device made meanwhile', async () => {
    const { h0, h1, h2 } = twoSteps()
    chains.serve(h1)
    const srv = fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    srv.racedBy(storeOf({ [U1]: pinAt(h2) }, 2)) // another device already moved the pin on to h2
    expect(await anchorIdentity(U1, servedIdentity(h1))).toBe('advanced')
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(h2))
  })
})

describe('anchorChainKey — a key served without a head (the per-grant snapshot, decision 9)', () => {
  it('the pinned key is a match with no chain read', async () => {
    const h0 = chains.born(U1, A)
    fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    expect(await anchorChainKey(U1, KEY_A)).toEqual({ anchor: 'match', retired: false })
    expect(chains.getKeyChain).not.toHaveBeenCalled()
  })

  it('the newest key of a chain that advanced moves the pin; an earlier key of it is answered as retired', async () => {
    const { h0, h2 } = twoSteps()
    chains.serve(h2)
    const srv = fakeServer(storeOf({ [U1]: pinAt(h0) }, 1))
    expect(await anchorChainKey(U1, KEY_B)).toEqual({ anchor: 'advanced', retired: true })
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(h2))
    expect(await anchorChainKey(U1, KEY_C)).toEqual({ anchor: 'match', retired: false })
    expect(await anchorChainKey(U1, KEY_A)).toEqual({ anchor: 'match', retired: true }) // read again: the pinned head's past
  })

  it('first use pins the chain\'s head; a key the chain does not hold, or no chain served, is an anomaly', async () => {
    const h0 = chains.born(U1, A)
    const srv = fakeServer()
    expect(await anchorChainKey(U1, KEY_B)).toEqual({ anchor: 'anomaly', retired: false })
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
    expect(await anchorChainKey(U2, KEY_A)).toEqual({ anchor: 'anomaly', retired: false })
    expect(await anchorChainKey(U1, KEY_A)).toEqual({ anchor: 'firstUse', retired: false })
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(h0))
  })

  it('a key behind an invented prefix is an anomaly even where the prefix holds it', async () => {
    const honest = chains.born(U1, A)
    fakeServer(storeOf({ [U1]: pinAt(honest) }, 1))
    const invented = step(step(genesis(U1, chainKeys()), { identity: A.identity, proof: chainKeys().proof }), B)
    chains.serve(invented)
    expect(await anchorChainKey(U1, KEY_B)).toEqual({ anchor: 'anomaly', retired: false })
  })
})

// ADR 0111 decisions 12, 15 and 16 — in a tenant, the join record anchors the person: the walk starts at the step that
// installs the identity AND proof key the record names, never at a prefix the server serves before it, and the pin stays
// the counter. The standing is handed in as `tenantTrust.ts` computes it (`tenantTrust.test.ts` judges the records
// themselves); the chains, the pin store and the walk are real.
describe('in a tenant, the join record anchors the chain (ADR 0111 decisions 12, 15, 16)', () => {
  const D = chainKeys()
  /** The tenant's word on `userId`: joined with `keys`, or another standing. */
  const tenantWhere = (standing: Standing): TenantStandings => ({ tenantId: 'tenant-1', standing: async () => standing })
  const joinedWith = (keys: ChainKeys) => tenantWhere({
    kind: 'joined', identityPublicKey: v1.serializeIdentityPublic(keys.identity.pub), secretKeyProofPublicKey: keys.proof.publicKey,
  })

  it('the chain from the join record\'s step reaches the served head: anchored (first use, then advanced from the pin)', async () => {
    const { h1, h2 } = twoSteps()
    chains.serve(h2)
    const srv = fakeServer()
    expect(await anchorIdentity(U1, servedIdentity(h2), { tenant: joinedWith(B) })).toBe('firstUse')
    expect(pinsOn(srv.state)[U1]).toMatchObject({ fingerprint: fp(KEY_C), head: headOf(h2), index: 2, replaced: false })

    const srv2 = fakeServer(storeOf({ [U1]: pinAt(h1, 3) }, 1))
    forgetVerifiedPins()
    expect(await anchorIdentity(U1, servedIdentity(h2), { tenant: joinedWith(B) })).toBe('advanced')
    expect(pinsOn(srv2.state)[U1]).toEqual({ fingerprint: fp(KEY_C), head: headOf(h2), index: 2, firstSeenAt: 3, replaced: false })
  })

  it('a chain on which no step installs the join record\'s keys is an anomaly, and pins nothing (control: the same chain, its own keys)', async () => {
    const { h2 } = twoSteps()
    chains.serve(h2)
    const srv = fakeServer()
    expect(await anchorIdentity(U1, servedIdentity(h2), { tenant: joinedWith(D) })).toBe('anomaly')
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
    expect(await anchorIdentity(U1, servedIdentity(h2), { tenant: joinedWith(A) })).toBe('firstUse')
  })

  it('the join record\'s keys beside another proof key do not anchor: the step must install BOTH', () => {
    const { h2 } = twoSteps()
    const halfB = { kind: 'joined' as const, identityPublicKey: v1.serializeIdentityPublic(B.identity.pub), secretKeyProofPublicKey: D.proof.publicKey }
    expect(readJoinedChain(undefined, U1, servedChain(h2), halfB)).toEqual({ anchor: 'anomaly' })
    const fullB = { ...halfB, secretKeyProofPublicKey: B.proof.publicKey }
    expect(readJoinedChain(undefined, U1, servedChain(h2), fullB)).toMatchObject({ anchor: 'firstUse', joinedAt: 1, index: 2 })
  })

  it('a key before the join step is refused by anchorChainKey; the join step\'s own key is a retired one, the newest is current', async () => {
    const { h2 } = twoSteps()
    chains.serve(h2)
    fakeServer()
    expect(await anchorChainKey(U1, KEY_A, { tenant: joinedWith(B) })).toEqual({ anchor: 'anomaly', retired: false })
    expect(await anchorChainKey(U1, KEY_B, { tenant: joinedWith(B) })).toMatchObject({ retired: true })
    expect((await anchorChainKey(U1, KEY_B, { tenant: joinedWith(B) })).anchor).not.toBe('anomaly')
    expect(await anchorChainKey(U1, KEY_C, { tenant: joinedWith(B) })).toEqual({ anchor: 'match', retired: false })
  })

  it('a served head behind the pinned one is an anomaly — the pin does not move back (control: the pinned head is a match)', async () => {
    const { h1, h2 } = twoSteps()
    chains.serve(h1) // the server rolls the chain back by one step
    const srv = fakeServer(storeOf({ [U1]: pinAt(h2, 5) }, 1))
    expect(await anchorIdentity(U1, servedIdentity(h1), { tenant: joinedWith(A) })).toBe('anomaly')
    expect(pinsOn(srv.state)[U1].head).toBe(headOf(h2))
    chains.serve(h2)
    expect(await anchorIdentity(U1, servedIdentity(h2), { tenant: joinedWith(A) })).toBe('match')
  })

  it('a pin NOT on the anchored chain, the chain at least as long, is replaced: the pin moves to the anchored head, marked', async () => {
    const h0 = chains.born(U1, A)
    // The operator served another key at the first contact, before the join record was read; it was pinned at index 0.
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_C, 5, SOME_HEAD, { index: 0 }) }, 1))
    expect(await anchorIdentity(U1, servedIdentity(h0), { tenant: joinedWith(A) })).toBe('replaced')
    const pin = pinsOn(srv.state)[U1]
    expect(pin).toMatchObject({ fingerprint: fp(KEY_A), head: headOf(h0), index: 0, replaced: true })
    expect(pin.firstSeenAt).not.toBe(5) // new to this account under the anchored key
    // control: the pin is now on the chain — the same read again is a plain match
    expect(await anchorIdentity(U1, servedIdentity(h0), { tenant: joinedWith(A) })).toBe('match')
  })

  it('a pin NOT on the anchored chain, the chain SHORTER than the pinned one was, is an anomaly — it may be a rollback', async () => {
    const h0 = chains.born(U1, A)
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_C, 5, SOME_HEAD, { index: 1 }) }, 1))
    expect(await anchorIdentity(U1, servedIdentity(h0), { tenant: joinedWith(A) })).toBe('anomaly')
    expect(pinsOn(srv.state)[U1]).toEqual(pinOf(KEY_C, 5, SOME_HEAD, { index: 1 }))
  })

  it('a pin taken unread at an invite accept (index -1) and not on the chain is no replacement either', () => {
    const h0 = genesis(U1, A)
    const joined = { kind: 'joined' as const, identityPublicKey: v1.serializeIdentityPublic(A.identity.pub), secretKeyProofPublicKey: A.proof.publicKey }
    expect(readJoinedChain(pinOf(KEY_C, 5, SOME_HEAD, { index: -1 }), U1, servedChain(h0), joined)).toEqual({ anchor: 'anomaly' })
    expect(readJoinedChain(pinOf(KEY_C, 5, SOME_HEAD, { index: 0 }), U1, servedChain(h0), joined)).toMatchObject({ anchor: 'replaced' })
  })

  it('a pinned head ON the chain must carry the pinned key — another key there is an anomaly, never a replacement', () => {
    const { h0, h1 } = twoSteps()
    const joined = { kind: 'joined' as const, identityPublicKey: v1.serializeIdentityPublic(A.identity.pub), secretKeyProofPublicKey: A.proof.publicKey }
    expect(readJoinedChain(pinOf(KEY_C, 5, headOf(h0), { index: 0 }), U1, servedChain(h1), joined)).toEqual({ anchor: 'anomaly' })
    expect(readJoinedChain(pinAt(h0, 5), U1, servedChain(h1), joined)).toMatchObject({ anchor: 'advanced' })
  })

  it('unanchored in the tenant is an anomaly with no chain read and no pin; so is a tenant this device cannot read, for a wrap', async () => {
    chains.born(U1, A)
    const srv = fakeServer()
    const served = servedIdentity(genesis(U1, A))
    expect(await anchorIdentity(U1, served, { tenant: tenantWhere({ kind: 'unanchored' }) })).toBe('anomaly')
    expect(await anchorIdentity(U1, served, { tenant: tenantWhere({ kind: 'unavailable' }) })).toBe('anomaly')
    expect(await anchorChainKey(U1, KEY_A, { tenant: tenantWhere({ kind: 'unanchored' }) })).toEqual({ anchor: 'anomaly', retired: false })
    expect(chains.getKeyChain).not.toHaveBeenCalled()
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
    // control: a root owner is judged by the pin, as without a tenant — first use
    expect(await anchorIdentity(U1, served, { tenant: tenantWhere({ kind: 'root' }) })).toBe('firstUse')
  })

  it('a grant\'s granter in a tenant this device cannot read falls back to the pin alone (decision 4 of the implementation)', async () => {
    chains.born(U1, A)
    fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 1))
    expect(await anchorChainKey(U1, KEY_A, { tenant: tenantWhere({ kind: 'unavailable' }) })).toEqual({ anchor: 'match', retired: false })
    expect(await anchorChainKey(U1, KEY_A, { tenant: tenantWhere({ kind: 'unanchored' }) })).toEqual({ anchor: 'anomaly', retired: false })
  })
})

describe('readChain is pure and never throws on served bytes', () => {
  it('a step that is no base64 is an anomaly', () => {
    const chain = servedChain(genesis(U1, A))
    expect(readChain(undefined, U1, { ...chain, steps: [{ ...chain.steps[0], identityPublicKey: '***' }] })).toEqual({ anchor: 'anomaly' })
  })

  it('a rotation\'s date is its newest step\'s; a chain never rotated has none', () => {
    const h0 = genesis(U1, A)
    const h1 = step(h0, B, { createdAt: new Date('2026-10-20T12:00:00Z') })
    expect(readChain(undefined, U1, servedChain(h0))).toMatchObject({ anchor: 'firstUse', rotatedAt: null })
    expect(readChain(pinAt(h0), U1, servedChain(h1))).toMatchObject({ anchor: 'advanced', rotatedAt: new Date('2026-10-20T12:00:00Z') })
  })
})

// Inbox #491: the single 1 MiB blob refused the 445th pin, and the grant-delivery worker could wrap to nobody after it.
// The worker's pin step is anchorIdentity; driven here for 1,000 contacts against the per-entry store, every write is one
// entry plus the head — the 445th and the 1,000th exactly like the first — and the whole set reads back.
describe('a key-holder pins past the old ceiling (ADR 0104, inbox #491)', () => {
  it('the 445th and the 1,000th contact pin as the first did: one entry a write, the set reads back whole', async () => {
    const srv = fakeServer()
    const contacts = Array.from({ length: 1000 }, (_, i) => {
      const userId = `c0ffee00-0000-4000-8000-${String(i).padStart(12, '0')}`
      return { userId, ...servedIdentity(chains.born(userId, chainKeys())) }
    })
    for (const c of contacts) expect(await anchorIdentity(c.userId, c)).toBe('firstUse')
    expect(srv.state.revision).toBe(1000)
    expect(srv.state.entries.size).toBe(1000)
    const sizes = srv.writeIdentityPins.mock.calls.map(([req]) => JSON.stringify(req.identityPinsWrite).length)
    for (const n of [1, 445, 1000]) {
      const w = srv.writeIdentityPins.mock.calls[n - 1][0].identityPinsWrite
      expect(w.put, `write ${n}`).toHaveLength(1)
      expect(w.replaceAll).toBe(false)
    }
    // A write's size does not grow with the store (the head's digest is fixed-size; the count adds a digit at most).
    expect(Math.max(...sizes) - Math.min(...sizes)).toBeLessThanOrEqual(8)
    forgetVerifiedPins() // a fresh session lists the whole set and checks it against the head
    srv.listIdentityPinEntries.mockClear()
    for (const c of [contacts[0], contacts[444], contacts[999]]) {
      expect(await getPin(c.userId)).toMatchObject({ fingerprint: fp(c.identityPublicKey), head: c.keyChainHead })
    }
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(1) // the whole set, listed once for the three reads
  }, 120_000)
})

// The safety-number the ceremony compares out-of-band; must match the pinned `fingerprint` conformance vector.
const IDENTITY_FROM_VECTOR = b64std(
  v1.serializeIdentityPublic({ kem: unhex(vectors.fingerprint.kem), sign: unhex(vectors.fingerprint.sign) }),
)

describe('safetyNumber', () => {
  it('renders the 6×5 digit safety number and matches the fingerprint vector', () => {
    expect(safetyNumber(IDENTITY_FROM_VECTOR)).toBe(vectors.fingerprint.digits)
  })

  it('a pin\'s stored fingerprint renders the same number the bundle does (ADR 0104 decision 3)', () => {
    expect(fp(IDENTITY_FROM_VECTOR)).toBe(b64std(unhex(vectors.fingerprint.hash)))
    expect(safetyNumberOfFingerprint(fp(IDENTITY_FROM_VECTOR))).toBe(vectors.fingerprint.digits)
    expect(() => safetyNumberOfFingerprint(b64std(new Uint8Array(31)))).toThrow()
  })
})

describe('myIdentityPublicKeyB64', () => {
  it('reconstructs my own identity bundle from the vault private key → a stable safety-number', () => {
    const id = v1.generateIdentity()
    vi.mocked(identityPrivate).mockReturnValue({ kemSeed: id.priv.kemSeed.slice(), sign: id.priv.sign.slice() })
    expect(safetyNumber(myIdentityPublicKeyB64())).toBe(v1.fingerprint(id.pub))
  })

  it('throws when the vault is locked', () => {
    vi.mocked(identityPrivate).mockReturnValue(null)
    expect(() => myIdentityPublicKeyB64()).toThrow()
  })
})

// ADR 0028 Decision 7 — a device never accepts older trust state than it has held. Every case below fixes its lever
// (the payload the server serves) inside the test, and every negative names the refusal it must meet.
describe('the pin store never goes backwards on this device (ADR 0028 Decision 7)', () => {
  const EMPTY: ServerState = { blob: undefined, revision: 0, entries: new Map() }

  it('wipe: once this device opened v3, a missing store is refused — another key is not pinned as a first use', async () => {
    const { served, chain } = born(U1, A)
    fakeServer(storeOf({ [U1]: pinAt(chain) }, 3))
    expect(await anchorIdentity(U1, served)).toBe('match') // this device has now held v3
    const wiped = fakeServer(EMPTY)
    await expect(firstContact(U1, B)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(wiped.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('control: a device with no mark reads a missing store as empty, and pins on first use', async () => {
    const srv = fakeServer(EMPTY)
    expect(await firstContact(U1, B)).toBe('firstUse')
    expect(srv.state.revision).toBe(1)
  })

  it('unopenable: a garbage head is refused as unreadable, never read as the empty store', async () => {
    const srv = fakeServer({ blob: b64std(v1.randomBytes(64)), revision: 2, entries: new Map() })
    await expect(firstContact(U1, B)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('rollback: a store below the mark cannot reinstate a retired key as a match', async () => {
    const h0 = chains.born(U1, A)
    const h1 = step(h0, B)
    fakeServer(storeOf({ [U1]: pinAt(h1) }, 3))
    expect((await getPin(U1))?.fingerprint).toBe(fp(KEY_B)) // this device has held v3, where the pin is the new key
    const rolled = fakeServer(storeOf({ [U1]: pinAt(h0) }, 2)) // v2 still pins the retired one
    await expect(anchorIdentity(U1, servedIdentity(h0))).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('a write raises the mark to the revision it stored — the next missing store is refused', async () => {
    const srv = fakeServer(EMPTY)
    expect(await firstContact(U1, B)).toBe('firstUse') // stored at v1 by this device
    expect(srv.state.revision).toBe(1)
    fakeServer(EMPTY)
    await expect(firstContact(U1, A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('the open before a write is checked too: a move over a rolled-back store writes nothing', async () => {
    const h0 = chains.born(U1, A)
    chains.serve(step(h0, B))
    fakeServer(storeOf({ [U1]: pinAt(h0) }, 3))
    await getPin(U1)
    const rolled = fakeServer(storeOf({ [U1]: pinAt(h0) }, 2))
    await expect(anchorIdentity(U1, servedIdentity(step(h0, B)))).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(rolled.writeIdentityPins).not.toHaveBeenCalled()
  })
})

// ADR 0104 decision 4 — the same refusal PER ENTRY. The head is honest in every case below (sealed, at the mark); the
// server's lever is the listing. Each negative anchors on `trust_state_rolled_back` — the seal alone would refuse a moved
// entry as UNREADABLE, so the code is what proves the digest did the refusing — and the control serves the same head
// with the stored entries. The digest check is what every negative here rests on: skipped, each reads as a store.
describe('a withheld, added, rolled-back or moved entry is refused (ADR 0104 decision 4)', () => {
  function threePins() {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B), [U3]: pinOf(KEY_C) }, 4))
    return srv
  }

  it('control: the stored entries under the stored head open to every pin', async () => {
    threePins()
    expect(await getPin(U1)).toEqual(pinOf(KEY_A))
    expect(await getPin(U2)).toEqual(pinOf(KEY_B))
  })

  it('withheld: an entry left out of the listing — another key would read as a first use', async () => {
    const srv = threePins()
    srv.tamper((e) => (e.delete(idOf(U2)), e))
    await expect(firstContact(U2, C)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
    expect(srv.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('added: an entry the head does not count — one copied in from another slot', async () => {
    const srv = threePins()
    srv.tamper((e) => e.set(idOf(OTHER), e.get(idOf(U1))!))
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('rolled back: an older ciphertext of one entry — a pin this device has since changed reinstated', async () => {
    const h0 = chains.born(U1, A)
    const h1 = step(h0, B)
    chains.serve(h1)
    const srv = fakeServer(storeOf({ [U1]: pinAt(h0), [U2]: pinOf(KEY_B), [U3]: pinOf(KEY_C) }, 4))
    const before = srv.state.entries.get(idOf(U1))!
    expect(await anchorIdentity(U1, servedIdentity(h1))).toBe('advanced') // this device rewrites U1's entry (head v5)
    srv.tamper((e) => e.set(idOf(U1), before)) // the server serves U1's old entry under the new head
    forgetVerifiedPins() // as a fresh session would: list again
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('moved: one contact\'s entry served at another contact\'s id', async () => {
    const srv = threePins()
    srv.tamper((e) => {
      const u1 = e.get(idOf(U1))!
      e.delete(idOf(U1))
      return e.set(idOf(OTHER), u1)
    })
    await expect(firstContact(OTHER, A)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })

  it('a head that moved between its read and the listing is read again, not refused', async () => {
    const srv = threePins()
    // Another device's write lands after this device read the head and before it listed: the listing is the new set.
    const next = storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B), [U3]: pinOf(KEY_C), [OTHER]: pinOf(KEY_A) }, 5)
    srv.listIdentityPinEntries.mockImplementationOnce(async () => {
      Object.assign(srv.state, { blob: next.blob, revision: next.revision, entries: new Map(next.entries) })
      return { entries: [...next.entries].sort(([a], [b]) => (a < b ? -1 : 1)).map(([pinId, entry]) => ({ pinId, entry })), more: false }
    })
    expect((await getPin(OTHER))?.fingerprint).toBe(fp(KEY_A))
    // The head, then the check that it moved — which is already the new head, opened and listed again.
    expect(srv.getIdentityPins).toHaveBeenCalledTimes(2)
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(2)
  })

  it('an entry whose contact does not derive its id is refused as unreadable, even inside a matching digest', async () => {
    // Only a holder of the Master Key can build this (the digest covers it): the check is the reader's own, so an
    // entry sealed for one contact never answers for another's slot.
    const entries = new Map([[idOf(OTHER), sealEntryAs(U1, pinOf(KEY_A), idOf(OTHER))]])
    fakeServer({ blob: headFor(entries, 2), revision: 2, entries })
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
  })

  it('a listing that does not advance is refused, not followed', async () => {
    const srv = threePins()
    const first = idOf(U1) < idOf(U2) ? U1 : U2
    srv.listIdentityPinEntries.mockImplementation(async () => ({
      entries: [{ pinId: idOf(first), entry: srv.state.entries.get(idOf(first))! }],
      more: true,
    }))
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_unreadable' })
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(2)
  })
})

describe('the entries are listed once per head (ADR 0104 decision 6)', () => {
  it('pages through the whole set by pinId', async () => {
    const pins = Object.fromEntries([U1, U2, U3, OTHER].map((u) => [u, pinOf(KEY_A)]))
    const srv = fakeServer(storeOf(pins, 1), 1) // one entry a page
    for (const u of [U1, U2, U3, OTHER]) expect(await getPin(u)).toEqual(pinOf(KEY_A))
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(4) // four pages, read once for all four
  })

  it('a second read under the same head lists nothing; a head another device moved lists again', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 1))
    await getPin(U1)
    await getPin(U1)
    expect(srv.getIdentityPins).toHaveBeenCalledTimes(2)
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(1)
    const next = storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B) }, 2)
    Object.assign(srv.state, { blob: next.blob, revision: next.revision, entries: next.entries })
    expect((await getPin(U2))?.fingerprint).toBe(fp(KEY_B))
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(2)
  })

  it('this device\'s own write is kept as verified: the next read is the head alone', async () => {
    const srv = fakeServer()
    const { served } = born(U1, A)
    await anchorIdentity(U1, served)
    const listed = srv.listIdentityPinEntries.mock.calls.length
    expect(await anchorIdentity(U1, served)).toBe('match')
    expect(srv.listIdentityPinEntries).toHaveBeenCalledTimes(listed)
  })

  it('a cached set is no defence a server can lean on: a rolled-back head is still refused by the marks', async () => {
    fakeServer(storeOf({ [U1]: pinOf(KEY_B) }, 3))
    await getPin(U1)
    fakeServer(storeOf({ [U1]: pinOf(KEY_A) }, 2))
    await expect(getPin(U1)).rejects.toMatchObject({ code: 'trust_state_rolled_back' })
  })
})

// The rotation and the reset act on the store WHOLE, through `pinStoreDocument` — their behaviour is proven in
// `rotation/rotateKeys.test.ts` and `trustReset/trustReset.test.ts`, against this real module. What is pinned here
// is that the whole-document view is the same store: the same open (marks and digest), the whole set as one write.
describe('pinStoreDocument — the store as one whole document (ADR 0028 Decision 7, ADR 0104)', () => {
  const pinned = { [U1]: pinOf(KEY_A) }

  it('opens through the same marks as every read: a store below the mark is refused, and nothing is written', async () => {
    fakeServer(storeOf(pinned, 3))
    await getPin(U1) // this device has held v3
    const rolled = fakeServer(storeOf(pinned, 2))
    await expect(pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())).rejects.toMatchObject({
      code: 'trust_state_rolled_back',
    })
    expect(rolled.writeIdentityPins).not.toHaveBeenCalled()
  })

  it('and through the same digest: a withheld entry is refused there too', async () => {
    const srv = fakeServer(storeOf(pinned, 3))
    srv.tamper(() => new Map())
    await expect(pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())).rejects.toMatchObject({
      code: 'trust_state_rolled_back',
    })
  })

  it('control: at the mark it opens to the pins, and its seal is the whole set as one replacing write', async () => {
    const srv = fakeServer(storeOf(pinned, 3))
    const pins = await pinStoreDocument.open(MK.slice(), await pinStoreDocument.fetch())
    expect(pins).toEqual(pinned)
    const sealed = pinStoreDocument.seal(MK.slice(), pins, 4)
    expect(sealed).toMatchObject({ remove: [], replaceAll: true })
    await pinStoreDocument.write({ ...sealed, revision: 3 })
    expect(srv.state.revision).toBe(4)
    expect(pinsOn(srv.state)).toEqual(pinned)
  })

  it('sealed under another Master Key, every entry moves to that key\'s ids (the rotation re-keys them)', () => {
    const other = Uint8Array.from(v1.randomBytes(32))
    const sealed = pinStoreDocument.seal(other.slice(), pinned, 4)
    expect(sealed.put.map((e) => e.pinId)).toEqual([idOf(U1, other)])
    expect(sealed.put[0].pinId).not.toBe(idOf(U1))
  })

  it('the empty set is a head of no entries — the reset leaves no entry', async () => {
    const srv = fakeServer(storeOf({ [U1]: pinOf(KEY_A), [U2]: pinOf(KEY_B) }, 3))
    await pinStoreDocument.write({ ...pinStoreDocument.seal(MK.slice(), pinStoreDocument.empty(), 4), revision: 3 })
    expect(srv.state.entries.size).toBe(0)
    expect(pinsOn(srv.state)).toEqual({})
  })
})

/** A built chain as the server serves it. */
function servedChain(chain: BuiltChain) {
  return { userId: chain.userId, head: headOf(chain), steps: chain.steps }
}

/** A served step decoded the way `readChain` decodes it — for asserting that an attack chain verifies on its own. */
function toLink(s: BuiltChain['steps'][number]): v1.KeyChainLink {
  const opt = (b: string | null | undefined) => (b == null ? null : fromB64std(b))
  return {
    index: s.index, previousHead: opt(s.previousHead), identityPublicKey: fromB64std(s.identityPublicKey),
    secretKeyProofPublicKey: fromB64std(s.secretKeyProofPublicKey), identitySignature: opt(s.identitySignature),
    proofSignature: opt(s.proofSignature),
  }
}
