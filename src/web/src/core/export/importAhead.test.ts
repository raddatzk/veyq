// @vitest-environment node
// The import's check ahead (inbox #557, `importSubtree.ts` `archivePayload`) through the REAL door: a check started for the
// payload after the one a hook holds may fail - a read the browser refuses - and nobody may ever ask for that payload. Its
// failure must then be nobody's: no unhandled rejection. `importHardening.test.ts` cannot show it, since the spy it records
// the door with attaches its own handlers to every promise the door returns.
//
// Bite: drop the `.catch` on the check ahead and the first case goes red (one unhandled rejection).
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../node/tree', async (orig) => ({ ...(await orig<typeof import('../node/tree')>()), createContentNode: vi.fn(), deleteNode: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { registry } from '../applications/registry'
import { createContentNode, deleteNode } from '../node/tree'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import type { ByteSink } from '../blob/stream'
import type { EpochKeys } from '../../features/spaces/epochs'
import { importSubtree, openExport } from './importSubtree'
import type { ManifestPayload } from './manifest'
import { ZipWriter } from './zip'

const KIND = 'H_AHEAD'
const epochs = { current: 1, keys: new Map() } as unknown as EpochKeys
const text = (s: string) => new TextEncoder().encode(s)
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
// Two payloads of sizes no ZIP structure read has (7 and 13 bytes), so a read of 13 bytes is the second payload's.
const bodies = ['seven b', 'thirteen byte']
const paths = ['Home/Series/a.txt', 'Home/Series/b.txt']
let asks = 1

beforeAll(() => {
  registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
    kind: KIND, icon: 'file', shareable: false, contentSchema: { application: 'ha', version: 1 },
    async exportContent() {
      throw new Error('not exported here')
    },
    async importContent(ctx) {
      const made = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, KIND, ctx.name)
      for (const ref of (ctx.node.content!.files as ManifestPayload[]).slice(0, asks)) await ctx.payload(ref, 'x')
      return { nodeId: made.nodeId, nodeKey: made.contentKey }
    },
  })
})

async function download(): Promise<File> {
  const files = bodies.map((b, i) => ({ path: paths[i], size: b.length, mediaType: 'text/plain', sha256: hex(v1.sha256Digest(text(b))) }))
  const root = { id: 'n1', kind: 'FOLDER', name: 'Home', path: 'Home', children: [
    { id: 'n2', kind: KIND, name: 'Series', path: 'Home/Series', content: { type: 'ha.series', schema: 1, files } },
  ] }
  const manifest = { format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [{ id: 'ha', contentSchema: 1 }], root }
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
  const w = new ZipWriter(sink, { modified: new Date('2026-10-29T00:00:00Z') })
  for (const [i, b] of bodies.entries()) await w.addBytes(paths[i], text(b))
  await w.addBytes('.veyq/manifest.json', text(JSON.stringify(manifest)))
  await w.finish()
  return new File(parts as BlobPart[], 'download.zip')
}

const unhandled: unknown[] = []
const onUnhandled = (reason: unknown) => void unhandled.push(reason)
beforeEach(() => {
  unhandled.length = 0
  process.on('unhandledRejection', onUnhandled)
  let n = 0
  vi.mocked(createContentNode).mockReset().mockImplementation(async () => ({ nodeId: `new${++n}`, contentKey: new Uint8Array(32), epoch: 1 }))
  vi.mocked(deleteNode).mockReset().mockResolvedValue(undefined)
  // The second payload's bytes cannot be read (a file changed on disk); every other read passes.
  const read = Blob.prototype.arrayBuffer
  vi.spyOn(Blob.prototype, 'arrayBuffer').mockImplementation(function (this: Blob) {
    return this.size === bodies[1].length ? Promise.reject(new DOMException('changed on disk', 'NotReadableError')) : read.call(this)
  })
})
afterEach(() => {
  process.off('unhandledRejection', onUnhandled)
  vi.restoreAllMocks()
})

const run = async () => importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened: await openExport({ kind: 'zip', file: await download() }) })
const settle = () => new Promise((r) => setTimeout(r, 20))

describe('a check ahead that fails is nobody\'s failure until its payload is asked for', () => {
  it('never asked for: the import succeeds and nothing is left unhandled', async () => {
    asks = 1
    await expect(run()).resolves.toMatchObject({ nodes: 2 })
    await settle()
    expect(unhandled).toEqual([])
  })

  it('control: asked for, the same failure is the import\'s', async () => {
    asks = 2
    const err = await run().catch((e) => e)
    expect(err.cause).toMatchObject({ name: 'NotReadableError' })
    await settle()
    expect(unhandled).toEqual([])
  })
})
