// @vitest-environment node
// A download's archive through the web's own adapters (`zip.ts`, `manifest.ts`) against contracts/platform/export/v1-vectors.json
// (ADR 0155 decision 7.2, the export archive's slice): what the web calls answers every case the core's ArchiveVectorsTest
// answers, so a rule mutated in Kotlin turns this red too. And it streams: the writer hands the sink each record as the core
// makes it (the case's write count), an archive is opened by slices that never reach its entries' bytes (`bounds.open`) and
// an entry is read a READ_CHUNK at a time — the file is never read whole. The digest worker's CRC-32 (`crc32.ts`, its own
// primitive) answers the same `crc32` cases. The adapters hold no rule; this holds the mapping (a Blob's slices as the core's
// reads, a ByteSink as its writes, a refusal as ZipError or ExportError).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { crc32, crc32Finish, crc32Start, crc32Update } from './crc32'
import { openZip, READ_CHUNK, ZipError, ZipWriter, zipLength, type ZipPlanEntry } from './zip'
import { collectPayloadsOf, ExportError, LIMITS, MANIFEST_FORMAT, MANIFEST_MAX_BYTES, MANIFEST_PATH, MANIFEST_PATH_V1, MANIFEST_VERSION, MAX_DEPTH, MAX_NODES, parseManifest } from './manifest'
import { assemble, type Part } from '../../test/mediaBytes'
import type { ByteSink } from '../blob/stream'

const v = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/platform/export/v1-vectors.json'), 'utf8'))
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex')
const pathOf = (p: any): string => (typeof p === 'string' ? p : p.repeat.repeat(p.times))

/** An archive whose slices are summed, and which refuses to be read whole. */
class CountingBlob extends Blob {
  read = 0
  largest = 0
  slice(start = 0, end = this.size, type?: string): Blob {
    const n = Math.min(end, this.size) - start
    this.read += n
    this.largest = Math.max(this.largest, n)
    return super.slice(start, end, type)
  }
  arrayBuffer(): Promise<ArrayBuffer> {
    throw new Error('the whole archive was read')
  }
}

function* chunked(b: Uint8Array, op: any) {
  if (op.chunks) {
    let at = 0
    for (const n of op.chunks) {
      yield b.subarray(at, at + n)
      at += n
    }
    return
  }
  const every = op.every ?? Math.max(1, b.length)
  for (let at = 0; at < b.length; at += every) yield b.subarray(at, at + every)
}

const planOf = (ops: any[]): ZipPlanEntry[] => ops.flatMap((op): ZipPlanEntry[] =>
  'dir' in op ? [{ path: pathOf(op.dir), directory: true }]
    : 'file' in op ? [{ path: pathOf(op.file), size: assemble(op.bytes).length }]
      : 'many' in op ? Array.from({ length: op.many.count }, (_, n) => ({ path: `${op.many.prefix}${n}`, size: 0 })) : [])

async function written(c: any) {
  const parts: Uint8Array[] = []
  let writes = 0
  const state = { closed: false, aborted: false }
  const sink: ByteSink = {
    write: async (b) => { parts.push(b.slice()); writes++ },
    close: async () => void (state.closed = true),
    abort: async () => void (state.aborted = true),
  }
  const w = new ZipWriter(sink, { modified: new Date(c.modified), zip64At: c.zip64At })
  const crcs: number[] = []
  for (const [i, op] of c.ops.entries()) {
    try {
      if ('dir' in op) await w.addDirectory(pathOf(op.dir))
      else if ('file' in op) {
        const b = assemble(op.bytes)
        crcs.push((await w.addFile(pathOf(op.file), op.size ?? b.length, chunked(b, op))).crc32)
      } else if ('many' in op) for (let n = 0; n < op.many.count; n++) await w.addFile(`${op.many.prefix}${n}`, 0, [])
      else await w.finish()
    } catch (e) {
      if (!(e instanceof ZipError)) throw e
      const finished = state.closed
      await w.abort(e)
      expect(state, 'a refused archive is aborted, never closed unless it was finished').toEqual({ closed: finished, aborted: true })
      return { error: { at: i, code: e.code } }
    }
  }
  if (!c.ops.some((o: any) => 'finish' in o)) await w.finish()
  expect(state.closed, 'the finished archive closes its sink').toBe(true)
  const bytes = new Uint8Array(await new Blob(parts as BlobPart[]).arrayBuffer())
  expect(zipLength(planOf(c.ops), { zip64At: c.zip64At }), 'the planned length is the written one').toBe(bytes.length)
  return { bytes, expected: { length: bytes.length, sha256: sha(bytes), ...(bytes.length <= 4096 ? { hex: hex(bytes) } : {}), writes, crc32: crcs } }
}

const archives = new Map<string, Uint8Array>()
async function archiveOf(s: any): Promise<Uint8Array> {
  let b: Uint8Array
  if (s.write !== undefined) {
    if (!archives.has(s.write)) archives.set(s.write, (await written(v.write.find((c: any) => c.rule === s.write))).bytes!)
    b = archives.get(s.write)!.slice()
  } else b = assemble(s.bytes)
  for (const p of s.patch ?? []) b.set(assemble([p.hex]), p.at)
  if (s.cut !== undefined) b = b.slice(0, s.cut)
  if (s.comment !== undefined) b = new Uint8Array([...b, ...assemble([s.comment])])
  return b
}

