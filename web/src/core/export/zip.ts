// A store-only ZIP, written and read as a stream (ADR 0082 decision 3). No compression (media is already compressed, and it
// keeps this trivial), no dependency, and no memory wall: the writer holds one chunk and a directory line per entry, the
// reader finds the central directory from the end of the file and reads an entry with `Blob.slice`, a chunk at a time.
//
// ZIP64 is used exactly where a field would overflow (an entry or the archive past 4 GiB, an offset past 4 GiB, more than
// 65 535 entries) and nowhere else, so a small export opens in every tool and a large one in every ZIP64-aware tool.
// An entry's CRC-32 is not known when its header is written (the bytes are still being produced), so the writer sets the
// data-descriptor flag and follows the data with the sizes and CRC; the size, however, IS declared up front (a blob's size
// is known) - that is how the writer knows whether the entry needs ZIP64 before its first byte, and a source that delivers
// a different number of bytes aborts the archive rather than writing a lie.
import type { ByteSink } from '../blob/stream'
import { crc32Finish, crc32Start, crc32Update } from './crc32'

export type ZipErrorCode = 'not_zip' | 'truncated' | 'corrupt' | 'unsupported' | 'crc_mismatch' | 'size_mismatch' | 'duplicate' | 'missing'

/** Anything wrong with an archive being read or written. `code` is stable for callers; the message is for developers. */
export class ZipError extends Error {
  constructor(readonly code: ZipErrorCode, message: string) {
    super(message)
    this.name = 'ZipError'
  }
}

const U32_MAX = 0xffffffff
const U16_MAX = 0xffff
const SIG_LOCAL = 0x04034b50
const SIG_DESCRIPTOR = 0x08074b50
const SIG_CENTRAL = 0x02014b50
const SIG_EOCD = 0x06054b50
const SIG_EOCD64 = 0x06064b50
const SIG_LOCATOR64 = 0x07064b50
const FLAG_DESCRIPTOR = 0x0008
const FLAG_UTF8 = 0x0800
const FLAG_ENCRYPTED = 0x0001
const EXTRA_ZIP64 = 0x0001
const VERSION_PLAIN = 20
const VERSION_ZIP64 = 45
const encoder = new TextEncoder()

// ---------------------------------------------------------------------------------------------------------- writing

type DirectoryEntry = { name: Uint8Array; crc: number; size: number; offset: number; zip64: boolean; date: number; time: number; dir: boolean }

export type ZipWriterOptions = {
  /** The modification time stamped on every entry (DOS format, UTC, 2-second resolution). Default: now. */
  modified?: Date
  /** The size from which an entry (or offset) is written as ZIP64. The format's own limit; lowered only by tests, which
   *  cannot afford a 4 GiB payload to reach the ZIP64 branches. */
  zip64At?: number
}

function dosStamp(d: Date): { date: number; time: number } {
  const year = Math.min(Math.max(d.getUTCFullYear(), 1980), 2107)
  return {
    date: ((year - 1980) << 9) | ((d.getUTCMonth() + 1) << 5) | d.getUTCDate(),
    time: (d.getUTCHours() << 11) | (d.getUTCMinutes() << 5) | (d.getUTCSeconds() >> 1),
  }
}

/** Writes a ZIP into a {@link ByteSink}. Entries one after another; {@link finish} writes the directory and closes the
 *  sink, {@link abort} aborts it (a half archive is never finalised). */
export class ZipWriter {
  private offset = 0
  private readonly entries: DirectoryEntry[] = []
  private readonly names = new Set<string>()
  private readonly stamp: { date: number; time: number }
  private readonly limit: number
  private ended = false

  constructor(private readonly sink: ByteSink, options: ZipWriterOptions = {}) {
    this.stamp = dosStamp(options.modified ?? new Date())
    this.limit = options.zip64At ?? U32_MAX
  }

  private async put(bytes: Uint8Array): Promise<void> {
    await this.sink.write(bytes)
    this.offset += bytes.length
  }

