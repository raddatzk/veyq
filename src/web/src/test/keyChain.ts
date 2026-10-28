// Real key chains for the tests that judge a served key against the head this account pinned (cr-secure-the-account
// decision 9): every step is built by `@veyq/crypto`'s own statement and signed by real keys, so a test's chain is one
// `verifyKeyChain` walks — or, for the attacks, one built exactly as an attacker holding some of the keys would build it.
// The wire shape is the generated `KeyChain` the server serves (`getKeyChain`), base64 throughout.
import { vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { keyChainApi } from '../api/client'
import type { KeyChain, KeyChainApi, KeyChainStep } from '../api/generated/scopes'
import { fakeApi } from './apiMock'
import { httpError } from './httpError'

/** The keys one step of a chain installs, with their private halves: an identity and a Secret-Key proof keypair. */
export type ChainKeys = { identity: v1.Identity; proof: { publicKey: Uint8Array; privateKey: Uint8Array } }

/** A fresh identity and proof keypair. */
export const chainKeys = (identity: v1.Identity = v1.generateIdentity()): ChainKeys => ({
  identity,
  proof: v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)),
})

/** The identity public bundle of `keys`, base64 — what a directory serves as `identityPublicKey`. */
export const bundleB64 = (keys: ChainKeys): string => b64std(v1.serializeIdentityPublic(keys.identity.pub))

const installed = (k: ChainKeys): v1.KeyChainKeys => ({
  identityPublicKey: v1.serializeIdentityPublic(k.identity.pub),
  secretKeyProofPublicKey: k.proof.publicKey,
})

/** A chain under construction: its steps as served, and the head of its newest step (raw). */
export type BuiltChain = { userId: string; steps: KeyChainStep[]; head: Uint8Array; keys: ChainKeys[] }

/** Step 0: `userId` born with `born` — unsigned, no predecessor. */
export function genesis(userId: string, born: ChainKeys, createdAt = new Date('2026-01-01T00:00:00Z')): BuiltChain {
  const head = v1.keyChainHead(v1.keyChainGenesisStatement(userId, installed(born)))
  const step: KeyChainStep = {
    index: 0, previousHead: null, identityPublicKey: bundleB64(born), secretKeyProofPublicKey: b64std(born.proof.publicKey),
    identitySignature: null, proofSignature: null, createdAt,
  }
  return { userId, steps: [step], head, keys: [born] }
}

/**
 * One more step, retiring the newest keys for `next`, signed by `signers` — the retired identity and proof key by
 * default, as an honest rotation signs it; an attack passes other signing keys.
 */
export function step(
  chain: BuiltChain, next: ChainKeys,
  o: { identitySignKey?: Uint8Array; proofPrivateKey?: Uint8Array; createdAt?: Date } = {},
): BuiltChain {
  const retired = chain.keys.at(-1)!
  const index = chain.steps.length
  const statement = v1.keyChainStepStatement({
    userId: chain.userId, index, previousHead: chain.head, retired: installed(retired), installed: installed(next),
  })
  const sig = v1.signKeyChainStep(
    statement, o.identitySignKey ?? retired.identity.priv.sign, o.proofPrivateKey ?? retired.proof.privateKey,
  )
  const served: KeyChainStep = {
    index, previousHead: b64std(chain.head), identityPublicKey: bundleB64(next), secretKeyProofPublicKey: b64std(next.proof.publicKey),
    identitySignature: b64std(sig.identitySignature), proofSignature: b64std(sig.proofSignature),
    createdAt: o.createdAt ?? new Date(Date.UTC(2026, 0, 1 + index)),
  }
  return { userId: chain.userId, steps: [...chain.steps, served], head: v1.keyChainHead(statement), keys: [...chain.keys, next] }
}

/** The chain as `getKeyChain` serves it. */
export const served = (chain: BuiltChain): KeyChain => ({ userId: chain.userId, head: b64std(chain.head), steps: chain.steps })

/** The head of `chain` (its newest step), base64 — what a directory serves as `keyChainHead`. */
export const headOf = (chain: BuiltChain): string => b64std(chain.head)

/** The head of the chain `userId` was born with under `identity` and a fresh proof key — for a fixture that needs a real
 *  head beside a key and reads no chain. */
export const genesisHeadOf = (userId: string, identity: v1.Identity): string => headOf(genesis(userId, chainKeys(identity)))

/** What a directory serves for a contact: the newest key of their chain and its head. */
export const servedIdentity = (chain: BuiltChain): { identityPublicKey: string; keyChainHead: string } => ({
  identityPublicKey: bundleB64(chain.keys.at(-1)!),
  keyChainHead: headOf(chain),
})

/**
 * A fake `getKeyChain` (the `key-chain` tag): serves the chains a test registers, and `404 not_found` for anyone else —
 * the answer the server gives for a person who shares no tenant with the caller. The test's `vi.mock` of
 * `api/client` must make `keyChainApi` a `vi.fn`.
 */
export function fakeKeyChains() {
  const chains = new Map<string, KeyChain>()
  const getKeyChain = vi.fn<KeyChainApi['getKeyChain']>(async ({ userId }) => {
    const chain = chains.get(userId)
    if (!chain) throw httpError(404, 'not_found')
    return chain
  })
  fakeApi(keyChainApi, { getKeyChain })
  return {
    getKeyChain,
    /** Serve `chain` (as built, or as an attacker assembled it) for its user from now on. */
    serve(chain: BuiltChain | KeyChain): void {
      chains.set(chain.userId, 'keys' in chain ? served(chain) : chain)
    },
    /** `userId` born with `keys`: their genesis chain served; the chain to step on. */
    born(userId: string, keys: ChainKeys = chainKeys()): BuiltChain {
      const chain = genesis(userId, keys)
      chains.set(userId, served(chain))
      return chain
    },
  }
}
