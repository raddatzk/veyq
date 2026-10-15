// The four live defects ADR 0072 closes, each driven through the production read path with a server that serves
// one object's ciphertext in another object's slot. Every negative is anchored on the specific refusal, and every
// one has its control: the same read of the object's own ciphertext, which must open.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ scopesApi: vi.fn(), accountApi: vi.fn() }))
// masterKey: no unlocked account key here, so this device holds no trust-state marks (ADR 0028 Decision 7) — the
// grant binding under test is the only check in play.
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn(), masterKey: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { scopesApi, accountApi } from '../../api/client'
import { signGrant } from '../spaces/grant'
import { identityPrivate } from '../../app/vault'
import { nodeContentKey, type TreeNode } from '../../core/node/tree'
import { summaryToItem, gridRef, makeChunkProvider } from './media'
import { unsealNodeMeta, sealNodeMeta } from '../../core/node/nodeMeta'
import { openScopeById } from '../spaces/scopes'
import { MEMORY_KIND } from './kinds'
import { MediaDerivativeKind, type MediaSummary } from '../../api/generated/media'
import { ScopeKind, type ScopeSummary } from '../../api/generated/scopes'
import { stubApi } from '../../test/apiMock'
import { accountKeyMaterial, treeNode } from '../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-000000000001'
const OTHER_SCOPE = '5c09e000-0000-4000-8000-000000000002'
const MEMORY_S = '5c09e000-0000-4000-8000-00000000000a'
const MEMORY_X = '5c09e000-0000-4000-8000-00000000000b'
const MEDIA_A = '5c09e000-0000-4000-8000-0000000000a1'
const MEDIA_B = '5c09e000-0000-4000-8000-0000000000b1'
const THUMB_A = '5c09e000-0000-4000-8000-0000000000a2'

/** The one refusal a moved ciphertext must meet — not a network error, a parse error, or any other throw. */
function expectRefused(fn: () => unknown, purpose: string): void {
  let thrown: unknown
  try {
    fn()
  } catch (e) {
    thrown = e
  }
  expect(thrown).toBeInstanceOf(v1.OpenRefused)
  expect((thrown as v1.OpenRefused).purpose).toBe(purpose)
}

const epochsOf = (key: Uint8Array) => ({ current: 0, keys: new Map([[0, key]]) })
const kmemWrap = (scopeKey: Uint8Array, nodeId: string, kMem: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE, nodeId, epoch: 0, placementVersion: 0 }, scopeKey, kMem))
const memoryRow = (id: string, wrappedContentKey: string): TreeNode => treeNode({
  id, parentId: null, kind: MEMORY_KIND, orderIndex: 0, epoch: 0, name: id, wrappedContentKey, wrappedMeta: null, metaVersion: 0, placementVersion: 0,
})

describe('the upload chain: a Memory key wrap served on another Memory\'s row', () => {
  const scopeKey = v1.randomBytes(32)
  const kS = v1.randomBytes(32)
  const kX = v1.randomBytes(32)
  const wrapS = kmemWrap(scopeKey, MEMORY_S, kS)
  const wrapX = kmemWrap(scopeKey, MEMORY_X, kX)

  it('refuses to resolve X\'s key from S\'s wrap — else every upload into X lands under S\'s key', () => {
    const tree = [memoryRow(MEMORY_S, wrapS), memoryRow(MEMORY_X, wrapS)]
    expectRefused(() => nodeContentKey(SCOPE, epochsOf(scopeKey), tree, MEMORY_X), 'content-key')
  })

  it('control: X\'s own wrap resolves to X\'s key', () => {
    const tree = [memoryRow(MEMORY_S, wrapS), memoryRow(MEMORY_X, wrapX)]
    expect(nodeContentKey(SCOPE, epochsOf(scopeKey), tree, MEMORY_X)).toEqual(kX)
  })
})