describe('the export archive through the web\'s adapters (contracts/platform/export/v1-vectors.json)', () => {
  it('the facts are the manifest\'s constants', () => {
    expect({ format: MANIFEST_FORMAT, version: MANIFEST_VERSION, path: MANIFEST_PATH, pathV1: MANIFEST_PATH_V1, maxBytes: MANIFEST_MAX_BYTES,
      maxDepth: MAX_DEPTH, maxNodes: MAX_NODES, limits: LIMITS, readChunk: READ_CHUNK }).toEqual(v.facts)
  })

  it('the CRC-32: the digest worker\'s primitive and the core\'s writer give each case\'s', async () => {
    for (const c of v.crc32 as { rule: string; bytes: Part[]; chunks?: number[]; expected: number }[]) {
      const b = assemble(c.bytes)
      let s = crc32Start()
      for (const chunk of chunked(b, { chunks: c.chunks })) s = crc32Update(s, chunk)
      expect(crc32Finish(s), c.rule).toBe(c.expected)
      expect(crc32(b), c.rule).toBe(c.expected)
      const w = new ZipWriter({ write: async () => {}, close: async () => {}, abort: async () => {} })
      expect((await w.addFile('f', b.length, chunked(b, { chunks: c.chunks }))).crc32, c.rule).toBe(c.expected)
    }
  })

  it('the DOS stamp of a written entry', async () => {
    for (const c of v.dos) {
      const parts: Uint8Array[] = []
      const w = new ZipWriter({ write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }, { modified: new Date(c.ms) })
      await w.addDirectory('x')
      const dv = new DataView(parts[0].buffer)
      expect({ date: dv.getUint16(12, true), time: dv.getUint16(10, true) }, c.rule).toEqual(c.expected)
    }
  })

  for (const c of v.write) {
    it(`write: ${c.rule}`, async () => {
      const got = await written(c)
      if ('error' in got) expect({ error: got.error }).toEqual(c.expected)
      else {
        archives.set(c.rule, got.bytes!)
        expect(got.expected).toEqual(c.expected)
      }
    }, 60_000)
  }

  it('the length at the format\'s own limits', () => {
    for (const c of v.length) {
      const plan = c.plan.flatMap((p: any) => 'many' in p ? Array.from({ length: p.many.count }, (_, n) => ({ path: `${p.many.prefix}${n}`, size: 0 })) : [p])
      expect(zipLength(plan, { zip64At: c.zip64At }), c.rule).toBe(c.expected)
    }
  })

  for (const c of v.read) {
    it(`read: ${c.rule}`, async () => {
      const file = new CountingBlob([await archiveOf(c.archive) as BlobPart])
      let zip
      try {
        zip = await openZip(file)
      } catch (e) {
        if (!(e instanceof ZipError)) throw e
        expect({ error: e.code }).toEqual(c.expected)
        return
      }
      expect(file.read, 'what opening read').toBeLessThanOrEqual(c.expected.bounds.open)
      const all = zip.entries
      const got: any = all.length > 50 ? { count: all.length, head: all.slice(0, 3), tail: all.slice(-3) } : { entries: all }
      if (c.stream) {
        got.streams = {}
        for (const name of c.stream) {
          const chunks: number[] = []
          file.largest = 0
          try {
            for await (const chunk of zip.stream(name)) chunks.push(chunk.length)
            got.streams[name] = { chunks }
          } catch (e) {
            if (!(e instanceof ZipError)) throw e
            got.streams[name] = { chunks, error: e.code }
          }
          expect(file.largest, 'an entry is read a READ_CHUNK at a time').toBeLessThanOrEqual(READ_CHUNK)
        }
      }
      if (c.small) {
        got.small = []
        for (const s of c.small) {
          try {
            got.small.push({ ...s, sha256: sha(await zip.bytes(s.name, s.max)) })
          } catch (e) {
            if (!(e instanceof ZipError)) throw e
            got.small.push({ ...s, error: e.code })
          }
        }
      }
      got.bounds = c.expected.bounds
      expect(got).toEqual(c.expected)
    }, 60_000)
  }

  const built = (b: any) => {
    const folder = (id: string, name: string, path: string, children: unknown[] = []) => ({ id, kind: 'FOLDER', name, path, children })
    let root: any
    if (b.deep !== undefined) {
      root = folder(`n${b.deep}`, 'd', Array.from({ length: b.deep + 1 }, () => 'd').join('/'))
      for (let i = b.deep - 1; i >= 0; i--) root = folder(`n${i}`, 'd', Array.from({ length: i + 1 }, () => 'd').join('/'), [root])
    } else root = folder('root', 'Home', 'Home', Array.from({ length: b.wide - 1 }, (_, i) => ({ id: `c${i}`, kind: 'FOLDER', name: 'c', path: `Home/c${i}` })))
    return { format: 'veyq-subtree', version: 2, exportedAt: '2026-11-23T10:00:00.000Z', appVersion: '1', applications: [{ id: 'depot', contentSchema: 1 }, { id: 'memories', contentSchema: 1 }], root }
  }
  it('the manifest\'s check', () => {
    for (const c of v.manifest) {
      const json = c.json ?? built(c.build)
      let got: unknown
      try {
        expect(parseManifest(json), 'the manifest taken is the value handed in').toBe(json)
        got = { ok: true }
      } catch (e) {
        if (!(e instanceof ExportError)) throw e
        got = { code: e.code, ...(e.detail !== undefined ? { detail: e.detail } : {}) }
      }
      expect(got, c.rule).toEqual(c.expected)
    }
  })

  it('the payload walk', () => {
    for (const c of v.payloads) expect(collectPayloadsOf(c.value).map((p) => p.path), c.rule).toEqual(c.expected)
  })
})
