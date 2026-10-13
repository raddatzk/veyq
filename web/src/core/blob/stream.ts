// Streaming a decrypted blob into a sink, chunk by chunk (inbox #371): the read side's counterpart to the upload
// pump. A download used to hold every plaintext chunk until the last arrived and assemble one `Blob`, so it needed
// about the file's size in tab memory. Here the chunks are opened at most `inFlight` at a time and written to the sink
// strictly in order, each dropped once written: what the page holds is bounded by the window, not by the file.
//
// The sink decides where the bytes go — a file the user picked (File System Access), the Service Worker's download
// route, or, below a cap, an in-memory `Blob` (`shared/saveStream.ts` chooses). This module knows none of them and
// imports no application; it needs only a chunk source (`makeChunkProvider` is the real one).
import type { ChunkProvider } from '../../sw/protocol'

/** Where a streamed download writes. `write` may apply backpressure by resolving late; the stream never starts more
 *  than its window of chunks while a write is pending. Exactly one of `close` (every byte written) or `abort` (anything
 *  went wrong — a partial file must never be finalised as if it were whole) ends it. */
export type ByteSink = {
  write(bytes: Uint8Array): Promise<void>
  close(): Promise<void>
  abort(reason?: unknown): Promise<void>
}

/** The part of a {@link ChunkProvider} a stream reads. */
export type ChunkSource = Pick<ChunkProvider, 'chunkCount' | 'getPlaintextChunk'>

export type StreamOptions = {
  /** At most this many chunks opened but not yet written — the memory bound. Default {@link STREAM_IN_FLIGHT}. */
  inFlight?: number
  signal?: AbortSignal
  /** Called after each chunk is written, with the fraction of chunks written so far. */
  onProgress?: (fraction: number) => void
}

/** The default window: the upload pump's bound (`PUMP_IN_FLIGHT`), so a download holds no more frames than an upload. */
export const STREAM_IN_FLIGHT = 4

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw signal.reason ?? new DOMException('Download cancelled', 'AbortError')
}

/**
 * Open every chunk of `source` and write it to `sink`, in index order, with at most `inFlight` chunks opened ahead of
 * the write. On success the sink is closed; on ANY failure — a chunk that does not open, a sink write that fails, the
 * signal — the sink is aborted (never closed) and the error rethrown. A close that fails aborts too.
 */
export async function streamBlob(source: ChunkSource, sink: ByteSink, options: StreamOptions = {}): Promise<void> {
  const { signal, onProgress } = options
  const inFlight = Math.max(1, Math.floor(options.inFlight ?? STREAM_IN_FLIGHT))
  const total = source.chunkCount
  const opened = new Map<number, Promise<Uint8Array>>()
  let started = 0
  const start = (upTo: number) => {
    for (; started < Math.min(upTo, total); started++) {
      const p = source.getPlaintextChunk(started)
      p.catch(() => {}) // observed when its turn comes; a rejection while an earlier chunk is awaited is not unhandled
      opened.set(started, p)
    }
  }
  try {
    for (let i = 0; i < total; i++) {
      throwIfAborted(signal)
      // Chunk 0 alone first: a provider presigns on its first read, and parallel first reads would each presign.
      start(i === 0 ? 1 : i + inFlight)
      const bytes = await opened.get(i)!
      opened.delete(i)
      throwIfAborted(signal)
      if (bytes.length > 0) await sink.write(bytes)
      onProgress?.((i + 1) / total)
    }
    throwIfAborted(signal)
    await sink.close()
  } catch (e) {
    await sink.abort(e).catch(() => {})
    throw e
  }
}

/**
 * The same windowed read as {@link streamBlob}, as an async iterator: a consumer that pulls (the ZIP writer of a subtree
 * export) gets the chunks in order, at most `inFlight` opened ahead of the one it takes, each dropped once handed out. A
 * consumer that stops early (a `break`, a throw, the signal) ends the read; chunks already opened are discarded.
 */
export async function* iterateBlob(source: ChunkSource, options: Pick<StreamOptions, 'inFlight' | 'signal'> = {}): AsyncGenerator<Uint8Array> {
  const { signal } = options
  const inFlight = Math.max(1, Math.floor(options.inFlight ?? STREAM_IN_FLIGHT))
  const total = source.chunkCount
  const opened = new Map<number, Promise<Uint8Array>>()
  let started = 0
  const start = (upTo: number) => {
    for (; started < Math.min(upTo, total); started++) {
      const p = source.getPlaintextChunk(started)
      p.catch(() => {})
      opened.set(started, p)
    }
  }
  for (let i = 0; i < total; i++) {
    throwIfAborted(signal)
    start(i === 0 ? 1 : i + inFlight)
    const bytes = await opened.get(i)!
    opened.delete(i)
    yield bytes
  }
  throwIfAborted(signal)
}