  /**
   * Add one entry whose `size` bytes arrive from `chunks`. The path is stored as given (UTF-8); it must be a plain relative
   * path - this writer is the last line against an archive a reader would have to distrust.
   * @throws ZipError `size_mismatch` when the chunks add up to anything but `size`, `duplicate` for a repeated path.
   */
  async addFile(path: string, size: number, chunks: AsyncIterable<Uint8Array> | Iterable<Uint8Array>): Promise<{ crc32: number }> {
    if (!Number.isSafeInteger(size) || size < 0) throw new ZipError('corrupt', `bad size for ${path}`)
    const name = this.claim(path, path)
    const zip64 = size >= this.limit
    const entryOffset = this.offset

    // local header; with the descriptor flag the CRC and sizes are 0 here and follow the data (ZIP64: 0xFFFFFFFF + extra)
    const extra = zip64 ? new Uint8Array(20) : new Uint8Array(0)
    if (zip64) {
      const e = new DataView(extra.buffer)
      e.setUint16(0, EXTRA_ZIP64, true)
      e.setUint16(2, 16, true)
    }
    const head = new Uint8Array(30 + name.length + extra.length)
    const h = new DataView(head.buffer)
    h.setUint32(0, SIG_LOCAL, true)
    h.setUint16(4, zip64 ? VERSION_ZIP64 : VERSION_PLAIN, true)
    h.setUint16(6, FLAG_DESCRIPTOR | FLAG_UTF8, true)
    h.setUint16(8, 0, true) // store
    h.setUint16(10, this.stamp.time, true)
    h.setUint16(12, this.stamp.date, true)
    h.setUint32(14, 0, true)
    h.setUint32(18, zip64 ? U32_MAX : 0, true)
    h.setUint32(22, zip64 ? U32_MAX : 0, true)
    h.setUint16(26, name.length, true)
    h.setUint16(28, extra.length, true)
    head.set(name, 30)
    head.set(extra, 30 + name.length)
    await this.put(head)

    let crc = crc32Start()
    let written = 0
    for await (const chunk of chunks) {
      if (chunk.length === 0) continue
      written += chunk.length
      if (written > size) throw new ZipError('size_mismatch', `${path} delivered more than the declared ${size} bytes`)
      crc = crc32Update(crc, chunk)
      await this.put(chunk)
    }
    if (written !== size) throw new ZipError('size_mismatch', `${path} delivered ${written} bytes, declared ${size}`)
    const final = crc32Finish(crc)

    const desc = new Uint8Array(zip64 ? 24 : 16)
    const d = new DataView(desc.buffer)
    d.setUint32(0, SIG_DESCRIPTOR, true)
    d.setUint32(4, final, true)
    if (zip64) {
      d.setBigUint64(8, BigInt(size), true)
      d.setBigUint64(16, BigInt(size), true)
    } else {
      d.setUint32(8, size, true)
      d.setUint32(12, size, true)
    }
    await this.put(desc)
    this.entries.push({ name, crc: final, size, offset: entryOffset, zip64, ...this.stamp, dir: false })
    return { crc32: final }
  }

  /**
   * Add a directory entry (`path/`, no bytes), so a directory with nothing in it still unpacks. Written without a data
   * descriptor: its CRC and sizes (all 0) are known up front.
   */
  async addDirectory(path: string): Promise<void> {
    const name = this.claim(path, `${path}/`)
    const head = new Uint8Array(30 + name.length)
    const h = new DataView(head.buffer)
    h.setUint32(0, SIG_LOCAL, true)
    h.setUint16(4, VERSION_PLAIN, true)
    h.setUint16(6, FLAG_UTF8, true)
    h.setUint16(10, this.stamp.time, true)
    h.setUint16(12, this.stamp.date, true)
    h.setUint16(26, name.length, true)
    head.set(name, 30)
    const offset = this.offset
    await this.put(head)
    this.entries.push({ name, crc: 0, size: 0, offset, zip64: false, ...this.stamp, dir: true })
  }

  /** Check `path` is a plain relative path (this writer is the last line against an archive a reader would have to distrust)
   *  and not yet written; returns the entry name `stored` as UTF-8. */
  private claim(path: string, stored: string): Uint8Array {
    if (this.ended) throw new ZipError('corrupt', 'archive already finished')
    if (path === '' || path.startsWith('/') || path.includes('\\') || path.split('/').some((s) => s === '..' || s === '.' || s === '')) {
      throw new ZipError('corrupt', `unsafe path ${JSON.stringify(path)}`)
    }
    if (this.names.has(stored)) throw new ZipError('duplicate', `path written twice: ${stored}`)
    const name = encoder.encode(stored)
    if (name.length > U16_MAX) throw new ZipError('corrupt', 'path too long')
    this.names.add(stored)
    return name
  }

