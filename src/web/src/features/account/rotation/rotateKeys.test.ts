import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { fakeApi } from '../../../test/apiMock'
import type {
  AccountApi,
  AccountDeviceRegisterApi,
  AccountRotationApi,
  DeviceRegister,
  MasterKeyWrap,
  RotateAccountKeysRequest,
  RotationBlockedReason,
  RotationPlanInvite,
  RotationPlanJoinRecord,
  StagedInviteResign,
  StagedJoinEndorsement,
  StagedModerationGrant,
  StagedSelfGrant,
} from '../../../api/generated/account'
import type { ModerationApi } from '../../../api/generated/moderation'
import { emptyPinStore, fakePinServer, identityKey, pinIdOf, pinOf, pinsOn, sealPinStore, type PinServerState } from '../../../test/pinStore'
import type { KeyChain, KeyChainApi, KeyChainStep, ScopeMember, ScopeSummary, ScopesApi } from '../../../api/generated/scopes'
import type { TasksApi } from '../../../api/generated/tasks'
import type { LoginApi } from '../../../api/generated/auth'
import type { MemberVerification, TenantsApi } from '../../../api/generated/tenants'
import * as verification from '@veyq/crypto/verification'
import * as invite from '@veyq/crypto/invite'

// The APIs and the vault are mocked; the scope layer, the trust layer and **the crypto are real** — the wraps, the identity
// mint, the self-grants, the key-chain step and the KEK derivations all run for real (the last at a light Argon cost, see
// the worker mock below), so these tests prove that what is posted actually opens with the credential offered and
// verifies under the key the server holds, rather than that a stub was called.
//
// The fake server below is the backend as phase 2 built it (cr-secure-the-account), reduced to what the
// client meets: the plan, the stage (each self-grant verified at upload under the stage's identity), the publish (the
// stage's digest, the chain step's two signatures, the rotation proof and the possession signature, the attestation, the
// set check — every grant-carrying membership staged at its current epoch or declared), the committed account and the
// states, and `rotateScope` with its regrant-row gate, its completeness check and its recipient-key check. A client that
// sends something the real server refuses is refused here.
//
// What carries the weight is the gate (ADR 0060 Decision 2): the attacker it defends against holds the account's Master
// Key and identity key and could otherwise secure the account FIRST. What they do not hold is the Secret Key. So every
// refusal below is asserted together with "and nothing was posted" — a gate that refuses after writing is not a gate.
vi.mock('../../../api/client', () => ({
  accountApi: vi.fn(),
  accountRotationApi: vi.fn(),
  accountIdentityPinsApi: vi.fn(),
  accountDeviceRegisterApi: vi.fn(),
  scopesApi: vi.fn(),
  tasksApi: vi.fn(),
  loginApi: vi.fn(),
  keyChainApi: vi.fn(),
  tenantsApi: vi.fn(),
  moderationApi: vi.fn(),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
// A vault that holds what it is handed, as the real one does — so the published identity a rotation swaps in is the one
// every later step (the finish, the worker) opens grants with.
const vault = vi.hoisted(() => ({
  mk: null as Uint8Array | null,
  priv: null as { kemSeed: Uint8Array; sign: Uint8Array } | null,
  origin: null as 'cold' | 'device' | 'none' | null,
}))
vi.mock('../../../app/vault', () => ({
  masterKey: vi.fn(() => (vault.mk ? vault.mk.slice() : null)),
  identityPrivate: vi.fn(() => (vault.priv ? { kemSeed: vault.priv.kemSeed.slice(), sign: vault.priv.sign.slice() } : null)),
  unlockOrigin: vi.fn(() => vault.origin),
  setUnlocked: vi.fn((mk: Uint8Array, priv: { kemSeed: Uint8Array; sign: Uint8Array }, origin: 'cold' | 'device' | 'none') => {
    vault.mk = mk
    vault.priv = priv
    vault.origin = origin
  }),
  clearVault: vi.fn(() => {
    vault.mk = null
    vault.priv = null
    vault.origin = null
  }),
  isUnlocked: vi.fn(() => vault.mk !== null),
}))
vi.mock('../../../core/favorites/favorites', () => ({ resealFavoritesUnderNewMasterKey: vi.fn() }))
// This tab's session vault (`sessionStorage`): what the swap carries it to is asserted below; its sealing is its own suite.
vi.mock('../rememberMe/sessionVault', () => ({ carrySessionVault: vi.fn(), clearSessionVault: vi.fn(), loadSessionVault: vi.fn() }))
vi.mock('../../memories/itemFavorites', () => ({ retagItemMarksUnderNewMasterKey: vi.fn() }))
vi.mock('../../spaces/members', () => ({ listScopeMembers: vi.fn() }))
// The re-key after a scope's rotation (ADR 0109) is `rekeyScope`'s own suite; here it always finishes.
vi.mock('../../../core/node/rekey/rekeyScope', () => ({ rekeyScope: vi.fn(async () => ({ done: true, committed: 0 })) }))

// The Argon-bearing derivations run for REAL but at a light cost. They stay **input-sensitive**, which is what every
// refusal below depends on — a stub returning a constant would make "a wrong Secret Key is refused" pass against a gate
// that never looked. Their real-byte correctness at production cost is v1.conformance's job; what this file pins is
// the wiring: which secret reaches which wrap.
vi.mock('@veyq/crypto/argonWorker', async () => {
  const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
  const LIGHT = { t: 1, m: 64, p: 1, dkLen: 32 }
  return {
    unlockKekAsync: async (secretKey: Uint8Array, password: string, salt: Uint8Array) => real.kekPasswordSecret(secretKey, password, salt, LIGHT),
    recoveryKekAsync: async (secretKey: Uint8Array, salt: Uint8Array) => real.kekRecovery(secretKey, salt, LIGHT),
    deriveAuthKeypairAsync: async (password: string, authSalt: Uint8Array) => real.deriveAuthKeypair(password, authSalt, LIGHT),
    deviceKekAsync: async () => {
      throw new Error('no device KEK in this suite')
    },
  }
})

import { mintSecretKey, rotateKeys, RotationOutcomeUnknown, RotationRefused, STAGE_CHUNK, unreadableOnThisDevice } from './rotateKeys'
import { readRotationPlan } from './finishRotation'
import {
  accountApi,
  accountDeviceRegisterApi,
  accountRotationApi,
  keyChainApi,
  loginApi,
  moderationApi,
  scopesApi,
  tasksApi,
  tenantsApi,
} from '../../../api/client'
import { clearVault, identityPrivate, setUnlocked } from '../../../app/vault'
import { carrySessionVault, clearSessionVault } from '../rememberMe/sessionVault'
import { setAccount } from '../../../app/account'
import { forgetVerifiedPins, getPin } from '../identityPins'
import { readRegisterView } from '../deviceRegister'
import { resealFavoritesUnderNewMasterKey } from '../../../core/favorites/favorites'
import { retagItemMarksUnderNewMasterKey } from '../../memories/itemFavorites'
import { httpError } from '../../../test/httpError'
import { registry } from '../../../core/applications/registry'
import { memoriesLifecycle } from '../../memories/lifecycle'
import { listScopeMembers } from '../../spaces/members'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { grantContext, grantSignatureVerifies, recipientIdentityDigest, sealGrant } from '../../spaces/grant'
import { raiseMarks, readMarks } from '../trustMarks'
import { runScopeRotationWorker } from '../../tasks/scopeRotationWorker'
import { SCOPE_ROTATION } from '../../tasks/capabilityTags'
import { bundleB64, genesis, headOf, served, type ChainKeys } from '../../../test/keyChain'

// This file runs in the node environment (fake-indexeddb), where src/test/setup.ts registers no application. The REAL
// Memories hooks go into the REAL registry, so the re-seal tests below prove rotateKeys reaches the favorites modules
// (mocked above, at the module that seals) THROUGH the registry rather than by an import of its own.
beforeAll(() => registry.registerLifecycle('memories', memoriesLifecycle))

const PASSWORD = 'correct horse battery staple'
const EMAIL = 'me@example.test'
const SECRET_KEY = v1.randomBytes(32)
const SECRET_KEY_B64 = b64std(SECRET_KEY)
/** The account's *current* Master Key — what the stored PASSWORD wrap opens and what the vault is holding. */
const MK = v1.randomBytes(32)
/** The account's *current* identity — the one its scope grants are wrapped to, and the vault holds. */
const OLD_ID = v1.generateIdentity()
const oldPriv = () => ({ kemSeed: OLD_ID.priv.kemSeed.slice(), sign: OLD_ID.priv.sign.slice() })
const OLD_PUB = b64std(v1.serializeIdentityPublic(OLD_ID.pub))
/** The caller's account id, as its own key material names it. */
const ME = '0e000000-0000-4000-8000-0000000000e9'
/** Another member of the tenant, with a real key chain this account pinned. */
const B = 'b0000000-0000-4000-8000-0000000000b1'
const B_KEYS: ChainKeys = { identity: v1.generateIdentity(), proof: v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)) }
const B_CHAIN = genesis(B, B_KEYS)
const B_PUB = bundleB64(B_KEYS)
/** The scopes the caller holds — canonical ids, since every grant and chain link is sealed bound to its scope. */
const SCOPE_PERSONAL = '5c09e000-0000-4000-8000-0000000000e1'
const SCOPE_TENANT = '5c09e000-0000-4000-8000-0000000000e2'
const SCOPE_ELSEWHERE = '5c09e000-0000-4000-8000-0000000000e3'
const SCOPE_JOINED = '5c09e000-0000-4000-8000-0000000000e4'
/** The light cost the mocked worker above uses; every stored KEK and auth key must be built with the same. The WIRE
 *  profile stays `v1.ARGON` — the client refuses any other before deriving (ADR 0017 rule 5). */
const KDF = { t: 1, m: 64, p: 1, dkLen: 32 }
const INSTANCE_SALT = v1.randomBytes(16)
const AUTH_SALT = v1.deriveAuthSalt(INSTANCE_SALT, EMAIL)
const authPubOf = (password: string) => v1.deriveAuthKeypair(password, AUTH_SALT, KDF).publicKey
/** The new Secret Key the ceremony printed into the new kit before it published. */
let NEXT: { secretKeyB64: string; password?: string }
const nextSecretKey = () => fromB64std(NEXT.secretKeyB64)

/** The stored PASSWORD wrap, produced exactly as account birth produces it. */
function storedFloor(masterKeyBytes = MK, secret = SECRET_KEY, password = PASSWORD): MasterKeyWrap {
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(secret, password, salt, KDF)
  return {
    id: 'w1',
    method: 'PASSWORD',
    wrappedKey: b64std(v1.seal({ purpose: 'master-key' }, kek, masterKeyBytes)),
    kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)),
  }
}

/** The two sealed account documents as fixtures seal them — under the CURRENT Master Key, bound to their revision. */
const pinsAt = (revision: number) => ({ purpose: 'identity-pins', revision }) as const
const U1 = 'c0ffee00-0000-4000-8000-0000000000a1'
const U2 = 'c0ffee00-0000-4000-8000-0000000000a2'
const U3 = 'c0ffee00-0000-4000-8000-0000000000a3'
const registerAt = (revision: number) => ({ purpose: 'device-register', revision }) as const
const enc = new TextEncoder()
const dec = new TextDecoder()
/** B pinned at the head of its chain — so the finish's grant to B is `match`, not a first use. */
const PINNED = { [U1]: pinOf(identityKey()), [B]: pinOf(B_PUB, 1, headOf(B_CHAIN)) }
const sealedPins = (revision: number, pins: Record<string, ReturnType<typeof pinOf>> = PINNED, mk = MK): PinServerState =>
  sealPinStore(mk, pins, revision)
const REGISTERED = { devices: { d1: { label: 'Work laptop', lastSeenAt: 1 } }, families: { fam1: 'd1' }, wraps: {} }
const sealedRegister = (revision: number, reg: Record<string, unknown> = REGISTERED, mk = MK) =>
  ({ blob: b64std(v1.seal(registerAt(revision), mk, enc.encode(JSON.stringify(reg)))), revision })

/** A FetchError as the generated runtime throws it around the browser's bare TypeError: no answer at all. */
const fetchFailed = () =>
  Object.assign(new Error('The request failed and the interceptors did not return an alternative response'), {
    name: 'FetchError',
    cause: new TypeError('Failed to fetch'),
  })

// ── The fake server ─────────────────────────────────────────────────────────────────────────────────────────

type Grant = { wrappedGrant: string; grantedEpoch: number; granterUserId: string; grantSignature: string; granterIdentityPublicKey: string }
type Member = { userId: string; role: 'OWNER' | 'MEMBER'; bundle: string; head: string; grant: Grant }
type Scope = {
  id: string; kind: v1.ScopeKind; tenantId: string; currentEpoch: number
  /** The epoch keys — the test's oracle; the server logic below never reads them. */
  keys: Uint8Array[]
  links: Map<number, string>
  members: Map<string, Member>
  /** Listed by `listMyScopes` (the active tenant); a scope of another tenant is in the plan only. */
  active: boolean
  blocked?: RotationBlockedReason
}
type Signer = { userId: string; bundle: string; sign: Uint8Array }
const meOld: Signer = { userId: ME, bundle: OLD_PUB, sign: OLD_ID.priv.sign }
const bSigner: Signer = { userId: B, bundle: B_PUB, sign: B_KEYS.identity.priv.sign }

/** A grant of `scope`'s epoch `epoch` to `to`, signed by `by`, as a grant row stores it. */
function grantRow(scope: Pick<Scope, 'id' | 'kind' | 'keys'>, epoch: number, to: { userId: string; bundle: string }, by: Signer): Grant {
  const g = sealGrant(scope.keys[epoch], { scopeId: scope.id, kind: scope.kind, epoch }, { userId: to.userId, identityPublicKeyB64: to.bundle }, by.sign)
  return { ...g, grantedEpoch: epoch, granterUserId: by.userId, granterIdentityPublicKey: by.bundle }
}

