// Bite test (e) of cr-node-key-hierarchy slice 2, on the wire: a chapter's create is recorded at `fetch` — the JSON
// the real generated client serializes, not the object handed to it — and its `encryptedName` opens under the
// Memory's `K_mem` and NOT under `K_SC` (ADR 0080 Decision 2: a chapter's entry is its parent's). The slug token is
// keyed the same way. No mock of the API client here: a field the client dropped or renamed would fail this.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { createChapter } from './memoryNodes'
import { epochsOf } from '../spaces/epochsTestUtil'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const MEMORY_ID = '5c09e000-0000-4000-8000-00000000000a'

afterEach(() => vi.unstubAllGlobals())

describe('createChapter on the wire (ADR 0080 Decision 2)', () => {
  it('sends a name and a slug token under the Memory\'s K_mem, not under K_SC', async () => {
    const bodies: { url: string; method: string; json: Record<string, unknown> }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const json = JSON.parse(String(init?.body)) as Record<string, unknown>
      bodies.push({ url: String(input), method: init?.method ?? 'GET', json })
      return new Response(JSON.stringify({ nodeId: json.nodeId }), { status: 201, headers: { 'Content-Type': 'application/json' } })
    }))
    const scopeKey = v1.randomBytes(32)
    const memoryKey = v1.randomBytes(32) // the parent Memory's K_mem

    const { nodeId } = await createChapter(SCOPE_ID, epochsOf(scopeKey), memoryKey, 'Tag 1', MEMORY_ID)

    expect(bodies).toHaveLength(1)
    const { url, method, json } = bodies[0]
    expect(method).toBe('POST')
    expect(url).toContain(`/api/scopes/${SCOPE_ID}/nodes`)
    expect(json.parentId).toBe(MEMORY_ID)
    const name = fromB64std(json.encryptedName as string)
    const ctx = { purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch: 0, placementVersion: 0 } as const
    expect(new TextDecoder().decode(v1.open(ctx, memoryKey, name))).toBe('Tag 1')
    expect(() => v1.open(ctx, scopeKey, name)).toThrow(v1.OpenRefused)
    expect(json.slugToken).toBe(b64std(v1.slugToken(memoryKey, MEMORY_ID, 'tag-1')))
    expect(json.slugToken).not.toBe(b64std(v1.slugToken(scopeKey, MEMORY_ID, 'tag-1')))
  })
})
