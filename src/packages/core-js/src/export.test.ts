import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'export.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const m = await import('@veyq/core-js/export')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/export/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const v = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/export/v1-vectors.json'), 'utf8'))

/** A case's bytes from its parts: hex, `{fill, count}`, `{repeat, times}`. */
function bytesOf(parts: any[]): Uint8Array {
  const flat = parts.flatMap((p) => (typeof p === 'object' && 'repeat' in p ? new Array(p.times).fill(p.repeat) : [p]))
  const out = new Uint8Array(flat.reduce((n, p) => n + (typeof p === 'string' ? p.length / 2 : p.count), 0))
  let at = 0
  for (const p of flat) {
    if (typeof p === 'string') for (let i = 0; i < p.length; i += 2) out[at++] = parseInt(p.slice(i, i + 2), 16)
    else {
      out.fill(p.fill, at, at + p.count)
      at += p.count
    }
  }
  return out
}
const pathOf = (p: any): string => (typeof p === 'string' ? p : p.repeat.repeat(p.times))
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex')
/** Each chunk handed over as a view into the middle of a larger buffer, so the façade must read the view, not its buffer. */
function chunksOf(b: Uint8Array, op: any): Uint8Array[] {
  const lengths: number[] = op.chunks ?? (() => {
    const every = op.every ?? Math.max(1, b.length)
    const out = []
    for (let at = 0; at < b.length; at += every) out.push(Math.min(every, b.length - at))
    return out
  })()
  let at = 0
  return lengths.map((n) => {
    const outer = new Uint8Array(n + 8).fill(0x7a)
    outer.set(b.subarray(at, at + n), 4)
    at += n
    return outer.subarray(4, 4 + n)
  })
}

async function written(c: any) {
  const parts: Uint8Array[] = []
  let writes = 0
  const w = m.zipWriter({ write: async (b: Uint8Array) => { parts.push(b.slice()); writes++ } }, c.modified, c.zip64At ?? 0xffffffff)
  const crcs: number[] = []
  for (const [i, op] of c.ops.entries()) {
    try {
      if ('dir' in op) await m.zipAddDirectory(w, pathOf(op.dir))
      else if ('file' in op) {
        const b = bytesOf(op.bytes)
        const queue = chunksOf(b, op)
        crcs.push(await m.zipAddFile(w, pathOf(op.file), op.size ?? b.length, { next: async () => queue.shift() ?? null }))
      } else if ('many' in op) {
        for (let n = 0; n < op.many.count; n++) await m.zipAddFile(w, `${op.many.prefix}${n}`, 0, { next: async () => null })
      } else await m.zipFinish(w)
    } catch (e: any) {
      if (e?.veyq !== 'zip') throw e
      return { error: { at: i, code: e.code } }
    }
  }
  if (!c.ops.some((o: any) => 'finish' in o)) await m.zipFinish(w)
  const bytes = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) { bytes.set(p, at); at += p.length }
  const length = m.zipLength(c.ops.flatMap((op: any) => 'dir' in op ? [{ path: pathOf(op.dir), directory: true }]
    : 'file' in op ? [{ path: pathOf(op.file), size: bytesOf(op.bytes).length }]
      : 'many' in op ? Array.from({ length: op.many.count }, (_, n) => ({ path: `${op.many.prefix}${n}`, size: 0 })) : []), c.zip64At ?? 0xffffffff)
  expect(length, 'the planned length is the written one').toBe(bytes.length)
  return { bytes, expected: { length: bytes.length, sha256: sha(bytes), ...(bytes.length <= 4096 ? { hex: hex(bytes) } : {}), writes, crc32: crcs } }
}

const archives = new Map<string, Uint8Array>()
async function archiveOf(s: any): Promise<Uint8Array> {
  let b: Uint8Array
  if (s.write !== undefined) {
    if (!archives.has(s.write)) archives.set(s.write, (await written(v.write.find((c: any) => c.rule === s.write))).bytes!)
    b = archives.get(s.write)!.slice()
  } else b = bytesOf(s.bytes)
  for (const p of s.patch ?? []) b.set(bytesOf([p.hex]), p.at)
  if (s.cut !== undefined) b = b.slice(0, s.cut)
  if (s.comment !== undefined) b = new Uint8Array([...b, ...bytesOf([s.comment])])
  return b
}

