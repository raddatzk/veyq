import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { createMemory, createChapter } from './memoryNodes'
import { openMemory, openContentKey } from '../../core/node/tree'
import { epochsOf } from '../spaces/epochsTestUtil'
import { contentApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi } from '../../api/generated/content'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std as fromB64 } from '@veyq/crypto/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const scopeKey = v1.randomBytes(32)

beforeEach(() => vi.clearAllMocks())

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
/** A createNode mock that echoes the client-minted node id back, as the server does (ADR 0072). */
function echoCreate() {
  return vi.fn<ContentApi['createNode']>(async (p) => ({ nodeId: p.createNodeRequest.nodeId }))
}
function mockContent(over: { createNode: ReturnType<typeof echoCreate> }) {
  fakeApi(contentApi, { ...over })
}
const nameCtx = (nodeId: string, epoch = 0) => ({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch }) as const

describe('createMemory', () => {
  it('seals the name, blinds the slug, and wraps a K_mem that opens back (operator-blind)', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })

    const { nodeId, memoryKey } = await createMemory(SCOPE_ID, epochsOf(scopeKey), 'Italien 2026')
    expect(memoryKey.length).toBe(32)

    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('MEMORY')
    // the client mints the node id (a canonical UUID) and the returned id is the one every seal is bound to
    expect(req.nodeId).toMatch(UUID)
    expect(nodeId).toBe(req.nodeId)
    // name round-trips under K_SC, bound to this node; the server only ever saw ciphertext
    expect(new TextDecoder().decode(v1.open(nameCtx(nodeId), scopeKey, fromB64(req.encryptedName)))).toBe('Italien 2026')
    // wrapped K_mem opens to exactly the returned key
    expect(Array.from(openMemory(SCOPE_ID, scopeKey, { id: nodeId, epoch: 0, wrappedContentKey: req.wrappedContentKey ?? null })!)).toEqual(Array.from(memoryKey))
    // ...and is refused on any other node's row (a server moving the wrap gets no key)
    expect(() => openMemory(SCOPE_ID, scopeKey, { id: crypto.randomUUID(), epoch: 0, wrappedContentKey: req.wrappedContentKey ?? null }))
      .toThrow(v1.OpenRefused)
    // slug token = blinded MAC under K_SC over the root parent slot (the scope id) + the slug
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, SCOPE_ID, 'italien-2026')))
  })
})

describe('createChapter — recursive K_mem (cr-0060)', () => {
  it('wraps the chapter K_mem under the PARENT Memory key, not K_SC', async () => {
    const create = echoCreate()
    mockContent({ createNode: create })
    const parentKey = v1.randomBytes(32) // the parent Memory's K_mem
    const MEM = '5c09e000-0000-4000-8000-00000000000a'

    const { nodeId, chapterKey } = await createChapter(SCOPE_ID, epochsOf(scopeKey), parentKey, 'Tag 1', MEM)

    const req = create.mock.calls[0][0].createNodeRequest
    expect(req.kind).toBe('CHAPTER')
    expect(req.parentId).toBe(MEM)
    expect(req.nodeId).toMatch(UUID)
    expect(nodeId).toBe(req.nodeId)
    // the name is still sealed under K_SC (whole-tree navigation), the slug too
    expect(new TextDecoder().decode(v1.open(nameCtx(nodeId), scopeKey, fromB64(req.encryptedName)))).toBe('Tag 1')
    expect(req.slugToken).toBe(b64std(v1.slugToken(scopeKey, MEM, 'tag-1')))
    const row = { id: nodeId, epoch: 0, wrappedContentKey: req.wrappedContentKey ?? null }
    // the K_mem opens under the PARENT key…
    expect(Array.from(openContentKey(SCOPE_ID, parentKey, row)!)).toEqual(Array.from(chapterKey))
    // …and NOT under K_SC (a wrong KEK can't open it — crypto-enforced least privilege)
    expect(() => openContentKey(SCOPE_ID, scopeKey, row)).toThrow(v1.OpenRefused)
  })
})
