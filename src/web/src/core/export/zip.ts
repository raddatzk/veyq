// A download's store-only ZIP, written and read as a stream (ADR 0082 decision 3) — the platform's Kotlin `export/ZipWriter`
// and `export/ZipReader`, drawn through `@veyq/core-js/export` (ADR 0155 decision 7.2, the export archive's slice), one
// writer and one reader for the web and the apps, held byte for byte by contracts/platform/export/v1-vectors.json. No
// compression, ZIP64 exactly where a field would overflow, a data descriptor after each entry's bytes, and no memory wall:
// the writer hands each record to the sink as it is made and holds one chunk and a directory line per entry; the reader finds
// the central directory from the end of the file, distrusts what it opens (ADR 0082 decision 10) and reads an entry a
// `READ_CHUNK` at a time with `Blob.slice`. What stays here is what only a browser does: the sink (a stream to the download
// route or a file, closed or aborted here), the `Blob` slices, an entry as a lazy `Blob`. This file holds no rule.
import * as core from '@veyq/core-js/export'
import type { ByteSink } from '../blob/stream'

export type ZipErrorCode = 'not_zip' | 'truncated' | 'corrupt' | 'unsupported' | 'crc_mismatch' | 'size_mismatch' | 'duplicate' | 'missing'

/** Anything wrong with an archive being read or written. `code` is stable for callers; the message is for developers. */
export class ZipError extends Error {
  constructor(readonly code: ZipErrorCode, message: string) {
    super(message)
    this.name = 'ZipError'
  }
}

/** The core's refusal of an archive as a {@link ZipError}; any other failure (the sink's, a read's) as it was thrown. */
function asZipError(e: unknown): unknown {
  const r = e as { veyq?: string; code?: ZipErrorCode; message?: string } | null
  return r?.veyq === 'zip' ? new ZipError(r.code!, r.message ?? r.code!) : e
}

async function zipCall<T>(call: () => T | Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (e) {
    throw asZipError(e)
  }
}

/** The format's own ZIP64 limit. */
const U32_MAX = 0xffffffff

export type ZipWriterOptions = {
  /** The modification time stamped on every entry (DOS format, UTC, 2-second resolution). Default: now. */
  modified?: Date
  /** The size from which an entry (or offset) is written as ZIP64. The format's own limit; lowered only by tests, which
   *  cannot afford a 4 GiB payload to reach the ZIP64 branches. */
  zip64At?: number
}

/** One entry of a planned archive, in the order it will be written: a file of `size` bytes, or a directory (`path/`). */
export type ZipPlanEntry = { path: string; size: number } | { path: string; directory: true }

/**
 * The exact length in bytes of the archive a {@link ZipWriter} writes for `entries` (same order, same `zip64At`) — known
 * before the first byte, so a save target that must be told it up front (the SW download route's `Content-Length`, ADR
 * 0047 § The download route) is given the number the writer then produces.
 */
export function zipLength(entries: readonly ZipPlanEntry[], options: Pick<ZipWriterOptions, 'zip64At'> = {}): number {
  return core.zipLength(entries, options.zip64At ?? U32_MAX)
}

/** A chunk source pulled one chunk at a time. */
function pull(chunks: AsyncIterable<Uint8Array> | Iterable<Uint8Array>): core.ZipChunksShape {
  const it = Symbol.asyncIterator in chunks ? chunks[Symbol.asyncIterator]() : (chunks as Iterable<Uint8Array>)[Symbol.iterator]()
  return { next: async () => {
    const r = await it.next()
    return r.done ? null : r.value
  } }
}

/** Writes a ZIP into a {@link ByteSink}. Entries one after another; {@link finish} writes the directory and closes the
 *  sink, {@link abort} aborts it (a half archive is never finalised). */
export class ZipWriter {
  private readonly writer: core.ZipWriterHandle

  constructor(private readonly sink: ByteSink, options: ZipWriterOptions = {}) {
    this.writer = core.zipWriter(sink, (options.modified ?? new Date()).getTime(), options.zip64At ?? U32_MAX)
  }