describe('@veyq/core-js/export answers contracts/platform/export/v1-vectors.json', () => {
  it('the CRC-32 the writer and reader run', async () => {
    for (const c of v.crc32) {
      // through a written file: the CRC the writer reports
      const w = m.zipWriter({ write: async () => {} }, 0, 0xffffffff)
      const queue = chunksOf(bytesOf(c.bytes), { chunks: c.chunks })
      expect(await m.zipAddFile(w, 'f', bytesOf(c.bytes).length, { next: async () => queue.shift() ?? null }), c.rule).toBe(c.expected)
    }
  })

  it('the DOS stamp, read from a written header', async () => {
    for (const c of v.dos) {
      const parts: Uint8Array[] = []
      const w = m.zipWriter({ write: async (b: Uint8Array) => void parts.push(b.slice()) }, c.ms, 0xffffffff)
      await m.zipAddDirectory(w, 'x')
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
      expect(m.zipLength(plan, c.zip64At ?? 0xffffffff), c.rule).toBe(c.expected)
    }
  })

  for (const c of v.read) {
    it(`read: ${c.rule}`, async () => {
      const b = await archiveOf(c.archive)
      let read = 0
      const file = { size: b.length, read: async (from: number, length: number) => {
        read += Math.min(b.length, from + length) - from
        return b.slice(from, Math.min(b.length, from + length))
      } }
      let zip
      try {
        zip = await m.zipOpen(file)
      } catch (e: any) {
        if (e?.veyq !== 'zip') throw e
        expect({ error: e.code }).toEqual(c.expected)
        return
      }
      expect(read, 'what opening read').toBeLessThanOrEqual(c.expected.bounds.open)
      const all = m.zipEntries(zip).map((e) => ({ ...e }))
      const got: any = all.length > 50 ? { count: all.length, head: all.slice(0, 3), tail: all.slice(-3) } : { entries: all }
      if (c.stream) {
        got.streams = {}
        for (const name of c.stream) {
          const chunks: number[] = []
          try {
            const r = m.zipReading(zip, name)
            for (let chunk = await m.zipNext(r); chunk != null; chunk = await m.zipNext(r)) chunks.push(chunk.length)
            got.streams[name] = { chunks }
          } catch (e: any) {
            if (e?.veyq !== 'zip') throw e
            got.streams[name] = { chunks, error: e.code }
          }
        }
      }
      if (c.small) {
        got.small = []
        for (const s of c.small) {
          try {
            got.small.push({ ...s, sha256: sha(await m.zipBytes(zip, s.name, s.max)) })
          } catch (e: any) {
            if (e?.veyq !== 'zip') throw e
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
  it('the manifest\'s check', async () => {
    for (const c of v.manifest) {
      const json = c.json ?? built(c.build)
      let got: unknown
      try {
        m.manifestCheck(json)
        got = { ok: true }
      } catch (e: any) {
        if (e?.veyq !== 'export') throw e
        got = { code: e.code, ...(e.detail !== undefined ? { detail: e.detail } : {}) }
      }
      expect(got, c.rule).toEqual(c.expected)
    }
  })

  it('the payload walk hands back the value\'s own objects', () => {
    for (const c of v.payloads) {
      const found = m.manifestPayloads(c.value) as any[]
      expect(found.map((p) => p.path), c.rule).toEqual(c.expected)
      // identity: each found object is one inside the value
      const inside = (x: any): boolean => found.every((f) => JSON.stringify(x).includes(JSON.stringify(f)))
      expect(inside(c.value)).toBe(true)
    }
    const ref = { path: 'p', size: 1 }
    expect(m.manifestPayloads({ a: [ref] } as any)[0]).toBe(ref)
  })

  it('the facts', () => {
    expect({ ...m.manifestFacts(), limits: { ...m.manifestFacts().limits }, readChunk: m.zipReadChunk() }).toEqual(v.facts)
  })

  it('a refusal of the web\'s sink or read crosses as it was thrown', async () => {
    const boom = new Error('the disk is full')
    const w = m.zipWriter({ write: async () => { throw boom } }, 0, 0xffffffff)
    await expect(m.zipAddDirectory(w, 'x')).rejects.toBe(boom)
    const gone = new DOMException('gone', 'NotReadableError')
    await expect(m.zipOpen({ size: 100, read: async () => { throw gone } })).rejects.toBe(gone)
  })
})