describe('a thumbnail ref moved from media A to media B', () => {
  const memoryKey = v1.randomBytes(32)
  const thumbKey = v1.randomBytes(32)
  // A's thumbnail, sealed where it lives: owned by media A, in the THUMBNAIL role.
  const thumbWrap = b64std(v1.seal({ purpose: 'blob-key', scopeId: SCOPE, ownerId: MEDIA_A, blobId: THUMB_A, role: 'THUMBNAIL' }, memoryKey, thumbKey))
  const row = (mediaId: string): MediaSummary => ({
    mediaId, blobId: '5c09e000-0000-4000-8000-0000000000ff', chunkCount: 1, byteSize: 44, wrappedBlobKey: b64std(v1.randomBytes(60)),
    derivatives: [{ kind: MediaDerivativeKind.Thumbnail, blobId: THUMB_A, chunkCount: 1, byteSize: 44, wrappedBlobKey: thumbWrap }],
  } as MediaSummary)

  it('does not open under B', () => {
    expectRefused(() => makeChunkProvider(SCOPE, memoryKey, gridRef(summaryToItem(row(MEDIA_B))), async () => []), 'blob-key')
  })

  it('control: under A it opens', () => {
    expect(() => makeChunkProvider(SCOPE, memoryKey, gridRef(summaryToItem(row(MEDIA_A))), async () => [])).not.toThrow()
  })
})

describe('a scope grant served in another scope\'s row', () => {
  const ID = v1.generateIdentity()
  const copyPriv = () => ({ kemSeed: ID.priv.kemSeed.slice(), sign: ID.priv.sign.slice() })
  // Scope A's own grant, sealed as A's (scope A, epoch 0).
  const grantOf = (key: Uint8Array) =>
    b64std(v1.sealTo({ purpose: 'scope-grant', scopeId: SCOPE, epoch: 0 }, v1.hybridPublic(ID.priv), key))
  const keyA = v1.randomBytes(32)
  const ME = '0e000000-0000-4000-8000-0000000000e8'
  const PUB = b64std(v1.serializeIdentityPublic(ID.pub))
  // Each row carries a signature that VERIFIES for that row — the signature layer (ADR 0028 Decision 6) held off, so
  // what refuses the moved grant below is the seal's own binding (ADR 0072), the layer this block is about.
  const row = (id: string, wrappedGrant: string): ScopeSummary => ({
    id, kind: ScopeKind.Group, tenantId: SCOPE, role: 'OWNER', wrappedGrant, grantedEpoch: 0, nameEpoch: 0, iconEpoch: 0, currentEpoch: 0,
    granterUserId: ME, granterIdentityPublicKey: PUB,
    grantSignature: signGrant(ID.priv.sign, { scopeId: id, kind: 'GROUP', epoch: 0 }, { userId: ME, identityPublicKeyB64: PUB }, wrappedGrant),
  })
  beforeEach(() => {
    vi.mocked(identityPrivate).mockImplementation(() => copyPriv())
    stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ME }) })
  })
  const serve = (rows: ScopeSummary[]) => stubApi(scopesApi, { listMyScopes: rows })

  it('does not open', async () => {
    serve([row(SCOPE, grantOf(keyA)), row(OTHER_SCOPE, grantOf(keyA))])
    let thrown: unknown
    await openScopeById(OTHER_SCOPE).catch((e) => (thrown = e))
    expect(thrown).toBeInstanceOf(v1.OpenRefused)
    expect((thrown as v1.OpenRefused).purpose).toBe('scope-grant')
  })

  it('control: in its own row it opens', async () => {
    serve([row(SCOPE, grantOf(keyA))])
    expect((await openScopeById(SCOPE))?.key).toEqual(keyA)
  })
})

describe('node meta sealed at one node, served for another', () => {
  const kMem = v1.randomBytes(32)
  const meta = { v: 1, date: { mode: 'fixed', start: '2026-07-01' } }

  const sealedAtS = sealNodeMeta(kMem, { scopeId: SCOPE, nodeId: MEMORY_S }, meta)

  it('does not open for the other node — the reader sees no meta at all, not the moved one', () => {
    expect(unsealNodeMeta(kMem, { scopeId: SCOPE, nodeId: MEMORY_X }, sealedAtS)).toEqual({ v: 1 })
    // …and underneath the best-effort reader it is the specific refusal, not a parse error.
    expectRefused(
      () => v1.open({ purpose: 'node-meta', scopeId: SCOPE, nodeId: MEMORY_X }, kMem, fromB64std(sealedAtS)),
      'node-meta',
    )
  })

  it('control: its own node opens it', () => {
    expect(unsealNodeMeta(kMem, { scopeId: SCOPE, nodeId: MEMORY_S }, sealedAtS)).toEqual(meta)
  })
})
