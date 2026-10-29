import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'

// The window between a member accept and the newcomer's first open of the tenant (inbox #740, ADR 0111 decision 23): the
// accept checked the carried epoch against the owner's anchor (decision 19), so the device must HOLD that key from then
// on - else its first open takes whatever grant the server serves as a first grant, and an operator serves a higher
// epoch around a key of its choosing under a granter it fabricated right then. The accept runs through the real
// `openMemberInvite` / `acceptCheckedInvite`, the first open through the real `openScopeById`; only the network, the
// vault, the pin store and the tenant's join records are driven here.
vi.mock('../../api/client', () => ({ tenantsApi: vi.fn(), scopesApi: vi.fn(), contentApi: vi.fn(), accountApi: vi.fn() }))
vi.mock('@veyq/crypto/argonWorker', async () => {
  const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
  const { TEST_ARGON } = await vi.importActual<typeof import('@veyq/crypto/testArgon')>('@veyq/crypto/testArgon')
  return { inviteLinkKekAsync: async (secret: Uint8Array, salt: Uint8Array) => real.kekInviteLink(secret, salt, TEST_ARGON) }
})
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn(), masterKey: vi.fn() }))
// A first-seen granter is pinned and passes (TOFU) - the operator's lever for the TENANT scope's own grant, which the pin
// alone judges (ADR 0111 § What stays open).
vi.mock('../account/identityPins', async (o) => ({
  linkOf: (await o<typeof import('../account/identityPins')>()).linkOf,
  anchorIdentity: vi.fn(),
  anchorChainKey: vi.fn(),
  anchorServedChain: vi.fn(),
  getPin: vi.fn(),
}))
vi.mock('../account/tenantTrust', () => ({ loadTenantView: vi.fn(), cachedTenantView: vi.fn(), tenantViewOfScope: vi.fn() }))

import { acceptCheckedInvite, buildMemberJoin, forgetMemberInvite, openMemberInvite, type OpenedMemberInvite } from './tenantInvite'
import { openScopeById } from '../spaces/scopes'
import { heldEpochKey, epochKeyCommitment } from '../account/trustMarks'
import { anchorChainKey, anchorIdentity, anchorServedChain, getPin } from '../account/identityPins'
import { accountApi, scopesApi } from '../../api/client'
import { identityPrivate, masterKey } from '../../app/vault'
import { fakeApi, stubApi } from '../../test/apiMock'
import { accountKeyMaterial } from '../../test/models'
import { httpError } from '../../test/httpError'
import { carriedTo, memberInviteFixture, type MemberInviteFixture } from '../../test/memberInvite'
import { signGrant } from '../spaces/grant'
import { epochLinkContext } from '../spaces/epochs'
import type { ScopeEpochLink, ScopesApi, ScopeSummary } from '../../api/generated/scopes'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

/** The newcomer: its identity (the vault's), account id and Secret-Key proof key. */
const ID = v1.generateIdentity()
const ID_PUB = b64std(v1.serializeIdentityPublic(ID.pub))
const ME = '0e000000-0000-4000-8000-0000000000e7'
const PROOF = v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)).publicKey
/** The account's Master Key: what names this device's trust marks. */
const MK = v1.randomBytes(32)
/** A granter the operator fabricated, met for the first time at the first open. */
const OPERATOR = { userId: 'f0000000-0000-4000-8000-0000000000f7', identity: v1.generateIdentity() }
/** An honest co-member who rotated the tenant after the accept. */
const MEMBER = { userId: 'a0000000-0000-4000-8000-0000000000a7', identity: v1.generateIdentity() }

let f: MemberInviteFixture

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory() // a device that has held nothing yet
  vi.mocked(identityPrivate).mockImplementation(() => ({ kemSeed: ID.priv.kemSeed.slice(), sign: ID.priv.sign.slice() }))
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ME }) })
  vi.mocked(anchorIdentity).mockResolvedValue('firstUse')
  vi.mocked(anchorChainKey).mockResolvedValue({ anchor: 'firstUse', retired: false })
  vi.mocked(anchorServedChain).mockResolvedValue('firstUse')
  vi.mocked(getPin).mockResolvedValue(null)
  f = memberInviteFixture({ epoch: 1 })
})

/** The newcomer's join through [opened], as the accept sends it. */
const joinOf = (opened: OpenedMemberInvite) => buildMemberJoin(opened, { userId: ME, identity: ID, secretKeyProofPublicKey: PROOF })

/** Accept [f]'s invite as the view does: open and check it with the code, send the join (the server takes it), forget it.
 *  [accountKey] is a new account's Master Key, which the vault does not hold yet. Resolves to the self-grant sent. */
async function accept(accountKey?: Uint8Array) {
  const opened = await openMemberInvite(f.tenantId, f.served, f.sealedPart, f.code)
  let sent: ReturnType<typeof joinOf>['selfGrant'] | null = null
  try {
    await acceptCheckedInvite(opened, async (o) => {
      sent = joinOf(o).selfGrant
      return 'ok'
    }, async () => f.served, undefined, accountKey)
  } finally {
    forgetMemberInvite(opened)
  }
  return sent!
}