/** A scope the caller holds at `epoch` (its chain of keys built up to it), its grant from `by` (itself by default). Its
 *  tenant is the active one unless said otherwise — whose TENANT scope has the tenant's id (`scopeId == tenantId`, ADR
 *  0007), which is where a rotation reads who is anchored there (ADR 0111 decision 15). */
function heldScope(
  id: string, kind: v1.ScopeKind,
  o: { epoch?: number; tenantId?: string; active?: boolean; role?: 'OWNER' | 'MEMBER'; by?: Signer; withB?: boolean; blocked?: RotationBlockedReason } = {},
): Scope {
  const epoch = o.epoch ?? 0
  const keys = Array.from({ length: epoch + 1 }, () => v1.randomBytes(32))
  const links = new Map<number, string>()
  for (let e = 1; e <= epoch; e++) links.set(e, b64std(v1.seal({ purpose: 'scope-epoch', scopeId: id, epoch: e }, keys[e], keys[e - 1])))
  const scope: Scope = { id, kind, tenantId: o.tenantId ?? SCOPE_TENANT, currentEpoch: epoch, keys, links, members: new Map(), active: o.active ?? true, blocked: o.blocked }
  const by = o.by ?? meOld
  scope.members.set(ME, { userId: ME, role: o.role ?? 'OWNER', bundle: OLD_PUB, head: '', grant: grantRow(scope, epoch, { userId: ME, bundle: OLD_PUB }, by) })
  if (o.withB) {
    scope.members.set(B, { userId: B, role: 'OWNER', bundle: B_PUB, head: headOf(B_CHAIN), grant: grantRow(scope, epoch, { userId: B, bundle: B_PUB }, bSigner) })
  }
  return scope
}

/** The personal scope, and the tenant scope B owns and granted the caller — the ordinary account. */
const ordinaryScopes = () => [
  heldScope(SCOPE_PERSONAL, 'PERSONAL'),
  heldScope(SCOPE_TENANT, 'TENANT', { role: 'MEMBER', by: bSigner, withB: true }),
]

type ServerOpts = {
  scopes?: Scope[]
  wraps?: MasterKeyWrap[]
  /** The identity private key the account serves, wrapped — the old one under MK by default. */
  wrappedIdentity?: string
  pins?: PinServerState
  register?: DeviceRegister
  /** How many publishes answer `409 stale_revision` first — each as if another device had just written the pin store. */
  staleRotations?: number
  /** What every publish throws instead of answering, before it writes anything. */
  rotateFails?: unknown
  /** What the first publish throws AFTER it committed — its answer is lost. */
  lostAnswer?: unknown
  /** Once the publish committed, every later call fails as a closed tab's would, until `reconnect()`. */
  abandonAfterPublish?: boolean
  /** Runs before each publish is judged — a membership arriving, a scope rotating, a stage expiring. */
  beforePublish?: (n: number) => void
  /** The open member invites the server says this account signed (`getRotationPlanInvites`) — the server's word. */
  invites?: RotationPlanInvite[]
  /** The join records the server says this account rooted (`getRotationPlanJoinRecords`). */
  records?: RotationPlanJoinRecord[]
  /** The live epochs of the instance moderation key, and which of them this account holds a grant of — none: the
   *  account is no moderator (`listMyModerationGrants` answers `403 not_moderator`). */
  moderation?: ModerationEpoch[]
}

/** One live epoch of the moderation key: its `M_n` (the test's oracle) and the grant the account holds of it, sealed to
 *  its identity at the time — or none, an epoch it waits for. `published` overrides the bundle the server publishes. */
type ModerationEpoch = { epoch: number; m: Uint8Array; sealedGrant: string | null; published?: v1.ModerationBundle }
/** `M_n` sealed to `bundle`'s identity, as a holder's delivery seals it. */
const moderationGrantTo = (epoch: number, m: Uint8Array, bundle: string) =>
  b64std(v1.sealTo({ purpose: 'moderation-grant', epoch }, v1.deserializeIdentityPublic(fromB64std(bundle)).kem, m))
/** An epoch this account holds, its grant sealed to the identity it is about to retire. */
const heldEpoch = (epoch: number, m = v1.randomBytes(32)): ModerationEpoch => ({ epoch, m, sealedGrant: moderationGrantTo(epoch, m, OLD_PUB) })