  /** Convenience for the small in-memory entry (the manifest). */
  addBytes(path: string, bytes: Uint8Array): Promise<{ crc32: number }> {
    return this.addFile(path, bytes.length, [bytes])
  }

  /** Write the central directory and close the sink. */
  async finish(): Promise<void> {
    if (this.ended) throw new ZipError('corrupt', 'archive already finished')
    this.ended = true
    const cdStart = this.offset
    for (const e of this.entries) {
      const bigOffset = e.offset >= this.limit
      const fields = (e.zip64 ? 2 : 0) + (bigOffset ? 1 : 0) // usize+csize travel together, the offset alone
      const extra = new Uint8Array(fields ? 4 + fields * 8 : 0)
      if (fields) {
        const x = new DataView(extra.buffer)
        x.setUint16(0, EXTRA_ZIP64, true)
        x.setUint16(2, fields * 8, true)
        let at = 4
        if (e.zip64) {
          x.setBigUint64(at, BigInt(e.size), true)
          x.setBigUint64(at + 8, BigInt(e.size), true)
          at += 16
        }
        if (bigOffset) x.setBigUint64(at, BigInt(e.offset), true)
      }
      const rec = new Uint8Array(46 + e.name.length + extra.length)
      const c = new DataView(rec.buffer)
      c.setUint32(0, SIG_CENTRAL, true)
      c.setUint16(4, fields ? VERSION_ZIP64 : VERSION_PLAIN, true)
      c.setUint16(6, fields ? VERSION_ZIP64 : VERSION_PLAIN, true)
      c.setUint16(8, e.dir ? FLAG_UTF8 : FLAG_DESCRIPTOR | FLAG_UTF8, true)
      c.setUint16(10, 0, true)
      c.setUint16(12, e.time, true)
      c.setUint16(14, e.date, true)
      c.setUint32(16, e.crc, true)
      c.setUint32(20, e.zip64 ? U32_MAX : e.size, true)
      c.setUint32(24, e.zip64 ? U32_MAX : e.size, true)
      c.setUint16(28, e.name.length, true)
      c.setUint16(30, extra.length, true)
      if (e.dir) c.setUint32(38, 0x10, true) // MS-DOS directory attribute
      c.setUint32(42, bigOffset ? U32_MAX : e.offset, true)
      rec.set(e.name, 46)
      rec.set(extra, 46 + e.name.length)
      await this.put(rec)
    }
    const cdSize = this.offset - cdStart
    const count = this.entries.length
    const big = count >= U16_MAX || cdStart >= this.limit || cdSize >= this.limit
    if (big) {
      const z = new Uint8Array(56 + 20)
      const v = new DataView(z.buffer)
      v.setUint32(0, SIG_EOCD64, true)
      v.setBigUint64(4, 44n, true)
      v.setUint16(12, VERSION_ZIP64, true)
      v.setUint16(14, VERSION_ZIP64, true)
      v.setBigUint64(24, BigInt(count), true)
      v.setBigUint64(32, BigInt(count), true)
      v.setBigUint64(40, BigInt(cdSize), true)
      v.setBigUint64(48, BigInt(cdStart), true)
      v.setUint32(56, SIG_LOCATOR64, true)
      v.setBigUint64(64, BigInt(this.offset), true) // where the zip64 record starts
      v.setUint32(72, 1, true)
      await this.put(z)
    }
    const end = new Uint8Array(22)
    const v = new DataView(end.buffer)
    v.setUint32(0, SIG_EOCD, true)
    v.setUint16(8, big ? U16_MAX : count, true)
    v.setUint16(10, big ? U16_MAX : count, true)
    v.setUint32(12, big ? U32_MAX : cdSize, true)
    v.setUint32(16, big ? U32_MAX : cdStart, true)
    await this.put(end)
    await this.sink.close()
  }

  /** Abort the sink: whatever was written is discarded, never closed as if whole. */
  async abort(reason?: unknown): Promise<void> {
    this.ended = true
    await this.sink.abort(reason).catch(() => {})
  }
}

// ---------------------------------------------------------------------------------------------------------- reading

export type ZipEntry = { name: string; size: number; crc32: number; offset: number }

/** How much of an entry one read takes - the memory bound of a streamed read. */
export const READ_CHUNK = 1024 * 1024
const MAX_TAIL = 22 + U16_MAX

const toNumber = (v: bigint, what: string): number => {
  if (v > BigInt(Number.MAX_SAFE_INTEGER)) throw new ZipError('unsupported', `${what} too large`)
  return Number(v)
}

