// uc.memory.background-music (ADR 0135 decisions 2-4) — a music track is a medium of its Memory or chapter whose original
// is the audio file as it was picked: the server stores a sealed blob of a size, as for any medium, and never learns that
// it is music. What counts as one is its sealed `blob-meta` type (`audio/*`, [isAudioMeta]); what a picked file must be to
// become one is checked on the device before anything is uploaded ([checkAudioFile]): no larger than 200 MiB, typed as
// audio by its bytes (core's `sniffStream`, never the browser's guess), and decodable here, since its facts
// (`audioFacts.ts`) are measured from the decode. No key, no request.
import type { OpenedBlobMeta } from '../../../core/blob/meta'
import { sniffStream } from '../../../core/blob/sniff'
import { measureAudio, type AudioFacts } from './audioFacts'

/** The largest audio file a track is made from (ADR 0135 decision 2): refused on the device before anything is uploaded. */
export const MUSIC_MAX_BYTES = 200 * 1024 * 1024

/** How much of a file's head is read to type it: every signature `sniffStream` knows lies in it. */
const HEAD_BYTES = 4096

/**
 * Whether a medium is a music track: its original's sealed type is audio. A medium whose original carries no meta is no
 * track. Its own list (the Music tab, the header's playlist) asks this; every photo view asks `isGalleryMeta` instead,
 * which leaves audio out.
 */
export function isAudioMeta(meta: Pick<OpenedBlobMeta, 'type'> | { readonly type?: unknown } | null | undefined): boolean {
  const type = typeof meta?.type === 'string' ? meta.type.trim().toLowerCase() : ''
  return type.startsWith('audio/')
}

/** A track's title: the file's name without its extension (ADR 0135 decision 4: no tags are read), else null. */
export function musicTitle(name: unknown): string | null {
  if (typeof name !== 'string') return null
  const trimmed = name.trim()
  const dot = trimmed.lastIndexOf('.')
  const title = (dot > 0 ? trimmed.slice(0, dot) : trimmed).trim()
  return title || null
}

/** A length as the list shows it: `m:ss`, `h:mm:ss` from an hour on. */
export function formatTrackLength(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}

/** Why a picked file is refused (E1), each with its own message. */
export type AudioProblem = 'tooLarge' | 'notAudio' | 'undecodable'

/** A picked file as a track: the file, the type its bytes name, and its facts - or why it is refused. */
export type AudioCheck = { file: File; mediaType: string; facts: AudioFacts } | { problem: AudioProblem }

/**
 * Check one picked file on the device (E1): its size first (nothing is read of a file too large), then its head (an audio
 * container core knows), then a decode for its facts. `measure` is the decode-and-scan, a seam for the tests.
 */
export async function checkAudioFile(file: File, measure: (bytes: ArrayBuffer) => Promise<AudioFacts> = measureAudio): Promise<AudioCheck> {
  if (file.size > MUSIC_MAX_BYTES) return { problem: 'tooLarge' }
  let kind
  try {
    kind = sniffStream(new Uint8Array(await file.slice(0, HEAD_BYTES).arrayBuffer()))
  } catch {
    return { problem: 'notAudio' }
  }
  if (kind?.element !== 'audio') return { problem: 'notAudio' }
  try {
    return { file, mediaType: kind.type, facts: await measure(await file.arrayBuffer()) }
  } catch {
    return { problem: 'undecodable' }
  }
}