function fakeServer(opts: ServerOpts = {}) {
  const scopes = new Map((opts.scopes ?? []).map((s) => [s.id, s]))
  const meKeys: ChainKeys = { identity: OLD_ID, proof: v1.deriveSecretKeyProofKeypair(SECRET_KEY) }
  const meGenesis = genesis(ME, meKeys)
  const acct = {
    bundle: OLD_PUB,
    wrappedIdentity: opts.wrappedIdentity ?? b64std(v1.seal({ purpose: 'identity-priv' }, MK, v1.serializeIdentityPrivate(OLD_ID))),
    wraps: opts.wraps ?? [storedFloor()],
    proofPub: meKeys.proof.publicKey,
    authPub: authPubOf(PASSWORD),
    chain: { steps: [...meGenesis.steps] as KeyChainStep[], head: meGenesis.head },
  }
  for (const s of scopes.values()) {
    const m = s.members.get(ME)
    if (m) m.head = b64std(acct.chain.head)
  }
  let offline = false
  const online = () => {
    if (offline) throw fetchFailed()
  }
  const material = () => ({ userId: ME, suiteId: 'v1' as const, wraps: acct.wraps, wrappedIdentityPrivateKey: acct.wrappedIdentity })
  let rereadFails = false
  const getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>(async () => {
    online()
    if (rereadFails && publishes.length > 0) throw fetchFailed()
    return material()
  })
  fakeApi(accountApi, { getAccountKeyMaterial })
  fakeApi(loginApi, {
    requestLoginChallenge: vi.fn<LoginApi['requestLoginChallenge']>(async () => {
      online()
      return { nonce: b64std(v1.randomBytes(32)), instanceSalt: b64std(INSTANCE_SALT) }
    }),
  })
  const chainOf = (): KeyChain => ({ userId: ME, head: b64std(acct.chain.head), steps: acct.chain.steps })
  fakeApi(keyChainApi, {
    getKeyChain: vi.fn<KeyChainApi['getKeyChain']>(async ({ userId }) => {
      online()
      if (userId === ME) return chainOf()
      if (userId === B) return served(B_CHAIN)
      throw httpError(404, 'not_found')
    }),
  })

  // The two documents as the server holds them, with the stores' real compare-and-set. Their own PUTs answer 500 — a
  // rotation that still wrote them on their own would leave them under the retired key.
  // B is pinned by default: a first sight would pin it, and this store's own write endpoint is down on purpose.
  const pinServer = fakePinServer(opts.pins ?? sealedPins(1))
  const pins = pinServer.state
  const register: DeviceRegister = { ...(opts.register ?? { revision: 0 }) }
  const putIdentityPins = pinServer.writeIdentityPins.mockImplementation(async () => {
    throw httpError(500)
  })
  const putDeviceRegister = vi.fn<AccountDeviceRegisterApi['putDeviceRegister']>(async () => {
    throw httpError(500)
  })
  fakeApi(accountDeviceRegisterApi, { getDeviceRegister: async () => ({ ...register }), putDeviceRegister })

  const held = () => [...scopes.values()].filter((s) => s.members.has(ME)).sort((a, b) => (a.id < b.id ? -1 : 1))
  const getAccountRotationPlan = vi.fn<AccountRotationApi['getAccountRotationPlan']>(async () => {
    online()
    return held().map((s) => {
      const g = s.members.get(ME)!.grant
      return {
        scopeId: s.id, kind: s.kind, tenantId: s.tenantId, currentEpoch: s.currentEpoch, blocked: s.blocked ?? null,
        wrappedGrant: g.wrappedGrant, grantedEpoch: g.grantedEpoch, granterUserId: g.granterUserId,
        grantSignature: g.grantSignature, granterIdentityPublicKey: g.granterIdentityPublicKey,
      }
    })
  })

  let stage: {
    id: string; bundle: string; rows: Map<string, StagedSelfGrant>
    invites: Map<string, StagedInviteResign>; endorsements: Map<string, StagedJoinEndorsement>
    moderation: Map<number, string | null>
  } | null = null
  const openRotationStage = vi.fn<AccountRotationApi['openRotationStage']>(async ({ openRotationStageRequest }) => {
    online()
    stage = {
      id: crypto.randomUUID(), bundle: openRotationStageRequest.identityPublicKey, rows: new Map(), invites: new Map(), endorsements: new Map(),
      moderation: new Map(),
    }
    return { stageId: stage.id, expiresAt: new Date(Date.now() + 3_600_000) }
  })

  // The instance moderation key (ADR 0099 § 4), as the server serves, stages and applies it: the grants are opaque to it,
  // so a staged epoch is checked only for being one the account holds.
  const moderation = opts.moderation
  const heldEpochs = () => (moderation ?? []).filter((e) => e.sealedGrant !== null)
  const listMyModerationGrants = vi.fn<ModerationApi['listMyModerationGrants']>(async () => {
    online()
    if (!moderation) throw httpError(403, 'not_moderator')
    return {
      currentEpoch: Math.max(...moderation.map((e) => e.epoch)),
      epochs: moderation.map((e) => {
        const published = e.published ?? v1.moderationBundle(e.m)
        return { epoch: e.epoch, kemPublicKey: b64std(published.kem), signPublicKey: b64std(published.sign), sealedGrant: e.sealedGrant ?? undefined }
      }),
    }
  })
  fakeApi(moderationApi, { listMyModerationGrants })
  const putRotationStageModerationGrants = vi.fn<AccountRotationApi['putRotationStageModerationGrants']>(
    async ({ stageId, rotationStageModerationGrants }) => {
      online()
      if (!stage || stage.id !== stageId) throw httpError(404, 'rotation_stage_not_found')
      const chunk = rotationStageModerationGrants.grants
      if (chunk.length < 1 || chunk.length > STAGE_CHUNK) throw httpError(400)
      if (chunk.some((g) => !heldEpochs().some((e) => e.epoch === g.epoch))) throw httpError(409, 'rotation_plan_changed')
      for (const g of chunk) stage.moderation.set(g.epoch!, g.sealedGrant ?? null)
    },
  )

  // What this account rooted (ADR 0111 decisions 20 and 21), as phase 2's server serves, stages
  // and applies it: each re-signature and endorsement verified at upload under the stage's identity, over what it stores.
  const invites = opts.invites ?? []
  const records = opts.records ?? []
  const endedInvites: string[] = []
  const recordKeyOf = (r: { tenantId: string; userId: string }) => `${r.tenantId}:${r.userId}`
  const getRotationPlanInvites = vi.fn<AccountRotationApi['getRotationPlanInvites']>(async () => {
    online()
    return invites.map((i) => ({ ...i }))
  })
  const getRotationPlanJoinRecords = vi.fn<AccountRotationApi['getRotationPlanJoinRecords']>(async () => {
    online()
    return records.map((r) => ({ ...r }))
  })
  const putRotationStageInvites = vi.fn<AccountRotationApi['putRotationStageInvites']>(async ({ stageId, rotationStageInvites }) => {
    online()
    if (!stage || stage.id !== stageId) throw httpError(404, 'rotation_stage_not_found')
    const chunk = rotationStageInvites.invites
    if (chunk.length < 1 || chunk.length > STAGE_CHUNK) throw httpError(400)
    const sign = v1.deserializeIdentityPublic(fromB64std(stage.bundle)).sign
    for (const st of chunk) {
      const stored = invites.find((i) => i.inviteId === st.inviteId)
      if (!stored || stored.anchorEpoch !== st.anchorEpoch) throw httpError(409, 'rotation_plan_changed')
      if ((st.inviteKeySignature == null) !== (st.anchorGrantSignature == null)) throw httpError(400)
      if (st.inviteKeySignature == null) continue
      const signed = { tenantId: stored.tenantId, inviteId: stored.inviteId, invitePublicKey: fromB64std(stored.invitePublicKey), expiresAt: stored.expiresAt.getTime() / 1000 }
      const scope = { scopeId: stored.tenantId, kind: 'TENANT', epoch: stored.anchorEpoch } as const
      const to = { userId: stored.inviteId, identityPublicKeyB64: stored.invitePublicKey }
      if (
        !invite.inviteKeySignatureVerifies(sign, signed, fromB64std(st.inviteKeySignature)) ||
        !grantSignatureVerifies(sign, scope, to, stored.anchorWrappedGrant, st.anchorGrantSignature!)
      ) {
        throw httpError(422, 'invite_resign_invalid')
      }
    }
    for (const st of chunk) stage.invites.set(st.inviteId, st)
  })
  const putRotationStageJoinEndorsements = vi.fn<AccountRotationApi['putRotationStageJoinEndorsements']>(async ({ stageId, rotationStageJoinEndorsements }) => {
    online()
    if (!stage || stage.id !== stageId) throw httpError(404, 'rotation_stage_not_found')
    const chunk = rotationStageJoinEndorsements.endorsements
    if (chunk.length < 1 || chunk.length > STAGE_CHUNK) throw httpError(400)
    const sign = v1.deserializeIdentityPublic(fromB64std(stage.bundle)).sign
    for (const st of chunk) {
      const stored = records.find((r) => recordKeyOf(r) === recordKeyOf(st))
      if (!stored) throw httpError(409, 'rotation_plan_changed')
      if (st.endorsement == null) continue
      const statement = { tenantId: st.tenantId, userId: st.userId, signerUserId: ME, sealedJoinRecord: fromB64std(stored.sealedJoinRecord) }
      if (!invite.joinEndorsementVerifies(sign, statement, fromB64std(st.endorsement))) throw httpError(422, 'join_endorsement_invalid')
    }
    for (const st of chunk) stage.endorsements.set(recordKeyOf(st), st)
  })
  const putRotationStageGrants = vi.fn<AccountRotationApi['putRotationStageGrants']>(async ({ stageId, rotationStageGrants }) => {
    online()
    if (!stage || stage.id !== stageId) throw httpError(404, 'rotation_stage_not_found')
    const { grants } = rotationStageGrants
    if (grants.length < 1 || grants.length > STAGE_CHUNK) throw httpError(400)
    if (grants.some((g) => !scopes.get(g.scopeId)?.members.has(ME))) throw httpError(409, 'rotation_plan_changed')
    // Verified at upload under the stage's identity (Kotlin `GrantSignature`), with the caller as recipient and the
    // scope's own kind — a chunk with one bad grant writes nothing.
    const sign = v1.deserializeIdentityPublic(fromB64std(stage.bundle)).sign
    for (const g of grants) {
      const scope = { scopeId: g.scopeId, kind: scopes.get(g.scopeId)!.kind, epoch: g.epoch! }
      const named = g.recipientIdentityDigest === recipientIdentityDigest(stage.bundle)
      if (!named || !grantSignatureVerifies(sign, scope, { userId: ME, identityPublicKeyB64: stage.bundle }, g.wrappedGrant, g.grantSignature)) {
        throw httpError(422, 'self_grant_invalid')
      }
    }
    for (const g of grants) stage.rows.set(g.scopeId, g)
  })

  const publishes: RotateAccountKeysRequest[] = []
  const regrant = new Map<string, number>()
  let staleLeft = opts.staleRotations ?? 0
  let lost = opts.lostAnswer
  const rotateAccountKeys = vi.fn<AccountRotationApi['rotateAccountKeys']>(async ({ rotateAccountKeysRequest: req }) => {
    online()
    publishes.push(req)
    opts.beforePublish?.(publishes.length)
    if (opts.rotateFails !== undefined) throw opts.rotateFails
    if (staleLeft > 0) {
      staleLeft -= 1
      pinServer.become(sealedPins(pins.revision + 1)) // another device wrote meanwhile
      throw httpError(409, 'stale_revision')
    }
    const planChanged = () => httpError(409, 'rotation_plan_changed')
    // The stage: live, declaring exactly the published identity, its digest recomputed over the rows it holds.
    if (!stage || stage.id !== req.stageId || stage.bundle !== req.identityPublicKey) throw planChanged()
    const digest = v1.rotationStageDigest([...stage.rows.values()].map((g) => ({ scopeId: g.scopeId, epoch: g.epoch!, wrappedGrant: fromB64std(g.wrappedGrant) })))
    if (b64std(digest) !== req.stagedGrantsDigest) throw planChanged()
    // The step, rebuilt from the stored chain and the request's keys: both signatures under the STORED keys.
    const current = acct.chain.steps.at(-1)!
    const retired = { identityPublicKey: fromB64std(current.identityPublicKey), secretKeyProofPublicKey: fromB64std(current.secretKeyProofPublicKey) }
    const installed = { identityPublicKey: fromB64std(req.identityPublicKey), secretKeyProofPublicKey: fromB64std(req.secretKeyProofPublicKey) }
    const step = v1.keyChainStepStatement({ userId: ME, index: acct.chain.steps.length, previousHead: acct.chain.head, retired, installed })
    if (!v1.identityVerify(v1.deserializeIdentityPublic(fromB64std(acct.bundle)).sign, step, fromB64std(req.keyChainStep.identitySignature))) {
      throw httpError(422, 'key_chain_step_invalid')
    }
    if (!v1.authVerify(acct.proofPub, v1.encodeStatement(step), fromB64std(req.keyChainStep.proofSignature))) throw httpError(403, 'secret_key_proof_failed')
    const statement = serverStatement(req, { identity: acct.bundle, authPub: acct.authPub, step })
    if (!v1.authVerify(acct.proofPub, statement, fromB64std(req.secretKeyProofSignature))) throw httpError(403, 'secret_key_proof_failed')
    if (!v1.authVerify(installed.secretKeyProofPublicKey, statement, fromB64std(req.secretKeyProofPossession))) throw httpError(403, 'secret_key_proof_failed')
    if (!v1.authVerify(acct.authPub, fromB64std(req.authPublicKey), fromB64std(req.attestationSignature))) throw httpError(403, 'attestation_failed')
    // The set: every grant-carrying membership staged at its current epoch, or declared.
    const unreadable = new Set(req.unreadableScopeIds)
    for (const s of held()) {
      if (unreadable.has(s.id)) continue
      if (stage.rows.get(s.id)?.epoch !== s.currentEpoch) throw planChanged()
    }
    // What this account rooted: every open invite it signed staged at its anchor epoch, every record it rooted staged.
    for (const i of invites) if (stage.invites.get(i.inviteId)?.anchorEpoch !== i.anchorEpoch) throw planChanged()
    for (const r of records) if (!stage.endorsements.has(recordKeyOf(r))) throw planChanged()
    // The moderation key: every live epoch the account holds staged, re-sealed or declared.
    for (const e of heldEpochs()) if (!stage.moderation.has(e.epoch)) throw planChanged()
    if (req.identityPins.revision !== pins.revision || req.deviceRegister.revision !== register.revision) throw httpError(409, 'stale_revision')
    // Apply, in one transaction.
    pinServer.land(req.identityPins)
    Object.assign(register, { blob: req.deviceRegister.blob, revision: register.revision + 1 })
    acct.chain.steps.push({
      index: acct.chain.steps.length, previousHead: b64std(acct.chain.head), identityPublicKey: req.identityPublicKey,
      secretKeyProofPublicKey: req.secretKeyProofPublicKey, identitySignature: req.keyChainStep.identitySignature,
      proofSignature: req.keyChainStep.proofSignature, createdAt: new Date(),
    })
    acct.chain.head = v1.keyChainHead(step)
    acct.bundle = req.identityPublicKey
    acct.wrappedIdentity = req.wrappedIdentityPrivateKey
    acct.wraps = [{ id: 'w2', method: 'PASSWORD', ...req.passwordWrap }]
    acct.proofPub = installed.secretKeyProofPublicKey
    acct.authPub = fromB64std(req.authPublicKey)
    regrant.clear()
    const regrantedScopeIds: string[] = []
    for (const s of held()) {
      const me = s.members.get(ME)!
      Object.assign(me, { bundle: acct.bundle, head: b64std(acct.chain.head) })
      if (unreadable.has(s.id)) continue
      const g = stage.rows.get(s.id)!
      me.grant = { wrappedGrant: g.wrappedGrant, grantedEpoch: g.epoch!, granterUserId: ME, grantSignature: g.grantSignature, granterIdentityPublicKey: acct.bundle }
      regrant.set(s.id, s.currentEpoch)
      regrantedScopeIds.push(s.id)
    }
    for (const st of stage.invites.values()) {
      const at = invites.findIndex((i) => i.inviteId === st.inviteId)
      if (at < 0) continue
      if (st.inviteKeySignature == null) {
        endedInvites.push(st.inviteId)
        invites.splice(at, 1)
      } else {
        Object.assign(invites[at], { inviteKeySignature: st.inviteKeySignature, anchorGrantSignature: st.anchorGrantSignature })
      }
    }
    for (const r of records) r.joinEndorsement = stage.endorsements.get(recordKeyOf(r))?.endorsement ?? null
    for (const e of heldEpochs()) e.sealedGrant = stage.moderation.get(e.epoch) ?? null
    applied.push({ stageId: stage.id, rows: new Map(stage.rows), recoveryWrap: req.recoveryWrap })
    stage = null
    if (opts.abandonAfterPublish) offline = true
    if (lost !== undefined) {
      const failure = lost
      lost = undefined
      throw failure
    }
    return { regrantedScopeIds }
  })
  const applied: { stageId: string; rows: Map<string, StagedSelfGrant>; recoveryWrap: RotateAccountKeysRequest['recoveryWrap'] }[] = []
  const listRotationScopeStates = vi.fn<AccountRotationApi['listRotationScopeStates']>(async () => {
    online()
    return [...regrant].map(([scopeId, at]) => {
      const s = scopes.get(scopeId)!
      if (s.blocked) return { scopeId, state: 'blocked' as const, reason: s.blocked }
      return { scopeId, state: s.currentEpoch === at ? ('waiting' as const) : ('secured' as const) }
    })
  })
  fakeApi(accountRotationApi, {
    getAccountRotationPlan, openRotationStage, putRotationStageGrants, rotateAccountKeys, listRotationScopeStates,
    getRotationPlanInvites, getRotationPlanJoinRecords, putRotationStageInvites, putRotationStageJoinEndorsements,
    putRotationStageModerationGrants,
  })

  const summary = (s: Scope): ScopeSummary => {
    const g = s.members.get(ME)!.grant
    return {
      id: s.id, kind: s.kind, role: s.members.get(ME)!.role, tenantId: s.tenantId, nameEpoch: 0, iconEpoch: 0,
      currentEpoch: s.currentEpoch, grantedEpoch: g.grantedEpoch, wrappedGrant: g.wrappedGrant, granterUserId: g.granterUserId,
      grantSignature: g.grantSignature, granterIdentityPublicKey: g.granterIdentityPublicKey,
    }
  }
  const rotateScope = vi.fn<ScopesApi['rotateScope']>(async ({ scopeId, rotateScopeRequest: r }) => {
    online()
    const s = scopes.get(scopeId)
    if (!s?.members.has(ME)) throw httpError(404, 'not_found')
    // The gate (decision 4): an owner, or a member whose regrant row still names the current epoch, with no removal.
    const owner = s.members.get(ME)!.role === 'OWNER'
    const row = regrant.get(scopeId)
    if (!owner) {
      if (row === undefined || r.removesUserId) throw httpError(403, 'not_scope_owner')
      if (row !== s.currentEpoch) throw httpError(409, 'stale_epoch')
    }
    if (r.epoch !== s.currentEpoch + 1) throw httpError(409, 'stale_epoch')
    const named = new Set([...r.grants.map((g) => g.userId), ...(r.pendingUserIds ?? [])])
    if ([...s.members.keys()].some((u) => u !== r.removesUserId && !named.has(u))) throw httpError(409, 'rotation_incomplete')
    for (const g of r.grants) {
      if (g.recipientIdentityDigest !== recipientIdentityDigest(s.members.get(g.userId)!.bundle)) throw httpError(409, 'recipient_key_not_current')
    }
    s.currentEpoch = r.epoch
    s.links.set(r.epoch, r.wrappedPrev)
    for (const g of r.grants) {
      s.members.get(g.userId)!.grant = {
        wrappedGrant: g.wrappedGrant, grantSignature: g.grantSignature, grantedEpoch: r.epoch, granterUserId: ME, granterIdentityPublicKey: acct.bundle,
      }
    }
    return { epoch: r.epoch }
  })
  const membersFail = new Set<string>()
  const membersDown = new Set<string>()
  // The directory as the server serves it — to the callers' read (`members.listScopeMembers`, mocked at that module) and
  // to a rotation's rebuild, which reads it again itself (inbox #661).
  async function readMembers(scopeId: string): Promise<ScopeMember[]> {
    online()
    if (membersFail.delete(scopeId) || membersDown.has(scopeId)) throw fetchFailed()
    const s = scopes.get(scopeId)!
    return [...s.members.values()].map((m): ScopeMember => ({
      userId: m.userId, username: m.userId, avatarRevision: 0, role: m.role, identityPublicKey: m.bundle, keyChainHead: m.head,
      pending: m.grant.grantedEpoch < s.currentEpoch,
    }))
  }
  fakeApi(scopesApi, {
    rotateScope,
    listScopeMembers: vi.fn<ScopesApi['listScopeMembers']>(async ({ scopeId }) => readMembers(scopeId)),
    listMyScopes: vi.fn<ScopesApi['listMyScopes']>(async () => {
      online()
      return held().filter((s) => s.active).map(summary)
    }),
    listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>(async ({ scopeId }) => {
      online()
      return [...(scopes.get(scopeId)?.links ?? [])].map(([epoch, wrappedPrev]) => ({ epoch, wrappedPrev }))
    }),
    listOpenInvites: vi.fn<ScopesApi['listOpenInvites']>(async () => {
      online()
      return []
    }),
  })
  fakeApi(tasksApi, { claimTask: vi.fn<TasksApi['claimTask']>(async () => null) })
  vi.mocked(listScopeMembers).mockImplementation(readMembers)

  return {
    scopes, acct, pins, register, publishes, applied, regrant,
    getAccountKeyMaterial, getAccountRotationPlan, openRotationStage, putRotationStageGrants, rotateAccountKeys, rotateScope,
    listRotationScopeStates, putIdentityPins, putDeviceRegister,
    invites, records, endedInvites, putRotationStageInvites, putRotationStageJoinEndorsements,
    moderation, listMyModerationGrants, putRotationStageModerationGrants,
    get stage() {
      return stage
    },
    expireStage() {
      stage = null
    },
    /** The next re-read of the key material after a publish fails too — an unknown outcome. */
    failReread() {
      rereadFails = true
    },
    /** `listScopeMembers` of `scopeId` fails once, as a re-key that does not reach the server. */
    failMembersOnce(scopeId: string) {
      membersFail.add(scopeId)
    },
    /** `listScopeMembers` of `scopeId` fails until {@link healMembers} — every read of it, the tenant's directory read
     *  by a rotation of any scope in it (ADR 0111 decision 15) included. */
    failMembers(scopeId: string) {
      membersDown.add(scopeId)
    },
    healMembers() {
      membersDown.clear()
    },
    reconnect() {
      offline = false
    },
    /** The stored `PASSWORD` floor and the identity it serves, opened with `password` and `secret` — a device that unlocks
     *  from the Emergency Kit, owning nothing of this one's memory. */
    unlockFresh(password: string, secret: Uint8Array) {
      const floor = acct.wraps[0]
      const { salt } = v1.decodeKdfParams(fromB64std(floor.kdfParams!))
      const mk = v1.open({ purpose: 'master-key' }, v1.kekPasswordSecret(secret, password, salt, KDF), fromB64std(floor.wrappedKey))
      const priv = v1.deserializeIdentityPrivate(v1.open({ purpose: 'identity-priv' }, mk, fromB64std(acct.wrappedIdentity)))
      vault.mk = mk
      vault.priv = priv
      vault.origin = 'cold'
    },
  }
}