async function readAt(file: Blob, start: number, end: number): Promise<Uint8Array> {
  if (start < 0 || end > file.size || start > end) throw new ZipError('truncated', 'read outside the file')
  return new Uint8Array(await file.slice(start, end).arrayBuffer())
}

/**
 * Open an archive without reading it: finds the end record, then reads the central directory (one line per entry) and
 * nothing else. Refuses what this format never produces - compressed or encrypted entries, a multi-disk archive, a name
 * given twice - instead of guessing.
 */
export async function openZip(file: Blob): Promise<ZipArchive> {
  if (file.size < 22) throw new ZipError('not_zip', 'too small to be an archive')
  const tailStart = Math.max(0, file.size - MAX_TAIL)
  const tail = await readAt(file, tailStart, file.size)
  const tv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength)
  let eocd = -1
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tv.getUint32(i, true) === SIG_EOCD && i + 22 + tv.getUint16(i + 20, true) === tail.length) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw new ZipError('not_zip', 'no end-of-central-directory record')
  if (tv.getUint16(eocd + 4, true) !== 0 || tv.getUint16(eocd + 6, true) !== 0) throw new ZipError('unsupported', 'multi-disk archive')
  let count = tv.getUint16(eocd + 10, true)
  let cdSize = tv.getUint32(eocd + 12, true)
  let cdOffset = tv.getUint32(eocd + 16, true)
  if (count === U16_MAX || cdSize === U32_MAX || cdOffset === U32_MAX) {
    const at = tailStart + eocd - 20
    const loc = await readAt(file, at, at + 20).catch(() => null)
    const lv = loc && new DataView(loc.buffer, loc.byteOffset, loc.byteLength)
    if (!lv || lv.getUint32(0, true) !== SIG_LOCATOR64) throw new ZipError('corrupt', 'zip64 locator missing')
    const recAt = toNumber(lv.getBigUint64(8, true), 'zip64 record offset')
    const rec = await readAt(file, recAt, recAt + 56)
    const rv = new DataView(rec.buffer, rec.byteOffset, rec.byteLength)
    if (rv.getUint32(0, true) !== SIG_EOCD64) throw new ZipError('corrupt', 'zip64 record missing')
    count = toNumber(rv.getBigUint64(32, true), 'entry count')
    cdSize = toNumber(rv.getBigUint64(40, true), 'directory size')
    cdOffset = toNumber(rv.getBigUint64(48, true), 'directory offset')
  }
  if (cdOffset + cdSize > file.size) throw new ZipError('truncated', 'directory outside the file')
  const cd = await readAt(file, cdOffset, cdOffset + cdSize)
  const cv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength)
  const decoder = new TextDecoder('utf-8', { fatal: true })
  const entries = new Map<string, ZipEntry>()
  let p = 0
  for (let n = 0; n < count; n++) {
    if (p + 46 > cd.length || cv.getUint32(p, true) !== SIG_CENTRAL) throw new ZipError('corrupt', 'bad directory entry')
    const flags = cv.getUint16(p + 8, true)
    const method = cv.getUint16(p + 10, true)
    const crc = cv.getUint32(p + 16, true)
    let csize = cv.getUint32(p + 20, true)
    let usize = cv.getUint32(p + 24, true)
    const nameLen = cv.getUint16(p + 28, true)
    const extraLen = cv.getUint16(p + 30, true)
    const commentLen = cv.getUint16(p + 32, true)
    const disk = cv.getUint16(p + 34, true)
    let offset = cv.getUint32(p + 42, true)
    const next = p + 46 + nameLen + extraLen + commentLen
    if (next > cd.length) throw new ZipError('corrupt', 'directory entry runs past the directory')
    if (flags & FLAG_ENCRYPTED || method !== 0) throw new ZipError('unsupported', 'only plain stored entries are read')
    if (disk !== 0 && disk !== U16_MAX) throw new ZipError('unsupported', 'multi-disk archive')
    let name: string
    try {
      name = decoder.decode(cd.subarray(p + 46, p + 46 + nameLen))
    } catch {
      throw new ZipError('corrupt', 'entry name is not UTF-8')
    }
    // the ZIP64 extra carries, in this order and only for a field that reads 0xFFFFFFFF: sizes, then the offset
    let x = p + 46 + nameLen
    const xEnd = x + extraLen
    while (x + 4 <= xEnd) {
      const id = cv.getUint16(x, true)
      const len = cv.getUint16(x + 2, true)
      if (x + 4 + len > xEnd) throw new ZipError('corrupt', 'extra field runs past its entry')
      if (id === EXTRA_ZIP64) {
        let f = x + 4
        const need = (cond: boolean) => {
          if (!cond) return null
          if (f + 8 > x + 4 + len) throw new ZipError('corrupt', 'zip64 extra too short')
          const v = cv.getBigUint64(f, true)
          f += 8
          return v
        }
        const u = need(usize === U32_MAX)
        const c = need(csize === U32_MAX)
        const o = need(offset === U32_MAX)
        if (u !== null) usize = toNumber(u, 'entry size')
        if (c !== null) csize = toNumber(c, 'entry size')
        if (o !== null) offset = toNumber(o, 'entry offset')
      }
      x += 4 + len
    }
    if (usize === U32_MAX || csize === U32_MAX || offset === U32_MAX) throw new ZipError('corrupt', 'zip64 value missing')
    if (csize !== usize) throw new ZipError('unsupported', 'only plain stored entries are read')
    if (entries.has(name)) throw new ZipError('duplicate', `entry listed twice: ${name}`)
    entries.set(name, { name, size: usize, crc32: crc, offset })
    p = next
  }
  return new ZipArchive(file, entries, cdOffset)
}

