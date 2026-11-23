// @vitest-environment node
// Phase 1 of cr-web-export-archive-on-the-core: records contracts/platform/export/v1-vectors.json from the web's own
// TypeScript (`crc32.ts`, `zip.ts`, `manifest.ts`) — every case's input and the web's answer. Deleted in phase 2, when the
// core's ArchiveVectorsTest answers every case and writes the file. Runs only with VEYQ_WRITE_VECTORS=1.
import { describe, it } from 'vitest'
import { writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { crc32, crc32Finish, crc32Start, crc32Update } from './crc32'
import { openZip, ZipError, ZipWriter, zipLength, type ZipPlanEntry } from './zip'
import { collectPayloadsOf, ExportError, LIMITS, MANIFEST_FORMAT, MANIFEST_MAX_BYTES, MANIFEST_PATH, MANIFEST_PATH_V1, MANIFEST_VERSION, MAX_DEPTH, MAX_NODES, parseManifest } from './manifest'
import { assemble, type Part } from '../../test/mediaBytes'
import type { ByteSink } from '../blob/stream'

const FILE = resolve(__dirname, '../../../../../contracts/platform/export/v1-vectors.json')
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const text = (s: string) => new TextEncoder().encode(s)
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex')
/** A Blob that sums what its slices take. */
class CountingBlob extends Blob {
  read = 0
  slice(start = 0, end = this.size, type?: string): Blob {
    this.read += Math.min(end, this.size) - start
    return super.slice(start, end, type)
  }
}

type PathSpec = string | { repeat: string; times: number }
const pathOf = (p: PathSpec) => (typeof p === 'string' ? p : p.repeat.repeat(p.times))
type Op =
  | { dir: PathSpec }
  | { file: PathSpec; bytes: Part[]; size?: number; chunks?: number[]; every?: number }
  | { many: { count: number; prefix: string } }
  | { finish: true }

function* chunked(b: Uint8Array, op: { chunks?: number[]; every?: number }) {
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

/** Runs [ops] through the web's writer; the archive, the sink's write count and each file's CRC-32, or the first refusal. */
async function written(modified: number, zip64At: number | undefined, ops: Op[]) {
  const parts: Uint8Array[] = []
  let writes = 0
  const sink: ByteSink = { write: async (b) => { parts.push(b.slice()); writes++ }, close: async () => {}, abort: async () => {} }
  const w = new ZipWriter(sink, { modified: new Date(modified), zip64At })
  const crcs: number[] = []
  const explicit = ops.some((o) => 'finish' in o)
  for (const [i, op] of ops.entries()) {
    try {
      if ('dir' in op) await w.addDirectory(pathOf(op.dir))
      else if ('file' in op) {
        const b = assemble(op.bytes)
        crcs.push((await w.addFile(pathOf(op.file), op.size ?? b.length, chunked(b, op))).crc32)
      } else if ('many' in op) for (let n = 0; n < op.many.count; n++) await w.addFile(`${op.many.prefix}${n}`, 0, [])
      else await w.finish()
    } catch (e) {
      if (!(e instanceof ZipError)) throw e
      return { error: { at: i, code: e.code } }
    }
  }
  if (!explicit) await w.finish()
  const bytes = new Uint8Array(await new Blob(parts as BlobPart[]).arrayBuffer())
  return { bytes, writes, crcs }
}

const planOf = (ops: Op[]): ZipPlanEntry[] => ops.flatMap((op): ZipPlanEntry[] =>
  'dir' in op ? [{ path: pathOf(op.dir), directory: true }]
    : 'file' in op ? [{ path: pathOf(op.file), size: assemble(op.bytes).length }]
      : 'many' in op ? Array.from({ length: op.many.count }, (_, n) => ({ path: `${op.many.prefix}${n}`, size: 0 })) : [])

const T0 = Date.UTC(2026, 9, 13, 9, 30, 0)
const seq31 = Array.from({ length: 256 }, (_, i) => ((i * 31) & 0xff).toString(16).padStart(2, '0')).join('')
const BIG: Part[] = [{ repeat: seq31, times: 8192 }, seq31.slice(0, 34)] // READ_CHUNK * 2 + 17 bytes, (i * 31) & 0xff
const MANIFEST_TEXT = hex(text('{"format":"veyq-subtree"}'))
const roundTrip: Op[] = [
  { file: 'manifest.json', bytes: [MANIFEST_TEXT] },
  { file: 'data/n2/größe ü.bin', bytes: BIG, every: 99_999 },
  { file: 'data/n3/empty', bytes: [] },
]
const small: Op[] = [{ file: 'a', bytes: [hex(text('hello'))] }, { file: 'b', bytes: [hex(text('world!'))] }, { dir: 'd' }]
const shapes: [string, Op[]][] = [
  ['no entry at all', []],
  ['an empty folder', [{ dir: 'Leer' }, { file: '.veyq/manifest.json', bytes: [{ fill: 0x7b, count: 412 }] }]],
  ['empty files', [{ dir: 'a' }, { file: 'a/0', bytes: [] }, { file: 'a/1', bytes: [] }]],
  ['many small files in nested folders', [
    { dir: 'Trip' }, { dir: 'Trip/Sub' }, { dir: 'Trip/Sub/Deeper' },
    ...Array.from({ length: 40 }, (_, i): Op => ({ file: `Trip/${['', 'Sub/', 'Sub/Deeper/'][i % 3]}f${i}.txt`, bytes: [{ fill: i, count: (i * 37) % 211 }] })),
  ]],
  ['non-ASCII names, stored as UTF-8 bytes with the UTF-8 flag', [
    { dir: 'Grüße' }, { dir: 'Grüße/日本' }, { file: 'Grüße/日本/写真 😀.jpg', bytes: [{ fill: 0xd8, count: 1234 }] },
    { file: 'Grüße/naïve café.txt', bytes: ['6361666521'] }, { file: 'Grüße/é.bin', bytes: ['000102'] },
    { file: 'Grüße/nfd.txt', bytes: ['4e4644'] },
  ]],
]

type Section = Record<string, unknown>[]
const v: Record<string, unknown> = {
  format: 'veyq-export-archive-v1',
  about: 'A download\'s archive (ADR 0155 decision 7.2, the sixth slice; ADR 0082 decisions 3 and 10): the store-only ZIP the writer ' +
    'writes byte for byte, its exact length before the first byte, the reader\'s refusals of what it opens, the CRC-32 both ' +
    'run, and the manifest\'s check. A case\'s bytes are parts: hex, {fill, count} bytes of one value, or {repeat, times} a hex ' +
    'run repeated. A path is a string or {repeat, times} a text repeated. `write`: each op in order (`dir`, `file` - its bytes ' +
    'delivered in `chunks` of the listed lengths or `every` n, `size` the declared size where it differs -, `many` count empty ' +
    'files, `finish`), finished after the last unless one is listed; expected the archive (its length, SHA-256, hex up to ' +
    '4 KiB), the number of writes the sink took, each file\'s CRC-32 - or the first refusal (`at` the op). `length`: a ' +
    'planned archive\'s exact length; every `write` case\'s plan is one too. `read`: an archive (a `write` case\'s output, ' +
    '`patch`ed at offsets, `cut` to a length, a `comment` appended) and what the reader answers: its entries, each `stream`\'s ' +
    'chunk lengths or refusal, each `small` read whole under a limit, at most `bounds.open` bytes read to open it - or the ' +
    'refusal; a directory past 50 entries as its `count`, `head` and `tail` (three each). `manifest`: a parsed manifest (`json`, or `build` a tree `deep` or `wide`) and whether it is taken or the ' +
    'refusal\'s code and detail. `payloads`: the payload references a value holds, in walk order.',
  facts: {
    format: MANIFEST_FORMAT, version: MANIFEST_VERSION, path: MANIFEST_PATH, pathV1: MANIFEST_PATH_V1,
    maxBytes: MANIFEST_MAX_BYTES, maxDepth: MAX_DEPTH, maxNodes: MAX_NODES, limits: LIMITS, readChunk: 1024 * 1024,
  },
}

describe.runIf(process.env.VEYQ_WRITE_VECTORS === '1')('records the export archive\'s vectors from the web', () => {
  it('writes contracts/platform/export/v1-vectors.json', async () => {
    // --- crc32 ---------------------------------------------------------------------------------------------------------
    const crcCases: [string, Part[], number[]?][] = [
      ['nothing', []],
      ['the standard check value: "123456789"', [hex(text('123456789'))]],
      ['one zero byte', ['00']],
      ['one 0xff byte', ['ff']],
      ['every byte value once', [Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0')).join('')]],
      ['a megabyte and a bit of a sequence, in uneven chunks', BIG, [1, 2, 3, 65_536, 1_000_000, 0, 1_031_627]],
      ['32 KiB of zeros', [{ fill: 0, count: 32_768 }]],
    ]
    v.crc32 = crcCases.map(([rule, bytes, chunks]) => {
      const b = assemble(bytes)
      let s = crc32Start()
      for (const c of chunked(b, { chunks })) s = crc32Update(s, c)
      if (crc32Finish(s) !== crc32(b)) throw new Error('chunked crc differs')
      return { rule, bytes, ...(chunks ? { chunks } : {}), expected: crc32(b) }
    })

    // --- dos time stamps (read from a written directory entry's header) ------------------------------------------------
    const dosCases: [string, number][] = [
      ['the round trip\'s stamp', T0],
      ['an odd second is rounded down to the 2-second grid', Date.UTC(2026, 10, 9, 9, 30, 59, 999)],
      ['the epoch: the year held to 1980, the month and day kept', 0],
      ['before the epoch', Date.UTC(1969, 11, 31, 23, 59, 58)],
      ['the first moment the format holds', Date.UTC(1980, 0, 1)],
      ['a leap day', Date.UTC(2028, 1, 29, 23, 59, 58)],
      ['the last year the format holds', Date.UTC(2107, 11, 31, 23, 59, 59)],
      ['past it: the year held to 2107', Date.UTC(2200, 5, 15, 12, 0, 0)],
      ['a year 10000 date', Date.UTC(10000, 0, 1)],
    ]
    v.dos = []
    for (const [rule, ms] of dosCases) {
      const r = await written(ms, undefined, [{ dir: 'x' }])
      if (!('bytes' in r)) throw new Error('dos')
      const dv = new DataView(r.bytes!.buffer)
      ;(v.dos as Section).push({ rule, ms, expected: { date: dv.getUint16(12, true), time: dv.getUint16(10, true) } })
    }

    // --- write ---------------------------------------------------------------------------------------------------------
    const writeCases: { rule: string; modified?: number; zip64At?: number; ops: Op[] }[] = [
      { rule: 'the round trip: a manifest, a 2 MiB file in 99,999-byte chunks, an empty file', ops: roundTrip },
      { rule: 'the round trip in ZIP64 (the limit lowered to 8 bytes)', zip64At: 8, ops: roundTrip },
      { rule: 'two small files and a directory', ops: small },
      ...shapes.map(([rule, ops]) => ({ rule, ops })),
      ...[1, 600, 5000].map((zip64At) => ({ rule: `many small files, the ZIP64 limit lowered to ${zip64At} bytes`, zip64At, ops: shapes[3][1] })),
      { rule: 'a file in one chunk', ops: [{ file: 'f.bin', bytes: BIG }] },
      { rule: 'the same file one READ_CHUNK and the rest: the chunking does not change a byte', ops: [{ file: 'f.bin', bytes: BIG, chunks: [1_048_576, 1_048_593] }] },
      { rule: 'the same file in chunks of 4,096', ops: [{ file: 'f.bin', bytes: BIG, every: 4_096 }] },
      { rule: 'empty chunks are skipped', ops: [{ file: 'e.txt', bytes: ['6869212121'], chunks: [0, 3, 0, 0, 2, 0] }] },
      { rule: 'a file and a directory of one name are two entries', ops: [{ dir: 'x' }, { file: 'x', bytes: ['01'] }] },
      { rule: 'a name\'s lone surrogate is written as U+FFFD', ops: [{ file: 'a\ud800b', bytes: ['01'] }] },
      { rule: 'a name of 65,535 UTF-8 bytes', ops: [{ file: { repeat: 'é', times: 32_767 }, bytes: ['01'] }, { file: 'z', bytes: [] }] },
      { rule: 'the stamp past 2107', modified: Date.UTC(2200, 5, 15, 12, 0, 0), ops: [{ file: 'a', bytes: ['00'] }] },
      { rule: '65,535 entries: the ZIP64 end records by count alone', ops: [{ many: { count: 65_535, prefix: 'e' } }] },
      { rule: '65,534 entries: still the classic end record', ops: [{ many: { count: 65_534, prefix: 'e' } }] },
      // refusals
      { rule: 'a source that delivers fewer bytes than declared', ops: [{ file: 'a', bytes: ['616263'], size: 5 }] },
      { rule: 'a source that delivers more bytes than declared', ops: [{ file: 'b', bytes: ['616263'], size: 2 }] },
      { rule: 'a negative size', ops: [{ file: 'a', bytes: [], size: -1 }] },
      { rule: 'a size that is not whole', ops: [{ file: 'a', bytes: [], size: 2.5 }] },
      { rule: 'a size past 2^53', ops: [{ file: 'a', bytes: [], size: 2 ** 53 }] },
      { rule: 'a path written twice', ops: [{ file: 'a/b', bytes: ['78'] }, { file: 'a/b', bytes: ['79'] }] },
      { rule: 'a directory written twice', ops: [{ dir: 'x' }, { dir: 'x' }] },
      ...['', '/abs', '../up', 'a/../b', 'a/./b', 'a\\b', 'a//b', 'a/', '.'].map((p) => ({ rule: `an unsafe path ${JSON.stringify(p)}`, ops: [{ file: p, bytes: ['78'] }] as Op[] })),
      { rule: 'an unsafe directory path', ops: [{ dir: '../x' }] },
      { rule: 'a name past 65,535 UTF-8 bytes', ops: [{ file: { repeat: 'é', times: 32_768 }, bytes: ['01'] }] },
      { rule: 'an entry after the end', ops: [{ file: 'a', bytes: ['01'] }, { finish: true }, { file: 'b', bytes: ['01'] }] },
      { rule: 'the end written twice', ops: [{ finish: true }, { finish: true }] },
    ]
    const archives = new Map<string, Uint8Array>()
    v.write = []
    v.length = []
    for (const c of writeCases) {
      const modified = c.modified ?? T0
      const r = await written(modified, c.zip64At, c.ops)
      const expected = 'error' in r ? { error: r.error } : (() => {
        const length = zipLength(planOf(c.ops), { zip64At: c.zip64At })
        if (length !== r.bytes!.length) throw new Error(`zipLength differs for ${c.rule}`)
        archives.set(c.rule, r.bytes!)
        return { length, sha256: sha(r.bytes!), ...(r.bytes!.length <= 4096 ? { hex: hex(r.bytes!) } : {}), writes: r.writes, crc32: r.crcs }
      })()
      ;(v.write as Section).push({ rule: c.rule, modified, ...(c.zip64At !== undefined ? { zip64At: c.zip64At } : {}), ops: c.ops, expected })
    }

    // --- length at the format's own limits (no bytes written) ------------------------------------------------------------
    const G = 4 * 1024 ** 3
    const lengthCases: { rule: string; zip64At?: number; plan: (ZipPlanEntry | { many: { count: number; prefix: string } })[] }[] = [
      { rule: 'a file one byte under 4 GiB: no ZIP64', plan: [{ path: 'a', size: G - 2 }] },
      { rule: 'a file of 0xFFFFFFFF bytes: ZIP64 (the field\'s own value means "see the extra")', plan: [{ path: 'a', size: G - 1 }] },
      { rule: 'a 5 GiB file', plan: [{ path: 'v/clip.mp4', size: 5 * 1024 ** 3 }] },
      { rule: 'files whose offsets cross 4 GiB: the later ones carry a ZIP64 offset', plan: [{ path: 'a', size: G - 100 }, { path: 'b', size: 50 }, { path: 'c', size: 200 }, { path: 'd', size: 1 }] },
      { rule: 'a directory after 4 GiB', plan: [{ path: 'a', size: G }, { path: 'later', directory: true }] },
      { rule: 'a lowered limit of 1 byte', zip64At: 1, plan: [{ path: 'a', directory: true }, { path: 'a/b', size: 0 }, { path: 'a/c', size: 1 }] },
      { rule: 'non-ASCII names count their UTF-8 bytes', plan: [{ path: 'Grüße/写真 😀.jpg', size: 10 }] },
      { rule: '65,535 entries', plan: [{ many: { count: 65_535, prefix: 'e' } }] },
    ]
    for (const c of lengthCases) {
      const plan = c.plan.flatMap((p): ZipPlanEntry[] => 'many' in p ? Array.from({ length: p.many.count }, (_, n) => ({ path: `${p.many.prefix}${n}`, size: 0 })) : [p])
      ;(v.length as Section).push({ rule: c.rule, ...(c.zip64At !== undefined ? { zip64At: c.zip64At } : {}), plan: c.plan, expected: zipLength(plan, { zip64At: c.zip64At }) })
    }

    // --- read ----------------------------------------------------------------------------------------------------------
    type Source = { write?: string; bytes?: Part[]; patch?: { at: number; hex: string }[]; cut?: number; comment?: string }
    const build = (s: Source): Uint8Array => {
      let b: Uint8Array = s.write !== undefined ? archives.get(s.write)!.slice() : assemble(s.bytes!)
      for (const p of s.patch ?? []) {
        if (!Number.isSafeInteger(p.at) || p.at < 0 || p.at >= b.length) throw new Error(`a patch outside the archive: ${p.at}`)
        b.set(assemble([p.hex]), p.at)
      }
      if (s.cut !== undefined) b = b.slice(0, s.cut)
      if (s.comment !== undefined) b = new Uint8Array([...b, ...assemble([s.comment])])
      return b
    }
    const u16 = (n: number) => hex(new Uint8Array([n & 0xff, (n >> 8) & 0xff]))
    const u32 = (n: number) => hex(new Uint8Array([n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff]))
    /** Where each record of an archive starts: the local headers, the directory's records, the end record. */
    const layout = (b: Uint8Array) => {
      const dv = new DataView(b.buffer, b.byteOffset, b.byteLength)
      const eocd = b.length - 22
      let cd = dv.getUint32(eocd + 16, true)
      if (cd === 0xffffffff) cd = Number(dv.getBigUint64(Number(dv.getBigUint64(eocd - 20 + 8, true)) + 48, true))
      const centrals: number[] = []
      for (let p = cd; p < eocd && dv.getUint32(p, true) === 0x02014b50; p += 46 + dv.getUint16(p + 28, true) + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true)) centrals.push(p)
      const locals = centrals.map((p) => dv.getUint32(p + 42, true)) // the first entries' (below any lowered ZIP64 limit)
      return { eocd, cd, centrals, locals, dv }
    }
    const S = 'two small files and a directory'
    const L = layout(archives.get(S)!)
    const R = 'the round trip: a manifest, a 2 MiB file in 99,999-byte chunks, an empty file'
    const Z = 'the round trip in ZIP64 (the limit lowered to 8 bytes)'
    const LZ = layout(archives.get(Z)!)
    // the 2 MiB entry's ZIP64 extra in the directory: after its 46 bytes and its name
    const bigName = text('data/n2/größe ü.bin').length
    const bigExtra = LZ.centrals[1] + 46 + bigName
    const aData = L.locals[0] + 30 + 1 // `a`'s bytes
    // an archive whose `a` holds `b`'s local header, bytes and descriptor as written, then `b` itself
    const O = 'a file that holds another entry\'s records'
    const bRecords = archives.get(S)!.slice(L.locals[1], L.locals[1] + 31 + 6 + 16)
    {
      const ops: Op[] = [{ file: 'a', bytes: [hex(bRecords)] }, { file: 'b', bytes: [hex(text('world!'))] }]
      const r = await written(T0, undefined, ops)
      archives.set(O, r.bytes!)
      ;(v.write as Section).push({ rule: O, modified: T0, ops, expected: { length: r.bytes!.length, sha256: sha(r.bytes!), hex: hex(r.bytes!), writes: r.writes, crc32: r.crcs } })
    }
    const LO = layout(archives.get(O)!)
    const aDesc = aData + 5
    const readCases: { rule: string; archive: Source; stream?: string[]; small?: { name: string; max: number }[] }[] = [
      { rule: 'the round trip read back: every entry, the 2 MiB one in READ_CHUNKs', archive: { write: R }, stream: ['manifest.json', 'data/n2/größe ü.bin', 'data/n3/empty'], small: [{ name: 'manifest.json', max: 1000 }, { name: 'manifest.json', max: 24 }] },
      { rule: 'the ZIP64 round trip read back', archive: { write: Z }, stream: ['data/n2/größe ü.bin', 'data/n3/empty'], small: [{ name: 'manifest.json', max: 1000 }] },
      { rule: 'many small files in ZIP64 by offset', archive: { write: 'many small files, the ZIP64 limit lowered to 600 bytes' }, stream: ['Trip/f0.txt', 'Trip/Sub/Deeper/f38.txt'] },
      { rule: 'non-ASCII names read back as written', archive: { write: 'non-ASCII names, stored as UTF-8 bytes with the UTF-8 flag' }, stream: ['Grüße/日本/写真 😀.jpg'] },
      { rule: 'an archive with no entry', archive: { write: 'no entry at all' } },
      { rule: '65,535 entries through the ZIP64 end records', archive: { write: '65,535 entries: the ZIP64 end records by count alone' }, stream: ['e65534'] },
      { rule: 'a flipped payload byte is found at the end of the entry\'s read', archive: { write: R, patch: [{ at: 200, hex: (archives.get(R)![200] ^ 0xff).toString(16).padStart(2, '0') }] }, stream: ['manifest.json', 'data/n2/größe ü.bin'] },
      { rule: 'an entry the directory does not list', archive: { write: S }, stream: ['nope'], small: [{ name: 'nope', max: 10 }] },
      { rule: 'a small entry above its limit', archive: { write: S }, small: [{ name: 'a', max: 4 }, { name: 'a', max: 5 }] },
      { rule: 'a directory entry reads as no bytes', archive: { write: S }, stream: ['d/'] },
      { rule: 'a comment after the end record', archive: { write: S, patch: [{ at: L.eocd + 20, hex: u16(3) }], comment: '616263' } },
      { rule: 'a header that declares its sizes, without a descriptor', archive: { write: S, patch: [{ at: L.locals[0] + 6, hex: u16(0x0800) }, { at: L.locals[0] + 14, hex: u32(0x3610a686) + u32(5) + u32(5) }] }, stream: ['a'] },
      { rule: 'a descriptor without its signature', archive: { write: S, patch: [{ at: aDesc, hex: u32(0x3610a686) + u32(5) + u32(5) + '00000000' }] }, stream: ['a'] },
      // refusals
      { rule: 'too small to be an archive', archive: { bytes: [{ fill: 0, count: 21 }] } },
      { rule: 'text, not a ZIP', archive: { bytes: [hex(text('this is just some text, long enough to read'))] } },
      { rule: 'the end cut off', archive: { write: R, cut: archives.get(R)!.length - 30 } },
      { rule: 'a comment the end record does not count', archive: { write: S, comment: '616263' } },
      { rule: 'a multi-disk archive (the end record\'s disk)', archive: { write: S, patch: [{ at: L.eocd + 4, hex: u16(1) }] } },
      { rule: 'a multi-disk archive (an entry\'s disk)', archive: { write: S, patch: [{ at: L.centrals[0] + 34, hex: u16(1) }] } },
      { rule: 'a compressed entry (method 8 in the directory)', archive: { write: S, patch: [{ at: L.centrals[0] + 10, hex: u16(8) }] } },
      { rule: 'an encrypted entry', archive: { write: S, patch: [{ at: L.centrals[1] + 8, hex: u16(0x0809) }] } },
      { rule: 'an entry listed twice', archive: { write: S, patch: [{ at: L.centrals[1] + 46, hex: '61' }] } },
      { rule: 'a name that is not UTF-8', archive: { write: S, patch: [{ at: L.centrals[0] + 46, hex: 'ff' }, { at: L.locals[0] + 30, hex: 'ff' }] } },
      { rule: 'a ZIP64 count without its locator', archive: { write: S, patch: [{ at: L.eocd + 10, hex: u16(0xffff) }] } },
      { rule: 'a ZIP64 record outside the file', archive: { write: '65,535 entries: the ZIP64 end records by count alone', patch: [{ at: archives.get('65,535 entries: the ZIP64 end records by count alone')!.length - 22 - 20 + 8, hex: u32(0xfffffff0) + u32(0) }] } },
      { rule: 'a directory outside the file', archive: { write: S, patch: [{ at: L.eocd + 16, hex: u32(L.cd + 1) }] } },
      { rule: 'more entries than the directory holds', archive: { write: S, patch: [{ at: L.eocd + 8, hex: u16(9) + u16(9) }] } },
      { rule: 'a bad directory record', archive: { write: S, patch: [{ at: L.centrals[1], hex: '504b0103' }] } },
      { rule: 'an extra field that runs past its entry', archive: { write: Z, patch: [{ at: bigExtra + 2, hex: u16(200) }] } },
      { rule: 'a ZIP64 extra too short for the offset it must carry', archive: { write: Z, patch: [{ at: LZ.centrals[0] + 42, hex: u32(0xffffffff) }] } },
      { rule: 'a ZIP64 size past 2^53', archive: { write: Z, patch: [{ at: bigExtra + 4 + 4, hex: u32(0x00200000) }] } },
      { rule: 'a stored entry whose sizes differ', archive: { write: S, patch: [{ at: L.centrals[0] + 20, hex: u32(6) }] } },
      { rule: 'an entry that lies outside the file', archive: { write: S, patch: [{ at: L.centrals[0] + 24, hex: u32(0x7fffffff) }, { at: L.centrals[0] + 20, hex: u32(0x7fffffff) }] } },
      { rule: 'an entry whose header lies in the directory', archive: { write: S, patch: [{ at: L.centrals[0] + 42, hex: u32(L.cd) }] } },
      { rule: 'a bad local header', archive: { write: S, patch: [{ at: L.locals[1], hex: '504b0305' }] } },
      { rule: 'a local name that differs from the directory', archive: { write: S, patch: [{ at: L.locals[1] + 30, hex: '63' }] } },
      { rule: 'a local header of another method', archive: { write: S, patch: [{ at: L.locals[0] + 8, hex: u16(8) }] } },
      { rule: 'an entry that runs into the directory', archive: { write: S, patch: [{ at: L.centrals[1] + 20, hex: u32(200) + u32(200) }] } },
      { rule: 'a descriptor whose checksum differs', archive: { write: S, patch: [{ at: aDesc + 4, hex: u32(0x12345678) }] } },
      { rule: 'a descriptor whose size differs', archive: { write: S, patch: [{ at: aDesc + 8, hex: u32(4) }] } },
      { rule: 'a directory checksum the descriptor does not hold', archive: { write: S, patch: [{ at: L.centrals[0] + 16, hex: u32(1) }] } },
      { rule: 'a header without a descriptor whose sizes are not the directory\'s', archive: { write: S, patch: [{ at: L.locals[0] + 6, hex: u16(0x0800) }] } },
      { rule: 'two entries over the same bytes: one inside the other\'s', archive: { write: O, patch: [{ at: LO.centrals[1] + 42, hex: u32(LO.locals[0] + 31) }] } },
    ]
    v.read = []
    for (const c of readCases) {
      const bytes = build(c.archive)
      const counting = new CountingBlob([bytes as BlobPart])
      let expected: unknown
      try {
        const zip = await openZip(counting)
        const opened = counting.read
        const streams: Record<string, unknown> = {}
        for (const name of c.stream ?? []) {
          const chunks: number[] = []
          try {
            for await (const chunk of zip.stream(name)) chunks.push(chunk.length)
            streams[name] = { chunks }
          } catch (e) {
            if (!(e instanceof ZipError)) throw e
            streams[name] = { chunks, error: e.code }
          }
        }
        const smalls = []
        for (const s of c.small ?? []) {
          try {
            const got = await zip.bytes(s.name, s.max)
            smalls.push({ ...s, sha256: sha(got) })
          } catch (e) {
            if (!(e instanceof ZipError)) throw e
            smalls.push({ ...s, error: e.code })
          }
        }
        const all = zip.entries.map((e) => ({ name: e.name, size: e.size, crc32: e.crc32, offset: e.offset, dataAt: e.dataAt }))
        expected = {
          // a long directory as its count, its first and its last three entries
          ...(all.length > 50 ? { count: all.length, head: all.slice(0, 3), tail: all.slice(-3) } : { entries: all }),
          ...(c.stream ? { streams } : {}), ...(c.small ? { small: smalls } : {}), bounds: { open: opened },
        }
      } catch (e) {
        if (!(e instanceof ZipError)) throw e
        expected = { error: e.code }
      }
      ;(v.read as Section).push({ rule: c.rule, archive: c.archive, ...(c.stream ? { stream: c.stream } : {}), ...(c.small ? { small: c.small } : {}), expected })
    }

    // --- manifest --------------------------------------------------------------------------------------------------------
    const sha64 = 'ab'.repeat(32)
    const env = (root: unknown, extra: Record<string, unknown> = {}) => ({ format: 'veyq-subtree', version: 2, exportedAt: '2026-11-23T10:00:00.000Z', appVersion: '1', applications: [{ id: 'depot', contentSchema: 1 }, { id: 'memories', contentSchema: 1 }], root, ...extra })
    const env1 = (root: unknown) => ({ format: 'veyq-subtree', version: 1, exportedAt: '2026-01-01T00:00:00Z', appVersion: '0.9', options: { includeLocation: true }, root })
    const file = (id: string, name: string, path: string, payload: Record<string, unknown> = {}) => ({ id, kind: 'FILE', name, path, content: { type: 'depot.file', schema: 1, file: { path, size: 3, mediaType: 'text/plain', sha256: sha64, ...payload } } })
    const folder = (id: string, name: string, path: string, children: unknown[] = [], extra: Record<string, unknown> = {}) => ({ id, kind: 'FOLDER', name, path, children, ...extra })
    const memory = (id: string, path: string, media: unknown[]) => ({ id, kind: 'MEMORY', name: 'Trip', path, cover: null, content: { type: 'memories.memory', schema: 1, media } })
    const medium = (path: string, extra: Record<string, unknown> = {}) => ({ name: 'p', file: { path, size: 10, mediaType: 'image/jpeg', sha256: sha64, ...extra } })
    const ok = env(folder('n1', 'Home', 'Home', [file('n2', 'a.txt', 'Home/a.txt'), memory('n3', 'Home/Trip', [medium('Home/Trip/p.jpg')]), folder('n4', 'Empty', 'Home/Empty')]))
    const manifestCases: { rule: string; json?: unknown; build?: { deep?: number; wide?: number } }[] = [
      { rule: 'a version 2 tree: folders, a file, a memory', json: ok },
      { rule: 'unknown fields are ignored', json: { ...ok, future: { x: 1 }, root: { ...(ok.root as object), extra: true } } },
      { rule: 'a version 1 manifest with payloads under data/', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'data/n1/p.jpg', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'a cover is a payload of its node', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg')])) },
      { rule: 'a kind that is one file: its payload at its own path', json: env(file('n1', 'a.txt', 'a.txt')) },
      { rule: 'a modified time on a payload', json: env(file('n1', 'a.txt', 'a.txt', { modifiedAt: 1793318400000.5 })) },
      { rule: 'a cover of null', json: env({ ...memory('n1', 'Trip', []), cover: null }) },
      { rule: 'not an object', json: [1, 2] },
      { rule: 'another format', json: { ...ok, format: 'zip' } },
      { rule: 'no format', json: { version: 2 } },
      { rule: 'a newer version', json: { ...ok, version: 3 } },
      { rule: 'a version of 0', json: { ...ok, version: 0 } },
      { rule: 'a version that is not whole', json: { ...ok, version: 1.5 } },
      { rule: 'a version as text', json: { ...ok, version: '2' } },
      { rule: 'no exportedAt', json: { ...ok, exportedAt: undefined } },
      { rule: 'an empty exportedAt', json: { ...ok, exportedAt: '' } },
      { rule: 'an exportedAt over its length', json: { ...ok, exportedAt: 'x'.repeat(65) } },
      { rule: 'an appVersion that is a number', json: { ...ok, appVersion: 1 } },
      { rule: 'an empty appVersion is taken', json: { ...ok, appVersion: '' } },
      { rule: 'an appVersion over its length', json: { ...ok, appVersion: 'x'.repeat(65) } },
      { rule: 'version 2 without applications', json: { ...ok, applications: undefined } },
      { rule: 'more applications than the cap', json: { ...ok, applications: Array.from({ length: 65 }, (_, i) => ({ id: `a${i}`, contentSchema: 1 })) } },
      { rule: 'an application id that is not one', json: { ...ok, applications: [{ id: 'Depot', contentSchema: 1 }] } },
      { rule: 'an application id of 64 characters', json: { ...ok, applications: [{ id: 'a'.repeat(64), contentSchema: 1 }, { id: 'depot', contentSchema: 1 }, { id: 'memories', contentSchema: 1 }] } },
      { rule: 'an application id of 65 characters', json: { ...ok, applications: [{ id: 'a'.repeat(65), contentSchema: 1 }] } },
      { rule: 'an application listed twice', json: { ...ok, applications: [{ id: 'depot', contentSchema: 1 }, { id: 'depot', contentSchema: 2 }] } },
      { rule: 'a content schema of 0', json: { ...ok, applications: [{ id: 'depot', contentSchema: 0 }] } },
      { rule: 'an application of the content not listed', json: { ...ok, applications: [{ id: 'depot', contentSchema: 1 }] } },
      { rule: 'no root', json: { ...ok, root: undefined } },
      { rule: 'a node id with a dot', json: env(folder('n.1', 'Home', 'Home')) },
      { rule: 'a node id of 65 characters', json: env(folder('n'.repeat(65), 'Home', 'Home')) },
      { rule: 'a node id used twice', json: env(folder('n1', 'Home', 'Home', [folder('n1', 'A', 'Home/A')])) },
      { rule: 'an empty name', json: env(folder('n1', '', 'Home')) },
      { rule: 'a name over its length', json: env(folder('n1', 'x'.repeat(1025), 'Home')) },
      { rule: 'a name of 1,024 characters', json: env(folder('n1', 'x'.repeat(1024), 'Home')) },
      { rule: 'no kind', json: env({ id: 'n1', name: 'Home', path: 'Home' }) },
      { rule: 'a kind over its length', json: env({ id: 'n1', kind: 'K'.repeat(65), name: 'Home', path: 'Home' }) },
      { rule: 'version 2 without a node path', json: env({ id: 'n1', kind: 'FOLDER', name: 'Home' }) },
      { rule: 'the root path with a slash', json: env(folder('n1', 'Home', 'Home/Sub')) },
      { rule: 'a child not directly below its parent', json: env(folder('n1', 'Home', 'Home', [folder('n2', 'A', 'Home/X/A')])) },
      { rule: 'a child under another directory', json: env(folder('n1', 'Home', 'Home', [folder('n2', 'A', 'Other/A')])) },
      { rule: 'a node at the manifest\'s directory', json: env(folder('n1', '.veyq', '.veyq')) },
      { rule: 'a node path with an unsafe name', json: env(folder('n1', 'Home', 'Ho:me')) },
      { rule: 'a node path of ..', json: env(folder('n1', 'Home', '..')) },
      { rule: 'a payload outside its node', json: env(folder('n1', 'Home', 'Home', [file('n2', 'a.txt', 'Home/a.txt', { path: 'Home/b.txt' })])) },
      { rule: 'a payload two levels down', json: env(memory('n1', 'Trip', [medium('Trip/x/p.jpg')])) },
      { rule: 'a payload in the manifest\'s directory', json: env(memory('n1', 'Trip', [medium('.veyq/p.jpg')])) },
      { rule: 'a payload path empty', json: env(memory('n1', 'Trip', [{ name: 'p', file: { path: '', size: 1, mediaType: 'x' } }])) },
      { rule: 'a payload whose size is negative', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { size: -1 })])) },
      { rule: 'a payload whose size is not whole', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { size: 1.5 })])) },
      { rule: 'a payload whose size is text: no payload at all, so the content is taken', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { size: '1' })])) },
      { rule: 'a payload without a media type', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { mediaType: undefined })])) },
      { rule: 'a media type over its length', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { mediaType: 'x'.repeat(256) })])) },
      { rule: 'a SHA-256 in capitals', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { sha256: 'AB'.repeat(32) })])) },
      { rule: 'a SHA-256 one character short', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { sha256: 'a'.repeat(63) })])) },
      { rule: 'a modified time as text', json: env(memory('n1', 'Trip', [medium('Trip/p.jpg', { modifiedAt: '2026' })])) },
      { rule: 'a cover that is not an object', json: env({ ...memory('n1', 'Trip', []), cover: 'x' }) },
      { rule: 'a cover outside its node', json: env({ ...memory('n1', 'Trip', []), cover: { path: 'Else/c.jpg', size: 1, mediaType: 'image/jpeg' } }) },
      { rule: 'content that is not an object', json: env({ ...folder('n1', 'Home', 'Home'), content: [] }) },
      { rule: 'content of null', json: env({ ...folder('n1', 'Home', 'Home'), content: null }) },
      { rule: 'content without a type', json: env({ ...folder('n1', 'Home', 'Home'), content: {} }) },
      { rule: 'a content type without a dot', json: env({ ...folder('n1', 'Home', 'Home'), content: { type: 'depot' } }) },
      { rule: 'a content type in capitals', json: env({ ...folder('n1', 'Home', 'Home'), content: { type: 'Depot.file' } }) },
      { rule: 'a content type over its length', json: env({ ...folder('n1', 'Home', 'Home'), content: { type: 'depot.' + 'f'.repeat(59) } }) },
      { rule: 'a content schema of 0', json: env({ ...folder('n1', 'Home', 'Home'), content: { type: 'depot.file', schema: 0 } }) },
      { rule: 'children that are not a list', json: env(folder('n1', 'Home', 'Home', {} as unknown as unknown[])) },
      { rule: 'children of null', json: env({ ...folder('n1', 'Home', 'Home'), children: null }) },
      { rule: 'a child that is not an object', json: env(folder('n1', 'Home', 'Home', [7])) },
      { rule: 'version 1: a payload under data/', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'data/abc_-1/p q.jpg', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'version 1: a payload not under data/', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'Trip/p.jpg', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'version 1: a payload named ..', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'data/n1/..', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'version 1: a payload with a control character', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'data/n1/a\u0001b', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'version 1: a payload with a backslash', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'data/n1/a\\b', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'version 1: a payload with a line break at its end', json: env1({ id: 'n1', kind: 'MEMORY', name: 'Trip', content: { type: 'memories.memory', media: [{ file: { path: 'data/n1/p.jpg\n', size: 1, mediaType: 'image/jpeg' } }] } }) },
      { rule: 'version 1 ignores node paths', json: env1({ id: 'n1', kind: 'FOLDER', name: 'Home', path: '../x' }) },
      { rule: 'a tree 100 levels deep is taken', build: { deep: 100 } },
      { rule: 'a tree 101 levels deep', build: { deep: 101 } },
      { rule: '200,000 nodes are taken', build: { wide: 200_000 } },
      { rule: '200,001 nodes', build: { wide: 200_001 } },
    ]
    const built = (b: { deep?: number; wide?: number }) => {
      if (b.deep !== undefined) {
        let node: Record<string, unknown> = folder(`n${b.deep}`, 'd', Array.from({ length: b.deep + 1 }, () => 'd').join('/'))
        for (let i = b.deep - 1; i >= 0; i--) node = folder(`n${i}`, 'd', Array.from({ length: i + 1 }, () => 'd').join('/'), [node])
        return env(node)
      }
      return env(folder('root', 'Home', 'Home', Array.from({ length: b.wide! - 1 }, (_, i) => ({ id: `c${i}`, kind: 'FOLDER', name: 'c', path: `Home/c${i}` }))))
    }
    v.manifest = manifestCases.map((c) => {
      // what JSON.parse would hand over: an undefined field is no field
      const json = c.json !== undefined ? JSON.parse(JSON.stringify(c.json)) : undefined
      let expected: unknown
      try {
        parseManifest(json ?? built(c.build!))
        expected = { ok: true }
      } catch (e) {
        if (!(e instanceof ExportError)) throw e
        expected = { code: e.code, ...(e.detail !== undefined ? { detail: e.detail } : {}) }
      }
      return { rule: c.rule, ...(json !== undefined ? { json } : { build: c.build }), expected }
    })

    // --- payloads ---------------------------------------------------------------------------------------------------------
    const payloadCases: [string, unknown][] = [
      ['nothing in a string', 'x'],
      ['an object with a path and a size', { path: 'a', size: 1 }],
      ['a size as text is no reference', { path: 'a', size: '1' }],
      ['a reference is not walked into', { path: 'a', size: 1, inner: { path: 'b', size: 2 } }],
      ['every reference in walk order: object values, then arrays in order', { cover: { path: 'c', size: 0 }, media: [{ file: { path: 'm1', size: 1 } }, [{ path: 'm2', size: 2 }]], deep: { x: { y: { path: 'd', size: 3 } } } }],
      ['a path that is not text', { path: 1, size: 1, under: { path: 'u', size: 1 } }],
      ['null and numbers inside', [null, 1, true, { path: 'n', size: 0 }]],
    ]
    v.payloads = payloadCases.map(([rule, value]) => ({ rule, value, expected: collectPayloadsOf(value).map((p) => p.path) }))

    writeFileSync(FILE, JSON.stringify(v, null, 2) + '\n')
  }, 600_000)
})