/** The rotation statement as the server rebuilds it: from its stored identity and auth key, and the request. */
function serverStatement(
  req: RotateAccountKeysRequest, stored: { identity: string; authPub: Uint8Array; step: v1.KeyChainStepStatement },
): Uint8Array {
  const wrap = (w: { wrappedKey: string; kdfParams: string }) => ({ wrappedKey: fromB64std(w.wrappedKey), kdfParams: fromB64std(w.kdfParams) })
  return v1.rotationProofStatement({
    currentIdentityPublicKey: fromB64std(stored.identity),
    identityPublicKey: fromB64std(req.identityPublicKey),
    wrappedIdentityPrivateKey: fromB64std(req.wrappedIdentityPrivateKey),
    passwordWrap: wrap(req.passwordWrap),
    recoveryWrap: wrap(req.recoveryWrap),
    keyChainStep: stored.step,
    currentAuthPublicKey: stored.authPub,
    authPublicKey: fromB64std(req.authPublicKey),
    secretKeyProofPublicKey: fromB64std(req.secretKeyProofPublicKey),
    stageId: req.stageId,
    stagedGrantsDigest: fromB64std(req.stagedGrantsDigest),
  })
}

/** The published identity, as the vault now holds it. */
const vaultPub = () => v1.identityPublicFromPrivate(vault.priv!)
const vaultPubB64 = () => b64std(v1.serializeIdentityPublic(vaultPub()))
/** The caller's grant on `s`, opened with `priv` at the epoch it carries. */
const openMine = (s: Scope, priv = vault.priv!) => {
  const g = s.members.get(ME)!.grant
  return v1.openAs(grantContext(s.id, g.grantedEpoch), v1.hybridSecret(priv), fromB64std(g.wrappedGrant))
}
const same = (a: Uint8Array, b: Uint8Array) => b64std(a) === b64std(b)
const rotate = (o: { password?: string; secretKeyB64?: string } = {}) => rotateKeys(o.password ?? PASSWORD, o.secretKeyB64 ?? SECRET_KEY_B64, NEXT)

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // this device's trust-state marks (ADR 0028 Decision 7), fresh per test
  vault.mk = MK.slice()
  vault.priv = oldPriv()
  vault.origin = 'cold'
  setAccount(EMAIL)
  forgetVerifiedPins()
  NEXT = { secretKeyB64: mintSecretKey() }
  vi.mocked(resealFavoritesUnderNewMasterKey).mockResolvedValue(undefined)
  vi.mocked(retagItemMarksUnderNewMasterKey).mockResolvedValue(undefined)
})

describe('the lifecycle gate: a rotation needs an application that carries the master-key hook', () => {
  // The registry is write-once and filled by the real composition root (src/test/setup.ts), so the "nothing
  // registered" build is simulated by making ITS gate answer as an empty registry does (proven separately in
  // registry.test.ts). This test pins the other half: rotateKeys asks BEFORE it proves, mints or posts anything.
  it('refuses to start when no application registered the hook, and touches nothing', async () => {
    const srv = fakeServer()
    const gate = vi.spyOn(registry, 'assertRotationHooks').mockImplementation(() => {
      throw new Error('no application registered an onMasterKeyRotated hook; refusing to rotate keys (fail closed)')
    })
    try {
      await expect(rotate()).rejects.toThrow(/refusing to rotate/)
      expect(srv.getAccountKeyMaterial).not.toHaveBeenCalled()
      expect(srv.openRotationStage).not.toHaveBeenCalled()
      expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
      expect(setUnlocked).not.toHaveBeenCalled()
      expect(resealFavoritesUnderNewMasterKey).not.toHaveBeenCalled()
    } finally {
      gate.mockRestore()
    }
  })

  it('live control: with the real registry and the real Memories hooks the same call rotates and re-seals', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await expect(rotate()).resolves.toMatchObject({ outcome: 'published' })
    expect(srv.rotateAccountKeys).toHaveBeenCalled()
    expect(resealFavoritesUnderNewMasterKey).toHaveBeenCalledTimes(1)
    expect(retagItemMarksUnderNewMasterKey).toHaveBeenCalledTimes(1)
  })
})

describe('the cold-credential gate (ADR 0060 Decision 2)', () => {
  it.each(['device', 'none'] as const)(
    'refuses a %s-opened session that cannot produce the Secret Key, and posts nothing',
    async (origin) => {
      // The attacker's exact position: they hold a DEVICE_KEY wrap (or a Master Key handed over by a phished grant,
      // which opened no wrap at all) and no Emergency Kit. If they could secure the account they would lock the
      // legitimate holder out permanently.
      vault.origin = origin
      const srv = fakeServer()
      await expect(rotate({ secretKeyB64: b64std(v1.randomBytes(32)) })).rejects.toMatchObject({ reason: 'credentialRejected' })
      expect(srv.openRotationStage).not.toHaveBeenCalled()
      expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
    },
  )

  it.each(['device', 'none'] as const)(
    'lets a %s-opened session rotate once it proves the cold credential in place, and marks it cold',
    async (origin) => {
      // E1 as written: supplying password + Secret Key IS the cold proof, however the vault was opened.
      vault.origin = origin
      const srv = fakeServer()
      await expect(rotate()).resolves.toBeDefined()
      expect(srv.rotateAccountKeys).toHaveBeenCalled()
      expect(vi.mocked(setUnlocked).mock.calls[0][2]).toBe('cold')
    },
  )

  it('refuses a wrong Secret Key even on a cold unlock, and posts nothing', async () => {
    const srv = fakeServer()
    await expect(rotate({ secretKeyB64: b64std(v1.randomBytes(32)) })).rejects.toMatchObject({ reason: 'credentialRejected' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses a wrong password even with the right Secret Key, and posts nothing', async () => {
    const srv = fakeServer()
    await expect(rotate({ password: 'not the password' })).rejects.toMatchObject({ reason: 'credentialRejected' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses a floor that opens a DIFFERENT Master Key which does not open the served identity — credentialRejected, never vaultStale', async () => {
    // The control `vaultStale` needs (decision 12): the credential is right for the wrap served, but the key it opens is
    // not the one the served identity is wrapped under — so this is no account secured meanwhile, and nothing is resumed.
    const srv = fakeServer({ wraps: [storedFloor(v1.randomBytes(32))] })
    await expect(rotate()).rejects.toMatchObject({ reason: 'credentialRejected' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
    expect(setUnlocked).not.toHaveBeenCalled()
  })

  it('refuses when the account has no PASSWORD floor to prove against', async () => {
    const srv = fakeServer({ wraps: [] })
    await expect(rotate()).rejects.toBeInstanceOf(RotationRefused)
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })

  it('refuses a locked vault — there is no current Master Key to prove the credential against', async () => {
    vault.mk = null
    vault.priv = null
    const srv = fakeServer()
    await expect(rotate()).rejects.toMatchObject({ reason: 'vaultLocked' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
  })
})

describe('what securing the account posts (decision 7, ADR 0060 Decision 2)', () => {
  const sent = (srv: ReturnType<typeof fakeServer>, n = 0) => srv.publishes[n]

  it('the PASSWORD wrap opens with the password and the NEW Secret Key — not the old one', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    const body = sent(srv)
    const pw = v1.decodeKdfParams(fromB64std(body.passwordWrap.kdfParams))
    const newMk = v1.open({ purpose: 'master-key' }, v1.kekPasswordSecret(nextSecretKey(), PASSWORD, pw.salt, KDF), fromB64std(body.passwordWrap.wrappedKey))
    expect(same(newMk, MK)).toBe(false)
    expect(same(newMk, vault.mk!)).toBe(true)
    // The old Emergency Kit opens nothing the publish wrote.
    expect(() => v1.open({ purpose: 'master-key' }, v1.kekPasswordSecret(SECRET_KEY, PASSWORD, pw.salt, KDF), fromB64std(body.passwordWrap.wrappedKey))).toThrow()
    // The identity private key rides the new Master Key.
    expect(() => v1.open({ purpose: 'identity-priv' }, newMk, fromB64std(body.wrappedIdentityPrivateKey))).not.toThrow()
  })

  it('the RECOVERY wrap opens under kekRecovery of the NEW Secret Key, to the same new Master Key, with its own fresh salt', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    const body = sent(srv)
    const rec = v1.decodeKdfParams(fromB64std(body.recoveryWrap.kdfParams))
    const fromRecovery = v1.open({ purpose: 'master-key' }, v1.kekRecovery(nextSecretKey(), rec.salt, KDF), fromB64std(body.recoveryWrap.wrappedKey))
    expect(same(fromRecovery, vault.mk!)).toBe(true)
    expect(() => v1.open({ purpose: 'master-key' }, v1.kekRecovery(SECRET_KEY, rec.salt, KDF), fromB64std(body.recoveryWrap.wrappedKey))).toThrow()
    expect(body.passwordWrap.kdfParams).not.toBe(body.recoveryWrap.kdfParams)
  })

  it('registers the NEW Secret Key’s proof key; both proof signatures verify over the statement the server rebuilds', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    const oldProofPub = srv.acct.proofPub
    const head0 = srv.acct.chain.head
    await rotate()
    const body = sent(srv)
    const newProof = v1.deriveSecretKeyProofKeypair(nextSecretKey())
    expect(body.secretKeyProofPublicKey).toBe(b64std(newProof.publicKey))
    const step = v1.keyChainStepStatement({
      userId: ME, index: 1, previousHead: head0,
      retired: { identityPublicKey: v1.serializeIdentityPublic(OLD_ID.pub), secretKeyProofPublicKey: oldProofPub },
      installed: { identityPublicKey: fromB64std(body.identityPublicKey), secretKeyProofPublicKey: newProof.publicKey },
    })
    const rebuilt = serverStatement(body, { identity: OLD_PUB, authPub: authPubOf(PASSWORD), step })
    expect(v1.authVerify(oldProofPub, rebuilt, fromB64std(body.secretKeyProofSignature))).toBe(true)
    expect(v1.authVerify(newProof.publicKey, rebuilt, fromB64std(body.secretKeyProofPossession))).toBe(true)
    // …and under no other key: the possession is not the current key's, the gate is not the new key's.
    expect(v1.authVerify(newProof.publicKey, rebuilt, fromB64std(body.secretKeyProofSignature))).toBe(false)
    expect(v1.authVerify(oldProofPub, rebuilt, fromB64std(body.secretKeyProofPossession))).toBe(false)
  })

  it('the chain step is signed by the retired identity AND the retired proof key, naming the stored head', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    const head0 = srv.acct.chain.head
    await rotate()
    // The served chain now walks from the genesis this account's contacts pinned to the published identity.
    const chain = srv.acct.chain.steps
    const walked = v1.verifyKeyChain({
      userId: ME,
      links: chain.map((s) => ({
        index: s.index, previousHead: s.previousHead ? fromB64std(s.previousHead) : null,
        identityPublicKey: fromB64std(s.identityPublicKey), secretKeyProofPublicKey: fromB64std(s.secretKeyProofPublicKey),
        identitySignature: s.identitySignature ? fromB64std(s.identitySignature) : null, proofSignature: s.proofSignature ? fromB64std(s.proofSignature) : null,
      })),
    }, head0)
    expect(walked).toMatchObject({ ok: true, index: 1, fromIndex: 0 })
    expect(walked.ok && b64std(walked.identityPublicKey)).toBe(vaultPubB64())
  })

  it('keeping the password keeps the auth key, and the attestation is the current auth key’s signature over it', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    const body = sent(srv)
    expect(body.authPublicKey).toBe(b64std(authPubOf(PASSWORD)))
    expect(v1.authVerify(authPubOf(PASSWORD), fromB64std(body.authPublicKey), fromB64std(body.attestationSignature))).toBe(true)
  })

  it('a new password installs its own auth key, attested by the current one, and the floor opens with it', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    NEXT = { ...NEXT, password: 'a brand new passphrase' }
    await rotate()
    const body = sent(srv)
    expect(body.authPublicKey).toBe(b64std(authPubOf('a brand new passphrase')))
    expect(v1.authVerify(authPubOf(PASSWORD), fromB64std(body.authPublicKey), fromB64std(body.attestationSignature))).toBe(true)
    const pw = v1.decodeKdfParams(fromB64std(body.passwordWrap.kdfParams))
    expect(() => v1.open({ purpose: 'master-key' }, v1.kekPasswordSecret(nextSecretKey(), 'a brand new passphrase', pw.salt, KDF), fromB64std(body.passwordWrap.wrappedKey))).not.toThrow()
    expect(() => v1.open({ purpose: 'master-key' }, v1.kekPasswordSecret(nextSecretKey(), PASSWORD, pw.salt, KDF), fromB64std(body.passwordWrap.wrappedKey))).toThrow()
  })

  it('sends exactly the contract’s fields — one rotation, no mode', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    expect(Object.keys(sent(srv)).sort()).toEqual([
      'attestationSignature', 'authPublicKey', 'deviceRegister', 'identityPins', 'identityPublicKey', 'keyChainStep',
      'passwordWrap', 'recoveryWrap', 'secretKeyProofPossession', 'secretKeyProofPublicKey', 'secretKeyProofSignature',
      'stageId', 'stagedGrantsDigest', 'unreadableScopeIds', 'wrappedIdentityPrivateKey',
    ])
  })

  it('never sends the password or either Secret Key', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    const wire = JSON.stringify([srv.publishes, srv.putRotationStageGrants.mock.calls, srv.openRotationStage.mock.calls])
    expect(wire).not.toContain(PASSWORD)
    expect(wire).not.toContain(SECRET_KEY_B64)
    expect(wire).not.toContain(NEXT.secretKeyB64)
  })

  it('every self-grant is signed by the NEW identity and opens under it — the old one signs none (decision 3)', async () => {
    // Bite: sign a self-grant with the old identity — the stage refuses it at upload (`422 self_grant_invalid`).
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    const rows = srv.applied[0].rows
    expect([...rows.keys()].sort()).toEqual([SCOPE_PERSONAL, SCOPE_TENANT])
    for (const [scopeId, g] of rows) {
      const kind = srv.scopes.get(scopeId)!.kind
      const to = { userId: ME, identityPublicKeyB64: vaultPubB64() }
      expect(g.recipientIdentityDigest).toBe(recipientIdentityDigest(vaultPubB64()))
      expect(grantSignatureVerifies(vaultPub().sign, { scopeId, kind, epoch: 0 }, to, g.wrappedGrant, g.grantSignature)).toBe(true)
      expect(grantSignatureVerifies(OLD_ID.pub.sign, { scopeId, kind, epoch: 0 }, to, g.wrappedGrant, g.grantSignature)).toBe(false)
      expect(same(v1.openAs(grantContext(scopeId, 0), v1.hybridSecret(vault.priv!), fromB64std(g.wrappedGrant)), srv.scopes.get(scopeId)!.keys[0])).toBe(true)
    }
  })
})