export class ZipArchive {
  constructor(private readonly file: Blob, private readonly byName: Map<string, ZipEntry>, private readonly directoryAt: number) {}

  get entries(): ZipEntry[] {
    return [...this.byName.values()]
  }

  has(name: string): boolean {
    return this.byName.has(name)
  }

  get(name: string): ZipEntry | undefined {
    return this.byName.get(name)
  }

  /**
   * An entry's bytes, in chunks of at most {@link READ_CHUNK}; at the end the CRC-32 and the length are checked against
   * the directory. The check runs after the last chunk was handed out, so a consumer must treat the thrown `crc_mismatch`
   * as "everything you took from this entry is bad" - an import rolls back, it does not keep what it already wrote.
   */
  async *stream(name: string): AsyncGenerator<Uint8Array> {
    const e = this.byName.get(name)
    if (!e) throw new ZipError('missing', `no entry ${name}`)
    const { start, end } = await this.span(name)
    let crc = crc32Start()
    for (let at = start; at < end; at += READ_CHUNK) {
      const chunk = await readAt(this.file, at, Math.min(at + READ_CHUNK, end))
      crc = crc32Update(crc, chunk)
      yield chunk
    }
    if (crc32Finish(crc) !== e.crc32) throw new ZipError('crc_mismatch', `${name} does not match its checksum`)
  }

  /**
   * An entry as a lazy, seekable `Blob` over the archive (no bytes are read). Its checksum is NOT checked - the caller that
   * needs it reads {@link stream} once first (an import verifies each payload's SHA-256 that way before it slices it).
   */
  async blob(name: string, type = ''): Promise<Blob> {
    const { start, end } = await this.span(name)
    return this.file.slice(start, end, type)
  }

  private async span(name: string): Promise<{ start: number; end: number }> {
    const e = this.byName.get(name)
    if (!e) throw new ZipError('missing', `no entry ${name}`)
    const local = await readAt(this.file, e.offset, e.offset + 30)
    const lv = new DataView(local.buffer, local.byteOffset, local.byteLength)
    if (lv.getUint32(0, true) !== SIG_LOCAL) throw new ZipError('corrupt', `bad local header for ${name}`)
    const start = e.offset + 30 + lv.getUint16(26, true) + lv.getUint16(28, true)
    const end = start + e.size
    if (end > this.directoryAt) throw new ZipError('corrupt', `${name} runs into the directory`)
    return { start, end }
  }

  /** A small entry (the manifest) read whole, refused above `maxBytes`. */
  async bytes(name: string, maxBytes: number): Promise<Uint8Array> {
    const e = this.byName.get(name)
    if (!e) throw new ZipError('missing', `no entry ${name}`)
    if (e.size > maxBytes) throw new ZipError('unsupported', `${name} is larger than ${maxBytes} bytes`)
    const out = new Uint8Array(e.size)
    let at = 0
    for await (const chunk of this.stream(name)) {
      out.set(chunk, at)
      at += chunk.length
    }
    return out
  }
}
