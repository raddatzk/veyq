// @vitest-environment happy-dom
// An import or a copy into a node not re-keyed since a rotation (ADR 0111 decision 11, inbox #563): both are a member's flow,
// so the create of each top node - core's folder, an application's own through its import hook (`ImportNodeContext.
// createOptions`) - pulls the target's path forward on this device, re-reads it and creates under its new key, instead of
// waiting for another device's re-key. A drop box is an owner's to re-key: its pull says no, and the create waits. What the
// run itself creates is minted at the current epoch and never lags. The pull itself is core's (`pullRekeyPath`,
// `core/node/rekey/rekeyScope.test.ts`); here only that the replay asks it, for which node, and what it does with the answer.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import type { EpochKeys } from '../../features/spaces/epochs'

const held = vi.hoisted(() => ({ epochs: { current: 0, keys: new Map() } as EpochKeys }))
vi.mock('../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return { activeSpace: reactive({ scopeId: '5c09e000-0000-4000-8000-0000000000cc' }), activeEpochKeys: () => held.epochs }
})
vi.mock('../../api/client', async (orig) => ({ ...(await orig<object>()), contentApi: vi.fn() }))
vi.mock('../node/rekey/rekeyScope', async (orig) => ({ ...(await orig<object>()), pullRekeyPath: vi.fn() }))
vi.mock('../node/rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn() }))
vi.mock('../node/treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn(async () => {}) }))

import { replaySubtrees, type ReplayItem } from './importSubtree'
import { contentApi } from '../../api/client'
import { fakeApi, type ApiMocks } from '../../test/apiMock'
import type { ContentApi } from '../../api/generated/content'
import { MANIFEST_FORMAT, MANIFEST_VERSION, type Manifest, type ManifestNode } from './manifest'
import { FOLDER_KIND } from '../node/kind'
import { openName } from '../node/tree'
import { pullRekeyPath } from '../node/rekey/rekeyScope'
import { nodeKeyForWrite } from '../node/rekey/floor'
import { MEMORY_KIND } from '../../features/memories/kinds'
import { MEMORY_CONTENT } from '../../features/memories/exportImport'
import { POT_KIND } from '../../features/expenses/kinds'
import { EXPENSES_POT_CONTENT } from '../../features/expenses/exportImport'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const PARENT = 'f0000000-0000-4000-8000-0000000000aa'
const OLD_KEY = v1.randomBytes(32)
const NEW_KEY = v1.randomBytes(32)

/** A thrown generated-client error carrying `409 { code }`, the shape `isCodedError` reads. */
const pending = () => Object.assign(new Error('node_rekey_pending'), {
  response: new Response(JSON.stringify({ code: 'node_rekey_pending' }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

const item = (root: ManifestNode, purpose: 'import' | 'copy' = 'import'): ReplayItem => ({
  manifest: { format: MANIFEST_FORMAT, version: MANIFEST_VERSION, exportedAt: '2026-10-28T00:00:00Z', appVersion: 'test', root } as Manifest,
  payload: vi.fn(),
  plan: { untrusted: false },
  purpose,
})
const FOLDER: ManifestNode = { id: 'x1', name: 'Trips', kind: FOLDER_KIND }
const MEMORY: ManifestNode = { id: 'x2', name: 'Rome', kind: MEMORY_KIND, content: { type: MEMORY_CONTENT, schema: 1, media: [] } }
const POT: ManifestNode = {
  id: 'x3', name: 'Ski', kind: POT_KIND,
  content: { type: EXPENSES_POT_CONTENT, schema: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [], participants: [], entries: [] },
}

const replay = (root: ManifestNode, purpose?: 'import' | 'copy') =>
  replaySubtrees({ scopeId: SCOPE, epochs: held.epochs, tree: [], parent: { id: PARENT, key: OLD_KEY, epoch: 0 }, items: [item(root, purpose)] })
const requests = () => createNode.mock.calls.map((c) => c[0].createNodeRequest)
/** The name a create sealed, opened under `key` (the parent's), as a reader of the parent opens it. */
const nameUnder = (key: Uint8Array, r: ReturnType<typeof requests>[number]) =>
  openName(key, { scopeId: SCOPE, nodeId: r.nodeId, epoch: r.epoch ?? 0, placementRevision: 0 }, r.encryptedName)

const pull = vi.fn(async () => true)
let createNode: ApiMocks<ContentApi, 'createNode'>['createNode']
beforeEach(() => {
  vi.clearAllMocks()
  ;({ createNode } = fakeApi(contentApi, { createNode: vi.fn<ContentApi['createNode']>() }))
  held.epochs = { current: 0, keys: new Map() }
  vi.mocked(pullRekeyPath).mockReturnValue(pull)
  pull.mockResolvedValue(true)
  // the re-read after the pull: the target at the scope's current epoch under its new key
  vi.mocked(nodeKeyForWrite).mockImplementation(() => ({ key: NEW_KEY, epoch: 1 }))
  createNode.mockImplementation(async (r) => ({ nodeId: r.createNodeRequest.nodeId }))
})
afterEach(() => vi.useRealTimers())

describe('an import or a copy into a target not re-keyed yet (ADR 0111 decision 11)', () => {
  it.each([
    ['a folder (core\'s create)', FOLDER, 'import'],
    ['a folder copied (the same replay)', FOLDER, 'copy'],
    ['a Memory (its application\'s import hook)', MEMORY, 'import'],
    ['a Pot (its application\'s import hook)', POT, 'import'],
  ] as const)('%s: refused node_rekey_pending, the target\'s path is pulled and the node created under its new key', async (_what, root, purpose) => {
    createNode.mockRejectedValueOnce(pending())
    await replay(root, purpose)
    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE, [PARENT])
    expect(pull).toHaveBeenCalledOnce()
    const last = requests().at(-1)!
    expect(last).toMatchObject({ parentId: PARENT, parentEpoch: 1 })
    expect(nameUnder(NEW_KEY, last)).toBe(root.name)
  })

  it('the device floor already shows the target lags: the pull runs before anything is sealed, one create', async () => {
    held.epochs = { current: 1, keys: new Map() }
    await replay(MEMORY)
    expect(pull).toHaveBeenCalledOnce()
    expect(requests()).toHaveLength(1)
    expect(requests()[0]).toMatchObject({ parentEpoch: 1 })
    expect(nameUnder(NEW_KEY, requests()[0])).toBe('Rome')
  })

  it('a drop box is an owner\'s to re-key: its pull says no, so the import waits - and lands once an owner\'s device re-keyed it', async () => {
    vi.useFakeTimers()
    pull.mockResolvedValue(false) // `pullRekeyPath` on a path through a drop box, for a member (`RekeyPathNotMineError`)
    createNode.mockRejectedValueOnce(pending())
    const run = replay(FOLDER)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(pull).toHaveBeenCalledOnce()
    expect(createNode).toHaveBeenCalledOnce() // still waiting
    await vi.advanceTimersByTimeAsync(2_000) // the first wait
    await run
    expect(createNode).toHaveBeenCalledTimes(2)
    expect(requests()[1]).toMatchObject({ parentEpoch: 1 })
  })
})