describe('securing the account loses no space (inbox #556)', () => {
  it('way 1, lost answer: the publish committed and its answer was lost — the vault takes the minted identity and every space gets its new epoch', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), lostAnswer: fetchFailed() })
    const result = await rotate()
    expect(result.outcome).toBe('published')
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
    // The vault holds the identity the account now serves, never the old one.
    expect(vaultPubB64()).toBe(srv.acct.bundle)
    expect(vaultPubB64()).not.toBe(OLD_PUB)
    for (const id of [SCOPE_PERSONAL, SCOPE_TENANT]) {
      const s = srv.scopes.get(id)!
      expect(s.currentEpoch, id).toBe(1)
      expect(same(openMine(s), s.keys[0]), id).toBe(false) // E_1: a fresh key…
      expect(v1.open({ purpose: 'scope-epoch', scopeId: id, epoch: 1 }, openMine(s), fromB64std(s.links.get(1)!)), id).toEqual(s.keys[0]) // …linked to E_0
    }
    expect(result.states.map((s) => s.state).sort()).toEqual(['secured', 'secured'])
  })

  it('way 1, retry: the vault still holds the old Master Key, the server committed — the new kit finishes, nothing is minted again', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), abandonAfterPublish: true })
    await rotate()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
    const published = srv.acct.bundle
    // Another tab of this device still holds the vault the publish replaced.
    srv.reconnect()
    vault.mk = MK.slice()
    vault.priv = oldPriv()
    const retry = await rotateKeys(PASSWORD, NEXT.secretKeyB64, { secretKeyB64: mintSecretKey() })
    expect(retry.outcome).toBe('resumed')
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
    expect(srv.openRotationStage).toHaveBeenCalledTimes(1)
    expect(vaultPubB64()).toBe(published)
    expect(srv.scopes.get(SCOPE_PERSONAL)!.currentEpoch).toBe(1)
    expect(srv.scopes.get(SCOPE_TENANT)!.currentEpoch).toBe(1)
  })

  it('way 2, closed tab: the publish alone leaves every space readable by the new identity; a fresh device finishes it and grants the other member', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), abandonAfterPublish: true })
    await rotate()
    srv.reconnect()
    // What the publish applied: the stage it named, a self-grant of E_0 per scope, each opening under the new identity.
    expect(srv.publishes[0].stageId).toBe(srv.applied[0].stageId)
    const fresh = v1.generateIdentity()
    vault.mk = null
    vault.priv = null
    globalThis.indexedDB = new IDBFactory() // another device: none of this one's marks
    srv.unlockFresh(PASSWORD, nextSecretKey())
    expect(vaultPubB64()).toBe(srv.acct.bundle)
    for (const id of [SCOPE_PERSONAL, SCOPE_TENANT]) {
      const s = srv.scopes.get(id)!
      expect(s.currentEpoch, id).toBe(0)
      expect(s.members.get(ME)!.grant.grantedEpoch).toBe(0)
      expect(same(openMine(s), s.keys[0]), id).toBe(true)
      expect(() => openMine(s, { kemSeed: fresh.priv.kemSeed, sign: fresh.priv.sign })).toThrow()
    }
    expect(srv.listRotationScopeStates).toHaveBeenCalled() // the abandoned tab asked, and got no answer
    expect((await srv.listRotationScopeStates()).map((s) => s.state)).toEqual(['waiting', 'waiting'])

    await runScopeRotationWorker()

    for (const id of [SCOPE_PERSONAL, SCOPE_TENANT]) {
      const s = srv.scopes.get(id)!
      expect(s.currentEpoch, id).toBe(1)
      expect(v1.open({ purpose: 'scope-epoch', scopeId: id, epoch: 1 }, openMine(s), fromB64std(s.links.get(1)!)), id).toEqual(s.keys[0])
    }
    const tenant = srv.scopes.get(SCOPE_TENANT)!
    const bGrant = tenant.members.get(B)!.grant
    expect(bGrant.grantedEpoch).toBe(1)
    const e1ForB = v1.openAs(grantContext(SCOPE_TENANT, 1), v1.hybridSecret(B_KEYS.identity.priv), fromB64std(bGrant.wrappedGrant))
    expect(same(e1ForB, openMine(tenant))).toBe(true)
    expect((await srv.listRotationScopeStates()).map((s) => s.state)).toEqual(['secured', 'secured'])
  })

  it('way 3, a new epoch fails: the space waits, the vault is on the new identity and its grant opens; the next pass secures it', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    srv.failMembers(SCOPE_TENANT)
    const result = await rotate()
    expect(result.states.find((s) => s.scopeId === SCOPE_TENANT)?.state).toBe('waiting')
    expect(vaultPubB64()).toBe(srv.acct.bundle)
    const tenant = srv.scopes.get(SCOPE_TENANT)!
    expect(tenant.currentEpoch).toBe(0)
    expect(same(openMine(tenant), tenant.keys[0])).toBe(true)

    srv.healMembers()
    await runScopeRotationWorker()
    expect(tenant.currentEpoch).toBe(1)
    expect((await srv.listRotationScopeStates()).find((s) => s.scopeId === SCOPE_TENANT)?.state).toBe('secured')
  })

  it('way 4, another tenant: a space the active tenant does not list is self-granted from the plan and gets its new epoch', async () => {
    const elsewhere = heldScope(SCOPE_ELSEWHERE, 'GROUP', { tenantId: 't2', active: false })
    const srv = fakeServer({ scopes: [...ordinaryScopes(), elsewhere] })
    const result = await rotate()
    expect(srv.applied[0].rows.has(SCOPE_ELSEWHERE)).toBe(true)
    expect(elsewhere.currentEpoch).toBe(1)
    expect(same(v1.open({ purpose: 'scope-epoch', scopeId: SCOPE_ELSEWHERE, epoch: 1 }, openMine(elsewhere), fromB64std(elsewhere.links.get(1)!)), elsewhere.keys[0])).toBe(true)
    expect(result.states.find((s) => s.scopeId === SCOPE_ELSEWHERE)?.state).toBe('secured')
  })

  it('the finish grants a member who joined after the publish (decision 4: whoever is a member then)', async () => {
    // Bite: restore the `members.length !== 1` refusal — the space stays waiting, and the newcomer is never granted.
    const group = heldScope(SCOPE_JOINED, 'GROUP')
    // B is anchored in the group's tenant (its owner, a root): the finish judges the newcomer there (ADR 0111 decision 15)
    const srv = fakeServer({ scopes: [group, heldScope(SCOPE_TENANT, 'TENANT', { role: 'MEMBER', by: bSigner, withB: true })], abandonAfterPublish: true })
    await rotate()
    srv.reconnect()
    group.members.set(B, { userId: B, role: 'MEMBER', bundle: B_PUB, head: headOf(B_CHAIN), grant: grantRow(group, 0, { userId: B, bundle: B_PUB }, bSigner) })
    await runScopeRotationWorker()
    expect(group.currentEpoch).toBe(1)
    const bGrant = group.members.get(B)!.grant
    expect(bGrant.grantedEpoch).toBe(1)
    expect(same(v1.openAs(grantContext(SCOPE_JOINED, 1), v1.hybridSecret(B_KEYS.identity.priv), fromB64std(bGrant.wrappedGrant)), openMine(group))).toBe(true)
  })

  /** B joins `group` (anchored in the tenant, whose owner B is) right AFTER the caller's own directory read of it — so
   *  the list the caller hands the rotation lacks B, and the server's completeness check refuses the first commit. */
  function joinAfterFirstRead(group: Scope) {
    const base = vi.mocked(listScopeMembers).getMockImplementation()!
    let joined = false
    vi.mocked(listScopeMembers).mockImplementation(async (scopeId: string) => {
      const listed = await base(scopeId)
      if (scopeId === group.id && !joined) {
        joined = true
        group.members.set(B, { userId: B, role: 'MEMBER', bundle: B_PUB, head: headOf(B_CHAIN), grant: grantRow(group, 0, { userId: B, bundle: B_PUB }, bSigner) })
      }
      return listed
    })
  }

  it('the finish grants a member who joined between its directory read and its commit: the rebuild reads the members again (inbox #661)', async () => {
    // Bite: build the rebuild from the list the caller handed in (`listed = members` in commitRotation) — the second
    // commit meets the same `409 rotation_incomplete`, the space stays waiting and B is never granted.
    const group = heldScope(SCOPE_JOINED, 'GROUP')
    const srv = fakeServer({ scopes: [group, heldScope(SCOPE_TENANT, 'TENANT', { role: 'MEMBER', by: bSigner, withB: true })], abandonAfterPublish: true })
    await rotate()
    srv.reconnect()
    joinAfterFirstRead(group)
    srv.rotateScope.mockClear()
    await runScopeRotationWorker()
    const groupCommits = srv.rotateScope.mock.calls.flatMap((c, i) => (c[0].scopeId === SCOPE_JOINED ? [srv.rotateScope.mock.results[i].value] : []))
    expect(groupCommits).toHaveLength(2) // the first met the join, the one rebuild took it
    await expect(groupCommits[0]).rejects.toMatchObject({ message: 'http 409 rotation_incomplete' })
    expect(group.currentEpoch).toBe(1)
    const bGrant = group.members.get(B)!.grant
    expect(bGrant.grantedEpoch).toBe(1)
    expect(same(v1.openAs(grantContext(SCOPE_JOINED, 1), v1.hybridSecret(B_KEYS.identity.priv), fromB64std(bGrant.wrappedGrant)), openMine(group))).toBe(true)
  })

  it('a queued rotation grants a member who joined between the worker\'s directory read and its commit (inbox #661)', async () => {
    // Bite: as above — without the re-read the worker's one rebuild fails as well, and the job is failed back unrotated.
    const group = heldScope(SCOPE_JOINED, 'GROUP')
    const srv = fakeServer({ scopes: [group, heldScope(SCOPE_TENANT, 'TENANT', { role: 'MEMBER', by: bSigner, withB: true })] })
    await rotate() // the account's own rotation settles first, so the pass below only takes the queued job
    expect(group.currentEpoch).toBe(1)
    const completeTask = vi.fn<TasksApi['completeTask']>(async () => undefined)
    const failTask = vi.fn<TasksApi['failTask']>()
    fakeApi(tasksApi, {
      claimTask: vi.fn<TasksApi['claimTask']>()
        .mockResolvedValueOnce({ jobId: 'j1', scopeId: SCOPE_JOINED, capabilityTag: SCOPE_ROTATION, attempts: 1 })
        .mockResolvedValue(null),
      completeTask, failTask,
      declineTask: vi.fn<TasksApi['declineTask']>(),
      heartbeatTask: vi.fn<TasksApi['heartbeatTask']>(),
    })
    joinAfterFirstRead(group)
    srv.rotateScope.mockClear()

    expect(await runScopeRotationWorker()).toBe(1)

    expect(srv.rotateScope).toHaveBeenCalledTimes(2)
    expect(failTask).not.toHaveBeenCalled()
    expect(completeTask).toHaveBeenCalledTimes(1)
    expect(group.currentEpoch).toBe(2)
    const bGrant = group.members.get(B)!.grant
    expect(bGrant.grantedEpoch).toBe(2)
    expect(same(v1.openAs(grantContext(SCOPE_JOINED, 2), v1.hybridSecret(B_KEYS.identity.priv), fromB64std(bGrant.wrappedGrant)), openMine(group))).toBe(true)
  })

  it('someone else’s commit finishes the row: a 409 stale_epoch, and the rebuild commits nothing on top', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), abandonAfterPublish: true })
    await rotate()
    srv.reconnect()
    // B, the tenant's owner, commits E_1 for everyone — the new identity included, as the server requires — just as this
    // device reads the members: its own commit meets `409 stale_epoch`.
    const tenant = srv.scopes.get(SCOPE_TENANT)!
    const base = vi.mocked(listScopeMembers).getMockImplementation()!
    vi.mocked(listScopeMembers).mockImplementation(async (scopeId: string) => {
      if (scopeId === SCOPE_TENANT && tenant.currentEpoch === 0) {
        tenant.keys.push(v1.randomBytes(32))
        tenant.currentEpoch = 1
        tenant.links.set(1, b64std(v1.seal({ purpose: 'scope-epoch', scopeId: SCOPE_TENANT, epoch: 1 }, tenant.keys[1], tenant.keys[0])))
        tenant.members.get(ME)!.grant = grantRow(tenant, 1, { userId: ME, bundle: srv.acct.bundle }, bSigner)
      }
      return base(scopeId)
    })
    srv.rotateScope.mockClear()
    await runScopeRotationWorker()
    expect(tenant.currentEpoch).toBe(1)
    const tenantCommits = srv.rotateScope.mock.calls.flatMap((c, i) => (c[0].scopeId === SCOPE_TENANT ? [srv.rotateScope.mock.results[i].value] : []))
    expect(tenantCommits).toHaveLength(1)
    await expect(tenantCommits[0]).rejects.toMatchObject({ message: 'http 409 stale_epoch' })
    expect((await srv.listRotationScopeStates()).map((x) => x.state)).toEqual(['secured', 'secured'])
  })

  it('an unreadable grant is declared, never dropped: it keeps its grant and gets no new lock', async () => {
    // Bite: omit it from both lists — the server's set check refuses the publish (`409 rotation_plan_changed`).
    const unreadable = heldScope(SCOPE_ELSEWHERE, 'GROUP', { tenantId: 't2', active: false })
    unreadable.members.get(ME)!.grant.grantSignature = b64std(v1.randomBytes(64)) // this device refuses it (bad_signature)
    const before = { ...unreadable.members.get(ME)!.grant }
    const srv = fakeServer({ scopes: [...ordinaryScopes(), unreadable] })
    const result = await rotate()
    expect(srv.publishes.at(-1)!.unreadableScopeIds).toEqual([SCOPE_ELSEWHERE])
    expect(result.unreadableScopeIds).toEqual([SCOPE_ELSEWHERE])
    expect(srv.applied[0].rows.has(SCOPE_ELSEWHERE)).toBe(false)
    expect(unreadable.members.get(ME)!.grant).toEqual(before)
    expect(unreadable.currentEpoch).toBe(0)
    expect(srv.regrant.has(SCOPE_ELSEWHERE)).toBe(false)
  })

  it('the entry’s dry run names exactly the scope the stage declares, and writes nothing (decision 11)', async () => {
    const unreadable = heldScope(SCOPE_ELSEWHERE, 'GROUP', { tenantId: 't2', active: false })
    unreadable.members.get(ME)!.grant.grantSignature = b64std(v1.randomBytes(64))
    const srv = fakeServer({ scopes: [...ordinaryScopes(), unreadable] })
    expect(await unreadableOnThisDevice(await readRotationPlan())).toEqual([SCOPE_ELSEWHERE])
    expect(srv.openRotationStage).not.toHaveBeenCalled()
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
    const result = await rotate()
    expect(result.unreadableScopeIds).toEqual([SCOPE_ELSEWHERE]) // the stage, judging by the same rule, agrees
  })

  it('the old identity never reaches the vault, and every copy of it this rotation took is zeroed', async () => {
    fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    for (const [, priv] of vi.mocked(setUnlocked).mock.calls) expect(b64std(priv.sign)).not.toBe(b64std(OLD_ID.priv.sign))
    // Every copy the vault handed out before it swapped is a copy of the old identity: the rotation's own, the stage's
    // grant opens'. Each is zeroed by the time the call returns.
    const swapAt = vi.mocked(setUnlocked).mock.invocationCallOrder[0]
    const { results, invocationCallOrder } = vi.mocked(identityPrivate).mock
    const oldCopies = results
      .filter((_, i) => invocationCallOrder[i] < swapAt)
      .map((r) => r.value as { kemSeed: Uint8Array; sign: Uint8Array } | null)
      .filter((p): p is { kemSeed: Uint8Array; sign: Uint8Array } => p !== null)
    expect(oldCopies.length).toBeGreaterThan(1)
    for (const p of oldCopies) expect(p.sign.every((b) => b === 0) && p.kemSeed.every((b) => b === 0)).toBe(true)
  })

  describe('a failure the server did not answer (decision 12)', () => {
    it('unknown outcome: the publish got no answer and the re-read fails too — the vault is locked, never "nothing was changed"', async () => {
      const srv = fakeServer({ scopes: ordinaryScopes(), rotateFails: fetchFailed() })
      srv.failReread()
      const outcome = await rotate().catch((e: unknown) => e)
      expect(outcome).toBeInstanceOf(RotationOutcomeUnknown)
      expect(clearVault).toHaveBeenCalled()
      expect(vault.mk).toBeNull()
      expect(setUnlocked).not.toHaveBeenCalled()
    })

    it('unknown outcome: the re-read serves an identity neither key opens — locked as well', async () => {
      const srv = fakeServer({ scopes: ordinaryScopes() })
      srv.rotateAccountKeys.mockImplementationOnce(async () => {
        srv.acct.wrappedIdentity = b64std(v1.seal({ purpose: 'identity-priv' }, v1.randomBytes(32), v1.serializeIdentityPrivate(v1.generateIdentity())))
        throw httpError(502)
      })
      await expect(rotate()).rejects.toBeInstanceOf(RotationOutcomeUnknown)
      expect(vault.mk).toBeNull()
    })

    it.each([
      ['a fetch that rejected', fetchFailed],
      ['a 500', () => httpError(500)],
      ['a proxy’s 504', () => httpError(504)],
    ])('control: %s whose re-read shows the old identity is rethrown as it is — never too large, the vault on the old key', async (_kind, make) => {
      // #553's control restated: no answer is never `requestTooLarge`, and it is now READ, not assumed.
      const failure = make()
      const srv = fakeServer({ scopes: ordinaryScopes(), rotateFails: failure, pins: sealedPins(3), register: sealedRegister(2) })
      await expect(rotate()).rejects.toBe(failure)
      expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
      expect(srv.getAccountKeyMaterial.mock.invocationCallOrder.at(-1)).toBeGreaterThan(srv.rotateAccountKeys.mock.invocationCallOrder[0])
      expect(setUnlocked).not.toHaveBeenCalled()
      expect(same(vault.mk!, MK)).toBe(true)
    })

    it.each([
      ['403 secret_key_proof_failed', () => httpError(403, 'secret_key_proof_failed')],
      ['422 key_chain_step_invalid', () => httpError(422, 'key_chain_step_invalid')],
      ['409 rotation_unavailable', () => httpError(409, 'rotation_unavailable')],
    ])('a declared refusal (%s) wrote nothing: rethrown without a re-read', async (_kind, make) => {
      const failure = make()
      const srv = fakeServer({ scopes: ordinaryScopes() })
      const reads = () => srv.getAccountKeyMaterial.mock.calls.length
      let readsAtPublish = -1
      srv.rotateAccountKeys.mockImplementation(async () => {
        readsAtPublish = reads()
        throw failure
      })
      await expect(rotate()).rejects.toBe(failure)
      expect(reads()).toBe(readsAtPublish)
      expect(setUnlocked).not.toHaveBeenCalled()
    })
  })

  describe('409 rotation_plan_changed (decision 3)', () => {
    it('a scope added between stage and publish is re-staged ALONE into the same stage, and the publish goes through', async () => {
      // Bite: re-stage the whole set instead of the delta — the second put carries the scopes already staged.
      const scopes = ordinaryScopes()
      const srv = fakeServer({
        scopes,
        beforePublish: (n) => {
          if (n === 1) srv.scopes.set(SCOPE_JOINED, heldScope(SCOPE_JOINED, 'GROUP'))
        },
      })
      await rotate()
      expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(2)
      expect(srv.openRotationStage).toHaveBeenCalledTimes(1)
      const puts = srv.putRotationStageGrants.mock.calls.map((c) => c[0].rotationStageGrants.grants.map((g) => g.scopeId))
      expect(puts).toEqual([[SCOPE_PERSONAL, SCOPE_TENANT], [SCOPE_JOINED]])
      expect(srv.publishes[1].stageId).toBe(srv.publishes[0].stageId)
      expect(srv.publishes[1].identityPublicKey).toBe(srv.publishes[0].identityPublicKey)
      expect(srv.publishes[1].stagedGrantsDigest).not.toBe(srv.publishes[0].stagedGrantsDigest)
      expect([...srv.applied[0].rows.keys()].sort()).toEqual([SCOPE_PERSONAL, SCOPE_TENANT, SCOPE_JOINED])
      expect(srv.scopes.get(SCOPE_JOINED)!.currentEpoch).toBe(1)
    })

    it('a scope that rotated between stage and publish is re-staged at its new epoch, alone', async () => {
      const srv = fakeServer({
        scopes: ordinaryScopes(),
        beforePublish: (n) => {
          if (n !== 1) return
          const tenant = srv.scopes.get(SCOPE_TENANT)!
          tenant.keys.push(v1.randomBytes(32))
          tenant.currentEpoch = 1
          tenant.links.set(1, b64std(v1.seal({ purpose: 'scope-epoch', scopeId: SCOPE_TENANT, epoch: 1 }, tenant.keys[1], tenant.keys[0])))
          tenant.members.get(ME)!.grant = grantRow(tenant, 1, { userId: ME, bundle: OLD_PUB }, bSigner)
        },
      })
      await rotate()
      const puts = srv.putRotationStageGrants.mock.calls.map((c) => c[0].rotationStageGrants.grants.map((g) => [g.scopeId, g.epoch]))
      expect(puts).toEqual([[[SCOPE_PERSONAL, 0], [SCOPE_TENANT, 0]], [[SCOPE_TENANT, 1]]])
      expect(srv.applied[0].rows.get(SCOPE_TENANT)!.epoch).toBe(1)
    })

    it('a stage the server no longer holds is opened afresh and staged whole', async () => {
      const srv = fakeServer({
        scopes: ordinaryScopes(),
        beforePublish: (n) => {
          if (n === 1) srv.expireStage()
        },
      })
      await rotate()
      expect(srv.openRotationStage).toHaveBeenCalledTimes(2)
      expect(srv.publishes[1].stageId).not.toBe(srv.publishes[0].stageId)
      expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(2)
    })

    it('after 404 rotation_stage_not_found on the delta, a fresh stage takes everything', async () => {
      const srv = fakeServer({
        scopes: ordinaryScopes(),
        beforePublish: (n) => {
          if (n !== 1) return
          srv.expireStage()
          srv.scopes.set(SCOPE_JOINED, heldScope(SCOPE_JOINED, 'GROUP'))
        },
      })
      await rotate()
      const puts = srv.putRotationStageGrants.mock.calls.map((c) => c[0].rotationStageGrants.grants.map((g) => g.scopeId))
      // The delta went to the stage the server no longer had (404), then everything to a fresh one.
      expect(puts).toEqual([[SCOPE_PERSONAL, SCOPE_TENANT], [SCOPE_JOINED], [SCOPE_PERSONAL, SCOPE_TENANT, SCOPE_JOINED]])
      expect(srv.openRotationStage).toHaveBeenCalledTimes(2)
    })

    it('a plan that keeps changing is bounded: after five publishes it rejects, and the vault keeps the old key', async () => {
      let n = 0
      const srv = fakeServer({
        scopes: ordinaryScopes(),
        beforePublish: () => {
          n += 1
          srv.scopes.set(`5c09e000-0000-4000-8000-00000000f0${String(n).padStart(2, '0')}`, heldScope(`5c09e000-0000-4000-8000-00000000f0${String(n).padStart(2, '0')}`, 'GROUP'))
        },
      })
      await expect(rotate()).rejects.toMatchObject({ message: 'http 409 rotation_plan_changed' })
      expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(5)
      expect(setUnlocked).not.toHaveBeenCalled()
    })
  })

  it('more than 256 scopes are staged in several chunks, and the digest covers every one', async () => {
    const many = Array.from({ length: 300 }, (_, i) =>
      heldScope(`5c09e000-0000-4000-8000-${String(i).padStart(12, '0')}`, 'GROUP', { blocked: 'scope_deleting' }),
    )
    const srv = fakeServer({ scopes: many })
    await rotate()
    const sizes = srv.putRotationStageGrants.mock.calls.map((c) => c[0].rotationStageGrants.grants.length)
    expect(sizes).toEqual([256, 44])
    expect(srv.applied[0].rows.size).toBe(300)
    expect(srv.rotateScope).not.toHaveBeenCalled() // blocked: no next epoch now, and none is asked for
  }, 60_000)
})