  /**
   * Add one entry whose `size` bytes arrive from `chunks`. The path is stored as given (UTF-8); it must be a plain relative
   * path - the writer is the last line against an archive a reader would have to distrust.
   * @throws ZipError `size_mismatch` when the chunks add up to anything but `size`, `duplicate` for a repeated path.
   */
  async addFile(path: string, size: number, chunks: AsyncIterable<Uint8Array> | Iterable<Uint8Array>): Promise<{ crc32: number }> {
    return { crc32: await zipCall(() => core.zipAddFile(this.writer, path, size, pull(chunks))) }
  }

  /** Add a directory entry (`path/`, no bytes), so a directory with nothing in it still unpacks. */
  async addDirectory(path: string): Promise<void> {
    await zipCall(() => core.zipAddDirectory(this.writer, path))
  }

  /** Convenience for the small in-memory entry (the manifest). */
  addBytes(path: string, bytes: Uint8Array): Promise<{ crc32: number }> {
    return this.addFile(path, bytes.length, [bytes])
  }

  /** Write the central directory and close the sink. */
  async finish(): Promise<void> {
    await zipCall(() => core.zipFinish(this.writer))
    await this.sink.close()
  }

  /** Abort the sink: whatever was written is discarded, never closed as if whole. */
  async abort(reason?: unknown): Promise<void> {
    core.zipAbort(this.writer)
    await this.sink.abort(reason).catch(() => {})
  }
}

// ---------------------------------------------------------------------------------------------------------- reading

export type ZipEntry = { name: string; size: number; crc32: number; offset: number; /** where its bytes start */ dataAt: number }

/** How much of an entry one read takes - the memory bound of a streamed read. */
export const READ_CHUNK: number = core.zipReadChunk()

/**
 * Open an archive without reading its entries' bytes: its end record, its central directory and each entry's local header
 * and data descriptor, the structure checked. Refuses what this format never produces - compressed or encrypted entries, a
 * multi-disk archive, a name given twice - instead of guessing (`ZipError` `not_zip`, `truncated`, `corrupt`,
 * `unsupported`, `duplicate`).
 */
export async function openZip(file: Blob): Promise<ZipArchive> {
  const reader = await zipCall(() => core.zipOpen({
    size: file.size,
    read: async (from, length) => new Uint8Array(await file.slice(from, from + length).arrayBuffer()),
  }))
  return new ZipArchive(file, reader, new Map(core.zipEntries(reader).map((e) => [e.name, { ...e }])))
}

export class ZipArchive {
  constructor(private readonly file: Blob, private readonly reader: core.ZipReaderHandle, private readonly byName: Map<string, ZipEntry>) {}

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
   * An entry's bytes, in chunks of at most {@link READ_CHUNK}; at the end the CRC-32 is checked against the directory. The
   * check runs after the last chunk was handed out, so a consumer must treat the thrown `crc_mismatch` as "everything you
   * took from this entry is bad" - an import rolls back, it does not keep what it already wrote.
   */
  async *stream(name: string): AsyncGenerator<Uint8Array> {
    const reading = await zipCall(() => core.zipReading(this.reader, name))
    for (let chunk = await zipCall(() => core.zipNext(reading)); chunk != null; chunk = await zipCall(() => core.zipNext(reading))) yield chunk
  }

  /**
   * An entry as a lazy, seekable `Blob` over the archive (no bytes are read). Its checksum is NOT checked - the caller that
   * needs it reads {@link stream} once first (an import verifies each payload's SHA-256 that way before it slices it).
   */
  blob(name: string, type = ''): Blob {
    const e = this.byName.get(name)
    if (!e) throw new ZipError('missing', `no entry ${name}`)
    return this.file.slice(e.dataAt, e.dataAt + e.size, type)
  }

  /** A small entry (the manifest) read whole, refused above `maxBytes`. */
  bytes(name: string, maxBytes: number): Promise<Uint8Array> {
    return zipCall(() => core.zipBytes(this.reader, name, maxBytes))
  }
}