/** Serve the tenant scope with this account's grant at [epoch] wrapping [key] (signed by [granter]) beside [links]. */
function serveTenant(key: Uint8Array, epoch: number, granter: { userId: string; identity: v1.Identity }, links: ScopeEpochLink[] = []) {
  const wrappedGrant = b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: f.tenantId, epoch }, v1.hybridPublic(ID.priv), key))
  const scope = { scopeId: f.tenantId, kind: 'TENANT' as const, epoch }
  const row: ScopeSummary = {
    id: f.tenantId, kind: 'TENANT', tenantId: f.tenantId, role: 'MEMBER', nameEpoch: 0, iconEpoch: 0, grantedEpoch: epoch, currentEpoch: epoch,
    wrappedGrant, granterUserId: granter.userId, granterIdentityPublicKey: b64std(v1.serializeIdentityPublic(granter.identity.pub)),
    grantSignature: signGrant(granter.identity.priv.sign, scope, { userId: ME, identityPublicKeyB64: ID_PUB }, wrappedGrant),
  }
  fakeApi(scopesApi, {
    listMyScopes: vi.fn<ScopesApi['listMyScopes']>().mockResolvedValue([row]),
    listScopeEpochs: vi.fn<ScopesApi['listScopeEpochs']>().mockResolvedValue(links),
  })
}

/** The link the tenant stores at [epoch]: [prev] sealed under [key]. */
const linkUnder = (epoch: number, key: Uint8Array, prev: Uint8Array): ScopeEpochLink =>
  ({ epoch, wrappedPrev: b64std(v1.seal(epochLinkContext(f.tenantId, epoch), key, prev)) })

describe('a member accept holds the key it checked, so the first open of the tenant runs under decision 23 (inbox #740)', () => {
  it('measured on main: after a checked accept, the first open opened a higher epoch the operator planted - now refused', async () => {
    await accept()
    const planted = v1.randomBytes(32) // the operator never held E_1, so it cannot link its key down to it
    serveTenant(planted, 2, OPERATOR, [linkUnder(2, planted, v1.randomBytes(32))])
    expect(await openScopeById(f.tenantId)).toBeNull()
  })

  it('control: the self-grant the accept sent opens at the first open - the very key the device holds', async () => {
    const sent = await accept()
    serveTenant(f.tenantKey, 1, { userId: ME, identity: ID })
    expect(sent.epoch).toBe(1)
    expect((await openScopeById(f.tenantId))?.epochs.current).toBe(1)
  })

  it('control: an honest rotation after the accept, its link sealing the checked E_1, opens at the first open', async () => {
    await accept()
    const next = v1.randomBytes(32)
    serveTenant(next, 2, MEMBER, [linkUnder(2, next, f.tenantKey)])
    expect((await openScopeById(f.tenantId))?.epochs.current).toBe(2)
  })

  it('at the checked epoch itself, another key than the checked one is refused', async () => {
    await accept()
    serveTenant(v1.randomBytes(32), 1, OPERATOR)
    expect(await openScopeById(f.tenantId)).toBeNull()
  })

  it('a new account holds under its own Master Key, which the vault does not hold at its accept - and its first open, after the unlock, refuses the planted epoch', async () => {
    vi.mocked(masterKey).mockReturnValue(null) // registering: no vault yet
    const born = v1.randomBytes(32)
    await accept(born)
    vi.mocked(masterKey).mockImplementation(() => born.slice()) // signed in and unlocked on the same browser
    const planted = v1.randomBytes(32)
    serveTenant(planted, 2, OPERATOR)
    expect(await openScopeById(f.tenantId)).toBeNull()
    expect(await heldEpochKey(f.tenantId)).toEqual({ epoch: 1, commitment: epochKeyCommitment(f.tenantKey) })
  })

  it('a re-check after 409 invite_changed holds the re-checked invite\'s key at its epoch, not the first one', async () => {
    const opened = await openMemberInvite(f.tenantId, f.served, f.sealedPart, f.code)
    const carried = carriedTo(f, 3)
    let sends = 0
    try {
      await acceptCheckedInvite(opened, async () => {
        if (sends++ === 0) throw httpError(409, 'invite_changed')
        return 'ok'
      }, async () => carried.served)
    } finally {
      forgetMemberInvite(opened)
    }
    expect(await heldEpochKey(f.tenantId)).toEqual({ epoch: 3, commitment: epochKeyCommitment(carried.tenantKey) })
  })

  it('an accept the server refuses holds nothing', async () => {
    const opened = await openMemberInvite(f.tenantId, f.served, f.sealedPart, f.code)
    try {
      await expect(acceptCheckedInvite(opened, async () => {
        throw httpError(410, 'invite_expired')
      }, async () => f.served)).rejects.toBeDefined()
    } finally {
      forgetMemberInvite(opened)
    }
    expect(await heldEpochKey(f.tenantId)).toBeNull()
  })
})