// ADR 0111 decisions 20 and 21: what this account rooted survives its securing, re-signed by the
// NEW identity through the stage — and what the server lists that the outgoing key never signed is declared, never vouched
// for. The fake server verifies every upload under the stage's identity over what it stores, and the publish requires
// every open invite and every rooted record staged.
describe('securing the account re-signs what it rooted (ADR 0111 decisions 20 and 21)', () => {
  /** An open invite into [scope] at its current epoch, its invite key signed by [signer] and its anchor by [anchorSigner]. */
  function planInvite(scope: Scope, signer: v1.Identity, anchorSigner: v1.Identity = signer): RotationPlanInvite {
    const inviteKey = invite.inviteKeyFromSeed(invite.generateInviteSeed())
    const inviteId = crypto.randomUUID()
    const invitePublicKey = v1.serializeIdentityPublic(inviteKey.pub)
    const expiresAt = Math.floor(Date.now() / 1000) + 3600
    const anchor = sealGrant(
      scope.keys[scope.currentEpoch], { scopeId: scope.id, kind: 'TENANT', epoch: scope.currentEpoch },
      { userId: inviteId, identityPublicKeyB64: b64std(invitePublicKey) }, anchorSigner.priv.sign,
    )
    return {
      tenantId: scope.id, inviteId, invitePublicKey: b64std(invitePublicKey), expiresAt: new Date(expiresAt * 1000),
      inviteKeySignature: b64std(invite.signInviteKey(signer.priv.sign, { tenantId: scope.id, inviteId, invitePublicKey, expiresAt })),
      anchorWrappedGrant: anchor.wrappedGrant, anchorEpoch: scope.currentEpoch, anchorGrantSignature: anchor.grantSignature,
    }
  }
  /** [joinerId]'s join record in [scope] through an invite [signerUserId] signed with [signer], sealed under the scope's
   *  current key as the server stores it — with an endorsement by [endorsedBy] when given. */
  function rootedRecord(
    scope: Scope, joinerId: string, signer: v1.Identity, o: { signerUserId?: string; endorsedBy?: v1.Identity; key?: Uint8Array } = {},
  ): RotationPlanJoinRecord {
    const inviteKey = invite.inviteKeyFromSeed(invite.generateInviteSeed())
    const joiner = v1.generateIdentity()
    const inviteId = crypto.randomUUID()
    const invitePublicKey = v1.serializeIdentityPublic(inviteKey.pub)
    const expiresAt = Math.floor(Date.now() / 1000) + 3600
    const address = { tenantId: scope.id, userId: joinerId }
    const record = invite.signJoinRecord(address, {
      identityPublicKey: v1.serializeIdentityPublic(joiner.pub),
      secretKeyProofPublicKey: v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)).publicKey,
      inviteId, invitePublicKey,
      inviteKeySignature: invite.signInviteKey(signer.priv.sign, { tenantId: scope.id, inviteId, invitePublicKey, expiresAt }),
      inviteExpiresAt: expiresAt, signerUserId: o.signerUserId ?? ME, signerIdentityPublicKey: v1.serializeIdentityPublic(signer.pub),
    }, inviteKey.priv.sign, joiner.priv.sign)
    const sealed = invite.sealJoinRecord(o.key ?? scope.keys[scope.currentEpoch], address, record)
    const joinEndorsement = o.endorsedBy
      ? b64std(invite.signJoinEndorsement(o.endorsedBy.priv.sign, { ...address, signerUserId: ME, sealedJoinRecord: sealed }))
      : null
    return { ...address, sealedJoinRecord: b64std(sealed), joinEndorsement }
  }
  const tenantOf = (srv: ReturnType<typeof fakeServer>) => srv.scopes.get(SCOPE_TENANT)!
  const stagedInvites = (srv: ReturnType<typeof fakeServer>) =>
    srv.putRotationStageInvites.mock.calls.flatMap((c) => c[0].rotationStageInvites.invites)
  const stagedEndorsements = (srv: ReturnType<typeof fakeServer>) =>
    srv.putRotationStageJoinEndorsements.mock.calls.flatMap((c) => c[0].rotationStageJoinEndorsements.endorsements)
  /** [r]'s endorsement as the publish wrote it, verified under the identity the vault now holds. */
  const endorsedByPublished = (r: RotationPlanJoinRecord) =>
    !!r.joinEndorsement && invite.joinEndorsementVerifies(
      vaultPub().sign, { tenantId: r.tenantId, userId: r.userId, signerUserId: ME, sealedJoinRecord: fromB64std(r.sealedJoinRecord) },
      fromB64std(r.joinEndorsement),
    )

  it('an open invite this account signed is re-signed by the NEW identity — the invite key and the anchor — and survives', async () => {
    const scopes = ordinaryScopes()
    const mine = planInvite(scopes[1], OLD_ID)
    const srv = fakeServer({ scopes, invites: [{ ...mine }] })
    await rotate()
    const [st] = stagedInvites(srv)
    expect(st.inviteId).toBe(mine.inviteId)
    expect(srv.endedInvites).toEqual([])
    const now = srv.invites[0]
    const signed = { tenantId: SCOPE_TENANT, inviteId: mine.inviteId, invitePublicKey: fromB64std(mine.invitePublicKey), expiresAt: mine.expiresAt.getTime() / 1000 }
    const scope = { scopeId: SCOPE_TENANT, kind: 'TENANT', epoch: mine.anchorEpoch } as const
    const to = { userId: mine.inviteId, identityPublicKeyB64: mine.invitePublicKey }
    expect(invite.inviteKeySignatureVerifies(vaultPub().sign, signed, fromB64std(now.inviteKeySignature))).toBe(true)
    expect(grantSignatureVerifies(vaultPub().sign, scope, to, mine.anchorWrappedGrant, now.anchorGrantSignature)).toBe(true)
    // …and no longer under the retired key.
    expect(invite.inviteKeySignatureVerifies(OLD_ID.pub.sign, signed, fromB64std(now.inviteKeySignature))).toBe(false)
  })

  it('a plan invite whose current signatures are not the outgoing key\'s is DECLARED, never re-signed — and the publish ends it', async () => {
    const scopes = ordinaryScopes()
    const operator = v1.generateIdentity()
    const foreignKey = planInvite(scopes[1], operator, OLD_ID) // its invite key signed by another key
    const foreignAnchor = planInvite(scopes[1], OLD_ID, operator) // its anchor signed by another key
    const mine = planInvite(scopes[1], OLD_ID) // control
    const srv = fakeServer({ scopes, invites: [{ ...foreignKey }, { ...foreignAnchor }, { ...mine }] })
    await rotate()
    const byId = new Map(stagedInvites(srv).map((st) => [st.inviteId, st]))
    for (const id of [foreignKey.inviteId, foreignAnchor.inviteId]) {
      expect(byId.get(id)).toEqual({ inviteId: id, anchorEpoch: 0, inviteKeySignature: null, anchorGrantSignature: null })
    }
    expect(byId.get(mine.inviteId)!.inviteKeySignature).not.toBeNull()
    expect(srv.endedInvites.sort()).toEqual([foreignKey.inviteId, foreignAnchor.inviteId].sort())
    expect(srv.invites.map((i) => i.inviteId)).toEqual([mine.inviteId])
  })

  it('a join record naming the outgoing key is endorsed by the NEW identity, over the sealed record as stored', async () => {
    const scopes = ordinaryScopes()
    const record = rootedRecord(scopes[1], U1, OLD_ID)
    const srv = fakeServer({ scopes, records: [{ ...record }] })
    await rotate()
    expect(stagedEndorsements(srv)).toHaveLength(1)
    expect(endorsedByPublished(srv.records[0])).toBe(true)
  })

  it('a record a RETIRED key signed is declared without an endorsement, and endorsed again with one by the outgoing key', async () => {
    const scopes = ordinaryScopes()
    const retired = v1.generateIdentity() // a key this account held before its last securing
    const bare = rootedRecord(scopes[1], U1, retired)
    const endorsed = rootedRecord(scopes[1], U2, retired, { endorsedBy: OLD_ID })
    const byRetired = rootedRecord(scopes[1], U3, retired, { endorsedBy: retired }) // the thief's own endorsement
    const srv = fakeServer({ scopes, records: [{ ...bare }, { ...endorsed }, { ...byRetired }] })
    await rotate()
    const staged = new Map(stagedEndorsements(srv).map((e) => [e.userId, e.endorsement]))
    expect(staged.get(U1)).toBeNull()
    expect(staged.get(U3)).toBeNull()
    expect(staged.get(U2)).not.toBeNull()
    const after = new Map(srv.records.map((r) => [r.userId, r]))
    expect(after.get(U1)!.joinEndorsement).toBeNull()
    expect(after.get(U3)!.joinEndorsement).toBeNull()
    expect(endorsedByPublished(after.get(U2)!)).toBe(true)
  })

  it('a record this device cannot open, or one naming another signer, is declared (control: the account\'s own opens)', async () => {
    const scopes = ordinaryScopes()
    const unopenable = rootedRecord(scopes[1], U1, OLD_ID, { key: v1.randomBytes(32) })
    const otherSigner = rootedRecord(scopes[1], U2, OLD_ID, { signerUserId: B })
    const control = rootedRecord(scopes[1], U3, OLD_ID)
    const srv = fakeServer({ scopes, records: [{ ...unopenable }, { ...otherSigner }, { ...control }] })
    await rotate()
    const staged = new Map(stagedEndorsements(srv).map((e) => [e.userId, e.endorsement]))
    expect(staged.get(U1)).toBeNull()
    expect(staged.get(U2)).toBeNull()
    expect(staged.get(U3)).not.toBeNull()
  })

  it('409 rotation_plan_changed: an invite minted and a record written between stage and publish are staged ALONE, same stage', async () => {
    const scopes = ordinaryScopes()
    const first = planInvite(scopes[1], OLD_ID)
    const srv = fakeServer({
      scopes,
      invites: [{ ...first }],
      beforePublish: (n) => {
        if (n !== 1) return
        srv.invites.push(planInvite(tenantOf(srv), OLD_ID))
        srv.records.push(rootedRecord(tenantOf(srv), U1, OLD_ID))
      },
    })
    await rotate()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(2)
    expect(srv.openRotationStage).toHaveBeenCalledTimes(1)
    const puts = srv.putRotationStageInvites.mock.calls.map((c) => c[0].rotationStageInvites.invites.map((i) => i.inviteId))
    expect(puts).toEqual([[first.inviteId], [srv.invites[1].inviteId]])
    expect(srv.putRotationStageGrants).toHaveBeenCalledTimes(1) // the self-grants did not move: none went up again
    expect(endorsedByPublished(srv.records[0])).toBe(true)
  })

  it('a stage the server no longer holds takes the invites and records whole again, into the fresh stage', async () => {
    const scopes = ordinaryScopes()
    const mine = planInvite(scopes[1], OLD_ID)
    const srv = fakeServer({
      scopes, invites: [{ ...mine }], records: [rootedRecord(scopes[1], U1, OLD_ID)],
      beforePublish: (n) => {
        if (n === 1) srv.expireStage()
      },
    })
    await rotate()
    expect(srv.openRotationStage).toHaveBeenCalledTimes(2)
    expect(srv.putRotationStageInvites).toHaveBeenCalledTimes(2)
    expect(srv.putRotationStageJoinEndorsements).toHaveBeenCalledTimes(2)
    expect(srv.putRotationStageInvites.mock.calls[1][0].stageId).toBe(srv.publishes[1].stageId)
  })

  it('more than 256 open invites go up in chunks', async () => {
    const scopes = ordinaryScopes()
    const many = Array.from({ length: 300 }, () => planInvite(scopes[1], OLD_ID))
    const srv = fakeServer({ scopes, invites: many })
    await rotate()
    expect(srv.putRotationStageInvites.mock.calls.map((c) => c[0].rotationStageInvites.invites.length)).toEqual([256, 44])
    expect(srv.endedInvites).toEqual([])
  }, 60_000)
})

