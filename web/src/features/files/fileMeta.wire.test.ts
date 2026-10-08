// A file's metadata write on the wire (ADR 0077): recorded at `fetch` — the JSON the real generated client serializes —
// so a field the client dropped or renamed fails here. The PATCH carries exactly the sealed meta and the version it was
// built from; the blob opens under the FILE node's key with the `node-meta` seal (a literal context here), and on a
// `409 stale_version` the change is re-run on the blob the server handed back, keeping a peer's sibling field.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { writeFileMeta } from './fileMeta'

const SCOPE = '5c09e000-0000-4000-8000-0000000000aa'
const NODE = '5c09e000-0000-4000-8000-0000000000f1'
const nodeKey = v1.randomBytes(32)
const META = { mime: 'text/markdown', size: 2048, modifiedAt: 1_700_000_000_000 }
const ctx = { purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE } as const
const openMeta = (b64: string) => JSON.parse(new TextDecoder().decode(v1.open(ctx, nodeKey, fromB64std(b64)))) as Record<string, unknown>
const sealMeta = (value: unknown) => b64std(v1.seal(ctx, nodeKey, new TextEncoder().encode(JSON.stringify(value))))

afterEach(() => vi.unstubAllGlobals())

type Sent = { url: string; method: string; json: Record<string, unknown> }
function server(answers: (sent: Sent) => Response): Sent[] {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const s = { url: String(input), method: init?.method ?? 'GET', json: JSON.parse(String(init?.body)) as Record<string, unknown> }
    sent.push(s)
    return answers(s)
  }))
  return sent
}

describe('a file\'s metadata write on the wire', () => {
  it('PATCHes the node with only the sealed meta and the version it was built from (0 for a new file)', async () => {
    const sent = server(() => new Response(null, { status: 204 }))

    const result = await writeFileMeta(SCOPE, NODE, nodeKey, { wrappedMeta: null, metaVersion: 0 }, META)

    expect(sent).toHaveLength(1)
    expect(sent[0].method).toBe('PATCH')
    expect(sent[0].url).toContain(`/api/scopes/${SCOPE}/nodes/${NODE}`)
    expect(Object.keys(sent[0].json).sort()).toEqual(['metaVersion', 'wrappedMeta'])
    expect(sent[0].json.metaVersion).toBe(0)
    expect(openMeta(sent[0].json.wrappedMeta as string)).toEqual({ v: 1, ...META })
    expect(() => v1.open({ ...ctx, nodeId: SCOPE }, nodeKey, fromB64std(sent[0].json.wrappedMeta as string))).toThrow(v1.OpenRefused)
    expect(result.state.metaVersion).toBe(1)
  })

  it('a replace builds on the node\'s current meta at its version and keeps a field it does not know', async () => {
    const sent = server(() => new Response(null, { status: 204 }))
    const base = { wrappedMeta: sealMeta({ v: 1, mime: 'text/plain', size: 1, modifiedAt: 1, note: 'kept' }), metaVersion: 4 }

    await writeFileMeta(SCOPE, NODE, nodeKey, base, META)

    expect(sent[0].json.metaVersion).toBe(4)
    expect(openMeta(sent[0].json.wrappedMeta as string)).toEqual({ v: 1, ...META, note: 'kept' })
  })

  it('on 409 stale_version re-runs on the blob the server handed back, at its version', async () => {
    const peer = sealMeta({ v: 1, mime: 'image/png', size: 9, modifiedAt: 9, peerField: 'theirs' })
    let calls = 0
    const sent = server(() =>
      ++calls === 1
        ? new Response(JSON.stringify({ code: 'stale_version', wrappedMeta: peer, metaVersion: 7 }), { status: 409, headers: { 'Content-Type': 'application/json' } })
        : new Response(null, { status: 204 }),
    )

    await writeFileMeta(SCOPE, NODE, nodeKey, { wrappedMeta: null, metaVersion: 0 }, META)

    expect(sent.map((s) => s.json.metaVersion)).toEqual([0, 7])
    expect(openMeta(sent[1].json.wrappedMeta as string)).toEqual({ v: 1, ...META, peerField: 'theirs' })
  })
})
