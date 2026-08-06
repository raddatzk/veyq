// cr-inline-video-playback Slice 2 (ADR 0047) — PURE range↔chunk arithmetic for the media Service Worker.
//
// This file holds NO service-worker globals and NO key material: it is the maths that turns the <video> demuxer's
// plaintext byte-range request into the covering ciphertext chunk indices and back into the exact window of bytes
// to answer. Kept pure so it unit-tests directly; mediaSw.ts is the thin self-based wrapper that drives it.

/** Per-frame ciphertext overhead: nonce(12) + AES-GCM tag(16). A plaintext chunk of size s ships as s+28 bytes. */
export const FRAME_OVERHEAD = 28

/**
 * Plaintext bytes per chunk (every chunk but the last is full). MUST equal media.ts `CHUNK_SIZE`; a unit test
 * pins the two equal so they cannot drift (the SW bundle must not import media.ts, which would pull the whole
 * data layer + exifr + the API client into the worker).
 */
export const PLAINTEXT_CHUNK_SIZE = 256 * 1024

/** Total plaintext size of a blob from its opaque ciphertext size + chunk count (each frame adds FRAME_OVERHEAD). */
export function plaintextTotal(byteSize: number, chunkCount: number): number {
  return byteSize - FRAME_OVERHEAD * chunkCount
}

/** A resolved, satisfiable, inclusive plaintext byte range. */
export type ByteRange = { start: number; end: number }

export type ParsedRange =
  | { kind: 'full' } // no Range header → 200 + Accept-Ranges (the demuxer then re-requests with ranges)
  | { kind: 'range'; range: ByteRange } // a satisfiable 206 window
  | { kind: 'unsatisfiable' } // 416

/**
 * Parse an HTTP `Range` header against a known plaintext `total`. Supports the three byte-range forms a native
 * `<video>` demuxer emits: `bytes=A-B` (incl. the first probe `bytes=0-1`), `bytes=N-` (open-ended), and
 * `bytes=-N` (suffix). A missing/empty header is `full`: the SW answers `200` with `Accept-Ranges` + length so the
 * demuxer learns the resource is range-capable and re-requests with ranges. Only single ranges are supported
 * (a multipart `Range` — never emitted by a media element — is treated as unsatisfiable).
 */
export function parseRange(header: string | null | undefined, total: number): ParsedRange {
  if (!header) return { kind: 'full' }
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim())
  if (!m || (m[1] === '' && m[2] === '')) return { kind: 'unsatisfiable' }
  let start: number
  let end: number
  if (m[1] === '') {
    // suffix form `bytes=-N`: the last N bytes
    const n = Number(m[2])
    if (n === 0 || total === 0) return { kind: 'unsatisfiable' }
    start = Math.max(0, total - n)
    end = total - 1
  } else {
    start = Number(m[1])
    end = m[2] === '' ? total - 1 : Number(m[2])
  }
  if (total === 0 || start >= total || start > end) return { kind: 'unsatisfiable' }
  return { kind: 'range', range: { start, end: Math.min(end, total - 1) } } // clamp an over-long end (RFC 7233)
}

/** The inclusive chunk-index span [first,last] whose plaintext covers `range`. */
export function coveringChunks(range: ByteRange, chunkSize = PLAINTEXT_CHUNK_SIZE): { first: number; last: number } {
  return { first: Math.floor(range.start / chunkSize), last: Math.floor(range.end / chunkSize) }
}

/**
 * The `[from,to)` sub-slice of chunk `index`'s plaintext that falls inside `range` (an empty `to===from` if the
 * chunk lies wholly outside). `chunkLen` is the chunk's actual plaintext length (the last chunk is short).
 */
export function clampChunkToRange(
  index: number,
  chunkLen: number,
  range: ByteRange,
  chunkSize = PLAINTEXT_CHUNK_SIZE,
): { from: number; to: number } {
  const chunkStart = index * chunkSize
  const from = Math.min(Math.max(range.start - chunkStart, 0), chunkLen)
  const to = Math.min(Math.max(range.end + 1 - chunkStart, 0), chunkLen)
  return { from, to: Math.max(from, to) }
}

/** The `Content-Range` header value for a `206`. */
export function contentRange(range: ByteRange, total: number): string {
  return `bytes ${range.start}-${range.end}/${total}`
}

/** The `Content-Range` header value for a `416` — the RFC 7233 unsatisfied-range form (`bytes` star slash total). */
export function unsatisfiedRange(total: number): string {
  return `bytes */${total}`
}