// ADR 0111 decision 13: the outgoing key's verifications are carried to the new key — read BEFORE the publish, while the
// old identity is still the account's, and re-signed by the new identity after it. A statement placed in this account's
// slot after the publish (a thief's, signed with the retired key) is never among them.
// ADR 0099 § 4: a holder of the instance moderation key keeps it through securing the account. The publish deletes the
// grants sealed to the identity it retires, so the client — which holds `M_n` here, through the outgoing identity —
// re-seals every live epoch to the new identity inside the same stage; the fake server, like the real one, refuses a
// holder's publish without them. What carries the weight is the sole holder: nobody else could deliver the key again.
describe('securing the account keeps the moderation key it holds (ADR 0099 § 4)', () => {
  const stagedModeration = (srv: ReturnType<typeof fakeServer>): StagedModerationGrant[] =>
    srv.putRotationStageModerationGrants.mock.calls.flatMap((c) => c[0].rotationStageModerationGrants.grants)
  /** [e]'s grant as the server holds it after the publish, opened with [priv] — the vault's (the published identity). */
  const openHeld = (e: ModerationEpoch, priv = vault.priv!) =>
    v1.openAs({ purpose: 'moderation-grant', epoch: e.epoch }, priv.kemSeed, fromB64std(e.sealedGrant!))

  it('a sole holder\'s live epochs — a pre-rotation one and the current one — are re-sealed to the NEW identity and open to the same key', async () => {
    const pre = heldEpoch(1)
    const current = heldEpoch(2)
    const srv = fakeServer({ scopes: ordinaryScopes(), moderation: [pre, current] })
    await rotate()
    expect(stagedModeration(srv).map((g) => g.epoch).sort()).toEqual([1, 2])
    expect(srv.putRotationStageModerationGrants.mock.invocationCallOrder[0]).toBeLessThan(srv.rotateAccountKeys.mock.invocationCallOrder[0])
    for (const e of [pre, current]) {
      expect(same(openHeld(e), e.m), `epoch ${e.epoch} opens under the published identity`).toBe(true)
      expect(() => openHeld(e, oldPriv()), `epoch ${e.epoch} is not the retired identity's`).toThrow()
    }
  })

  it('an account that is no moderator stages no epoch, and its publish goes through (control: the same flow)', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    expect(srv.listMyModerationGrants).toHaveBeenCalled()
    expect(srv.putRotationStageModerationGrants).not.toHaveBeenCalled()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
  })

  it('a grant this device cannot open, or one whose key the published bundle disowns, is declared — the rest re-sealed', async () => {
    const unopenable = { ...heldEpoch(1), sealedGrant: moderationGrantTo(1, v1.randomBytes(32), B_PUB) }
    const disowned = { ...heldEpoch(2), published: v1.moderationBundle(v1.randomBytes(32)) }
    const fine = heldEpoch(3)
    const srv = fakeServer({ scopes: ordinaryScopes(), moderation: [unopenable, disowned, fine] })
    await rotate()
    const staged = new Map(stagedModeration(srv).map((g) => [g.epoch, g.sealedGrant]))
    expect(staged.get(1)).toBeNull()
    expect(staged.get(2)).toBeNull()
    expect(staged.get(3)).not.toBeNull()
    expect(same(openHeld(fine), fine.m)).toBe(true)
  })

  it('409 rotation_plan_changed: an epoch delivered between stage and publish is re-sealed ALONE, into the same stage', async () => {
    const first = heldEpoch(1)
    const delivered = heldEpoch(2)
    const srv = fakeServer({
      scopes: ordinaryScopes(),
      moderation: [first],
      beforePublish: (n) => {
        if (n === 1) srv.moderation!.push(delivered)
      },
    })
    await rotate()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(2)
    expect(srv.openRotationStage).toHaveBeenCalledTimes(1)
    const puts = srv.putRotationStageModerationGrants.mock.calls.map((c) => c[0].rotationStageModerationGrants.grants.map((g) => g.epoch))
    expect(puts).toEqual([[1], [2]])
    expect(srv.putRotationStageGrants).toHaveBeenCalledTimes(1) // the self-grants did not move: none went up again
    for (const e of [first, delivered]) expect(same(openHeld(e), e.m), `epoch ${e.epoch}`).toBe(true)
  })
})

