// The node listing's conditional GET on the wire (ADR 0081): recorded at `fetch` — the headers the real generated client
// sends and the status and headers it is answered with, no mock of the API client. A load sends no `If-None-Match`; a
// poll sends the tag of what is shown, byte for byte as the server wrote it; a 304 reads as "unchanged" (nothing parsed,
// nothing opened) and any other failure still rejects; the new tag of a 200 is what the NEXT poll sends.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({ status: 'ready', scopeId: '11111111-1111-4111-8111-111111111111', tenantId: 't-1', kind: 'PERSONAL' }),
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, new Uint8Array(32).fill(7)]]) }),
  }
})

import { fetchTree } from './tree'
import { loadTree, pollTree, resetTree, treeStore } from './treeStore'

const SCOPE = '11111111-1111-4111-8111-111111111111'

type Sent = { url: string; ifNoneMatch: string | null }

/** Record every request; answer each from `answers` in turn. */
function recordFetch(...answers: Response[]) {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    sent.push({ url: String(input), ifNoneMatch: new Headers(init?.headers).get('if-none-match') })
    const answer = answers.shift()
    if (!answer) throw new Error('unexpected request')
    return answer
  }))
  return sent
}
const rows = (etag: string | null, body: unknown[] = []) =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json', ...(etag ? { ETag: etag } : {}) } })
const notModified = () => new Response(null, { status: 304 })

beforeEach(() => resetTree())
afterEach(() => vi.unstubAllGlobals())

describe('fetchTree', () => {
  it('sends no If-None-Match on a plain load and returns the rows with the ETag the server named', async () => {
    const sent = recordFetch(rows('"7"'))
    const read = await fetchTree(SCOPE)
    expect(sent).toEqual([{ url: `/api/scopes/${SCOPE}/nodes`, ifNoneMatch: null }])
    expect(read).toEqual({ rows: [], etag: '"7"' })
  })

  it('sends the tag it is given exactly, and a 304 is null: nothing to open', async () => {
    const sent = recordFetch(notModified())
    expect(await fetchTree(SCOPE, '"7"')).toBeNull()
    expect(sent[0].ifNoneMatch).toBe('"7"')
  })

  it('a 200 to a conditional read carries the rows and the new tag', async () => {
    recordFetch(rows('"8"', []))
    expect(await fetchTree(SCOPE, '"7"')).toEqual({ rows: [], etag: '"8"' })
  })

  it('reads a response without a tag as tag-less (the poll then falls back to a full read)', async () => {
    recordFetch(rows(null))
    expect((await fetchTree(SCOPE, '"7"'))?.etag).toBeNull()
  })

  it('rejects on every other failure — a 304 to a request that asked nothing included', async () => {
    recordFetch(new Response(JSON.stringify({ code: 'x' }), { status: 500, headers: { 'content-type': 'application/json' } }))
    await expect(fetchTree(SCOPE, '"7"')).rejects.toMatchObject({ response: { status: 500 } })
    recordFetch(notModified())
    await expect(fetchTree(SCOPE)).rejects.toMatchObject({ response: { status: 304 } })
  })
})

describe('the tree store over the wire', () => {
  it('echoes the load\'s tag on the first poll, treats a 304 as unchanged, and echoes the new tag of a moved scope next', async () => {
    const sent = recordFetch(rows('"1"'), notModified(), rows('"2"'), notModified())
    await loadTree()
    expect(treeStore.loaded).toBe(true)
    const shown = treeStore.nodes

    const signal = new AbortController().signal
    expect(await pollTree(signal)).toBe(false) // 304: nothing moved
    expect(await pollTree(signal)).toBe(true) // 200 with a new tag: the scope moved (these rows are the same ones)
    expect(await pollTree(signal)).toBe(false)

    expect(sent.map((s) => s.ifNoneMatch)).toEqual([null, '"1"', '"1"', '"2"'])
    expect(treeStore.nodes).toBe(shown) // same rows each time: never replaced
  })

  it('a tag-less server is polled in full every time', async () => {
    const sent = recordFetch(rows(null), rows(null))
    await loadTree()
    expect(await pollTree(new AbortController().signal)).toBe(true)
    expect(sent.map((s) => s.ifNoneMatch)).toEqual([null, null])
  })

  it('a failed poll rejects and leaves the page out of its error state', async () => {
    recordFetch(rows('"1"'), new Response(null, { status: 503 }))
    await loadTree()
    await expect(pollTree(new AbortController().signal)).rejects.toBeTruthy()
    expect(treeStore.error).toBe(false)
  })
})