describe('securing the account re-signs this account\'s verifications (ADR 0111 decision 13)', () => {
  /** The tenant's verification rows: ME's verification of B, as ME's OLD identity signed it at ME's genesis head — and,
   *  once the publish committed, a statement the retired key signs about a stranger (the thief's). */
  function verificationsIn(srv: ReturnType<typeof fakeServer>) {
    const tenant = srv.scopes.get(SCOPE_TENANT)!
    const row = (subjectUserId: string, subjectHead: Uint8Array): MemberVerification => {
      const address = { tenantId: SCOPE_TENANT, verifierUserId: ME, subjectUserId }
      const v = verification.signVerification(address, { subjectHead, verifierHead: srv.acct.chain.head }, OLD_ID.priv.sign)
      return { verifierUserId: ME, subjectUserId, epoch: 0, sealedStatement: b64std(verification.sealVerification(tenant.keys[0], { ...address, epoch: 0 }, v)), createdAt: new Date() }
    }
    const mine = row(B, B_CHAIN.head)
    const thief = row(U1, v1.randomBytes(32))
    const listVerifications = vi.fn<TenantsApi['listVerifications']>(async () => ({
      verifications: srv.publishes.length ? [mine, thief] : [mine], more: false,
    }))
    const putMyVerification = vi.fn<TenantsApi['putMyVerification']>(async () => undefined)
    fakeApi(tenantsApi, { listVerifications, putMyVerification })
    return { tenant, listVerifications, putMyVerification }
  }

  it('collects before the publish and re-signs after it: the new identity, the new head, the same subject and head', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes() })
    const { tenant, listVerifications, putMyVerification } = verificationsIn(srv)
    await rotate()

    expect(listVerifications).toHaveBeenCalledWith(expect.objectContaining({ tenantId: SCOPE_TENANT, verifierUserId: ME }))
    expect(listVerifications.mock.invocationCallOrder[0]).toBeLessThan(srv.rotateAccountKeys.mock.invocationCallOrder[0])
    expect(putMyVerification).toHaveBeenCalledTimes(1) // the thief's statement, there only after the publish, is not carried
    expect(putMyVerification.mock.invocationCallOrder[0]).toBeGreaterThan(srv.rotateAccountKeys.mock.invocationCallOrder[0])

    const put = putMyVerification.mock.calls[0][0]
    expect(put).toMatchObject({ tenantId: SCOPE_TENANT, subjectUserId: B, putVerificationRequest: { epoch: 0 } })
    const address = { tenantId: SCOPE_TENANT, verifierUserId: ME, subjectUserId: B }
    const v = verification.openVerification(tenant.keys[0], { ...address, epoch: 0 }, fromB64std(put.putVerificationRequest.sealedStatement))
    expect(b64std(v.subjectHead)).toBe(headOf(B_CHAIN))
    expect(b64std(v.verifierHead)).toBe(b64std(srv.acct.chain.head)) // the head the publish appended
    const published = v1.deserializeIdentityPublic(fromB64std(srv.acct.bundle))
    expect(verification.verificationVerifies(address, v, published.sign)).toBe(true)
    expect(verification.verificationVerifies(address, v, OLD_ID.pub.sign)).toBe(false)
  })

  it('a publish that is refused re-signs nothing', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), rotateFails: httpError(403, 'secret_key_proof_failed') })
    const { putMyVerification } = verificationsIn(srv)
    await expect(rotate()).rejects.toBeDefined()
    expect(putMyVerification).not.toHaveBeenCalled()
  })
})

describe('what securing the account leaves behind', () => {
  it('carries the Memory pins of EVERY scope across the new Master Key, after the vault swapped', async () => {
    const elsewhere = heldScope(SCOPE_ELSEWHERE, 'GROUP', { tenantId: 't2', active: false })
    fakeServer({ scopes: [...ordinaryScopes(), elsewhere] })
    let swapped = false
    vi.mocked(resealFavoritesUnderNewMasterKey).mockImplementationOnce(async () => {
      swapped = vi.mocked(setUnlocked).mock.calls.length > 0
    })
    await rotate()
    expect(resealFavoritesUnderNewMasterKey).toHaveBeenCalledTimes(1)
    expect([...vi.mocked(resealFavoritesUnderNewMasterKey).mock.calls[0][2]].sort()).toEqual([SCOPE_PERSONAL, SCOPE_TENANT, SCOPE_ELSEWHERE])
    expect(swapped).toBe(true)
  })

  it('carries this device’s trust-state marks to the new Master Key', async () => {
    await raiseMarks(MK, { pins: 3, register: 2, epochs: { [SCOPE_ELSEWHERE]: 4 } })
    fakeServer({ scopes: ordinaryScopes(), pins: sealedPins(3), register: sealedRegister(2) })
    await rotate()
    const marks = await readMarks(vault.mk!)
    expect(marks).toMatchObject({ pins: 4, register: 3 })
    expect(marks.epochs).toMatchObject({ [SCOPE_ELSEWHERE]: 4, [SCOPE_PERSONAL]: 1, [SCOPE_TENANT]: 1 })
  })

  it('re-tags the caller’s item marks from the old Master Key to the one the vault ends up holding — with the vault already on it', async () => {
    let seen: [Uint8Array, Uint8Array] | null = null
    let swappedBefore = false
    vi.mocked(retagItemMarksUnderNewMasterKey).mockImplementationOnce(async (o, n) => {
      seen = [o.slice(), n.slice()]
      swappedBefore = vi.mocked(setUnlocked).mock.calls.length > 0
    })
    fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    expect(seen).not.toBeNull()
    expect(b64std(seen![0])).toBe(b64std(MK))
    expect(b64std(seen![1])).toBe(b64std(vault.mk!))
    expect(swappedBefore).toBe(true)
  })

  // The CR's invariant holds in the browser too: no retired private material is kept. This tab's session vault sealed
  // the Master Key the publish retired, so a reload would have restored it (phase 4's finding). Bite: drop the carry
  // from the swap → the first case is red.
  /** What each carry was handed — copied, because the rotation zeroes its own key once it returns. */
  const carried: Uint8Array[] = []
  beforeEach(() => {
    carried.length = 0
    vi.mocked(carrySessionVault).mockImplementation(async (mk) => void carried.push(mk.slice()))
  })

  it('re-seals this tab’s session vault under the PUBLISHED Master Key, right after the vault swapped', async () => {
    fakeServer({ scopes: ordinaryScopes() })
    await rotate()
    expect(carrySessionVault).toHaveBeenCalledTimes(1)
    expect(b64std(carried[0])).toBe(b64std(vault.mk!))
    expect(b64std(carried[0])).not.toBe(b64std(MK))
    expect(vi.mocked(carrySessionVault).mock.invocationCallOrder[0]).toBeGreaterThan(vi.mocked(setUnlocked).mock.invocationCallOrder[0])
  })

  it('a retry that finds the publish committed carries the session vault to the key it re-opened', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), abandonAfterPublish: true })
    await rotate()
    srv.reconnect()
    vault.mk = MK.slice()
    vault.priv = oldPriv()
    vi.mocked(carrySessionVault).mockClear()
    await expect(rotateKeys(PASSWORD, NEXT.secretKeyB64, { secretKeyB64: mintSecretKey() })).resolves.toMatchObject({ outcome: 'resumed' })
    expect(carrySessionVault).toHaveBeenCalledTimes(1)
    expect(b64std(carried[0])).toBe(b64std(vault.mk!))
    expect(b64std(carried[0])).not.toBe(b64std(MK))
  })

  it('an unknown outcome drops the session vault with the vault — neither key is kept for a reload', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), rotateFails: fetchFailed() })
    srv.failReread()
    await expect(rotate()).rejects.toBeInstanceOf(RotationOutcomeUnknown)
    expect(clearSessionVault).toHaveBeenCalled()
    expect(carrySessionVault).not.toHaveBeenCalled()
  })

  it('control: a rotation refused before any write leaves the session vault alone', async () => {
    fakeServer({ scopes: ordinaryScopes() })
    await expect(rotateKeys(PASSWORD, b64std(v1.randomBytes(32)), { secretKeyB64: mintSecretKey() })).rejects.toBeInstanceOf(RotationRefused)
    expect(carrySessionVault).not.toHaveBeenCalled()
    expect(clearSessionVault).not.toHaveBeenCalled()
  })

  it('does not fail a published rotation because the favorites or the item marks could not be carried across', async () => {
    vi.mocked(resealFavoritesUnderNewMasterKey).mockRejectedValueOnce(new Error('offline'))
    vi.mocked(retagItemMarksUnderNewMasterKey).mockRejectedValueOnce(new Error('offline'))
    fakeServer({ scopes: ordinaryScopes() })
    await expect(rotate()).resolves.toMatchObject({ outcome: 'published' })
    expect(vi.mocked(setUnlocked)).toHaveBeenCalled()
  })
})

describe('the pin store and the register ride in the publish (ADR 0060 § Consequences, inbox #192)', () => {
  const sent = (srv: ReturnType<typeof fakeServer>, n = 0) => srv.publishes[n]

  it('leaves neither document under the retired key, even when their own endpoints are down', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), pins: sealedPins(3), register: sealedRegister(2) })
    await rotate()
    forgetVerifiedPins()
    expect(await getPin(U1)).toEqual(PINNED[U1])
    expect((await readRegisterView(['fam1'], null, 'me')).names).toEqual({ fam1: 'Work laptop' })
    const req = sent(srv)
    expect(req.identityPins.revision).toBe(3)
    const head = JSON.parse(dec.decode(v1.open(pinsAt(4), vault.mk!, fromB64std(req.identityPins.head))))
    expect(head).toMatchObject({ version: 2, count: 2 })
    expect(req.identityPins).toMatchObject({ remove: [], replaceAll: true })
    expect(pinsOn(vault.mk!, srv.pins)).toEqual(PINNED)
    expect(req.deviceRegister.revision).toBe(2)
    expect(JSON.parse(dec.decode(v1.open(registerAt(3), vault.mk!, fromB64std(req.deviceRegister.blob))))).toEqual(REGISTERED)
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
    expect(srv.putDeviceRegister).not.toHaveBeenCalled()
  })

  it('an account that never wrote either document sends both empty, sealed for revision 1 against 0', async () => {
    const srv = fakeServer({ pins: emptyPinStore() })
    await rotate()
    const req = sent(srv)
    expect(req.identityPins.revision).toBe(0)
    expect(JSON.parse(dec.decode(v1.open(pinsAt(1), vault.mk!, fromB64std(req.identityPins.head))))).toMatchObject({ version: 2, count: 0 })
    expect(req.identityPins.put).toEqual([])
    expect(req.deviceRegister.revision).toBe(0)
    expect(JSON.parse(dec.decode(v1.open(registerAt(1), vault.mk!, fromB64std(req.deviceRegister.blob))))).toEqual({ devices: {}, families: {}, wraps: {} })
  })

  it('control: a first attempt that lands is the only call', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), pins: sealedPins(3), register: sealedRegister(2) })
    await rotate()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
  })

  it('a stale document fails the attempt, not the rotation: it re-reads, re-seals and publishes the same keys', async () => {
    const srv = fakeServer({ scopes: ordinaryScopes(), pins: sealedPins(3), register: sealedRegister(2), staleRotations: 2 })
    await rotate()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(3)
    expect(sent(srv, 2).identityPins.revision).toBe(5)
    expect(sent(srv, 2).identityPublicKey).toBe(sent(srv, 0).identityPublicKey)
    expect(sent(srv, 2).stageId).toBe(sent(srv, 0).stageId)
    expect(srv.pins.revision).toBe(6)
    expect(vi.mocked(setUnlocked)).toHaveBeenCalledTimes(1)
  })

  it('re-keys every pin entry: new ids under the new Master Key, every entry re-sealed, the old ids gone', async () => {
    const three = { [U1]: pinOf(identityKey()), [U2]: pinOf(identityKey()), [U3]: pinOf(identityKey()) }
    const srv = fakeServer({ pins: sealedPins(3, three), register: sealedRegister(2) })
    const oldIds = [...srv.pins.entries.keys()].sort()
    await rotate()
    const req = sent(srv).identityPins
    expect(req.replaceAll).toBe(true)
    const newIds = [U1, U2, U3].map((u) => pinIdOf(vault.mk!, u)).sort()
    expect(req.put.map((e) => e.pinId).sort()).toEqual(newIds)
    expect(newIds.filter((id) => oldIds.includes(id))).toEqual([])
    expect(pinsOn(vault.mk!, srv.pins)).toEqual(three)
  })

  it('past the bound it rejects — and the vault still holds the old key, because nothing was published', async () => {
    const srv = fakeServer({ pins: sealedPins(3), register: sealedRegister(2), staleRotations: 99 })
    await expect(rotate()).rejects.toMatchObject({ message: 'http 409 stale_revision' })
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(5)
    expect(vi.mocked(setUnlocked)).not.toHaveBeenCalled()
  })

  // uc.account.rotate-keys E10: a 413 means the server read none of the body, and the same body fails the same way
  // again, so it is a refusal of its own and never retried. By status alone — a proxy's own 413 carries no code.
  it.each([
    ['coded', httpError(413, 'request_too_large')],
    ['bare', httpError(413)],
  ])('a %s 413 on the publish is refused as too large, once, and the vault keeps the old key', async (_kind, failure) => {
    const srv = fakeServer({ pins: sealedPins(3), register: sealedRegister(2), rotateFails: failure })
    await expect(rotate()).rejects.toMatchObject({ name: 'RotationRefused', reason: 'requestTooLarge' })
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
    expect(vi.mocked(setUnlocked)).not.toHaveBeenCalled()
  })

  it.each([
    ['pins', 'a pin store below the mark', { pins: 5 }, () => ({ pins: sealedPins(4) })],
    ['register', 'a register below the mark', { register: 5 }, () => ({ register: sealedRegister(4) })],
    ['pins', 'a pin store that does not open', {}, () => ({ pins: sealedPins(3, PINNED, v1.randomBytes(32)) })],
  ] as const)('%s: %s is refused before anything is written — no laundering into the new key', async (_d, _n, marks, served) => {
    await raiseMarks(MK, marks)
    const srv = fakeServer(served())
    await expect(rotate()).rejects.toMatchObject({ reason: 'trustStateRefused' })
    expect(srv.rotateAccountKeys).not.toHaveBeenCalled()
    expect(srv.putIdentityPins).not.toHaveBeenCalled()
    expect(srv.putDeviceRegister).not.toHaveBeenCalled()
    expect(vi.mocked(setUnlocked)).not.toHaveBeenCalled()
  })

  it('control: served AT the mark, the rotation proceeds', async () => {
    await raiseMarks(MK, { pins: 5, register: 5 })
    const srv = fakeServer({ pins: sealedPins(5), register: sealedRegister(5) })
    await expect(rotate()).resolves.toBeDefined()
    expect(srv.rotateAccountKeys).toHaveBeenCalledTimes(1)
  })
})
