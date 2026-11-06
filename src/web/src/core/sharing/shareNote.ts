// A share's curator note as a value (uc.sharing.share-note, ADR 0036 point 2): its per-locale texts and the switch that
// asks a visitor to accept it before the share opens. Both live inside ONE seal under `K_share` (`share-note`), so the
// operator cannot tell which links ask: the switch is never a field of its own on the wire.
//
// The plaintext has two shapes, and both are read:
//  - the flat `{locale: markdown}` map every client wrote before the switch existed, and still writes with it off — so
//    a link without the switch is byte-for-byte what it was, and an old note never gates;
//  - `{"texts": {locale: markdown}, "mustAccept": true}`, written only with the switch on.
// Anything else (an unknown shape, a non-boolean `mustAccept`, a text that is not a string) is a note that does not
// open, exactly as a seal that does not open: no text and no gate. Never a gate the visitor cannot pass.
//
// The acceptance is remembered on the visitor's DEVICE only (localStorage), keyed by the share id and a digest of the
// opened plaintext — so an edited note asks again, and the note's text itself is never written to storage. Nothing of
// it reaches the server. Like the tenant-terms gate it is an operational gate, not enforcement: the content opens with
// the fragment key alone, and going past the gate by other means is the visitor's own not-accepting.
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import type { LocaleMap } from '../../shared/ui/localeMap'
import { deviceKey, type ShareAt } from './shareAt'

/** A curator note: its per-locale markdown and whether a visitor must accept it before the share opens. */
export type ShareNote = { texts: LocaleMap; mustAccept: boolean }

/** No note: nothing is sealed for it. */
export const NO_NOTE: ShareNote = Object.freeze({ texts: Object.freeze({}) as LocaleMap, mustAccept: false })

/** Whether a per-locale map has any non-blank text. */
export function hasLocaleText(map: LocaleMap): boolean {
  return Object.values(map).some((t) => t.trim().length > 0)
}

/** The plaintext a note is sealed as: today's flat map with the switch off, the wrapped shape with it on. */
export function encodeShareNote(note: ShareNote): string {
  return JSON.stringify(note.mustAccept ? { texts: note.texts, mustAccept: true } : note.texts)
}

/** A plain JSON object whose every value is a string — the only thing a locale map may be. */
function localeMapOf(value: unknown): LocaleMap | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const entries = Object.entries(value)
  return entries.every(([, t]) => typeof t === 'string') ? (Object.fromEntries(entries) as LocaleMap) : null
}

/** Read a note's plaintext; null for anything that is neither shape (the caller treats it as a note that does not
 *  open). The wrapped shape is told apart by its `texts` key, which no locale is named. */
export function decodeShareNote(plaintext: string): ShareNote | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(plaintext)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  if (!('texts' in parsed)) {
    const texts = localeMapOf(parsed)
    return texts ? { texts, mustAccept: false } : null
  }
  const { texts, mustAccept, ...rest } = parsed as { texts: unknown; mustAccept?: unknown }
  const map = localeMapOf(texts)
  if (!map || typeof mustAccept !== 'boolean' || Object.keys(rest).length > 0) return null
  return { texts: map, mustAccept }
}

/** The digest that keys a note's acceptance on the device: SHA-256 of the opened plaintext bytes, base64url. */
export function noteDigest(plaintext: Uint8Array): string {
  return b64url(v1.sha256Digest(plaintext))
}

// Keyed by the share - and, for another instance's link, its origin (`./shareAt.ts`): this origin's key is unchanged.
const NOTE_ACCEPTED_KEY_PREFIX = 'veyq:share-note-accepted'
const acceptedKey = (at: ShareAt, digest: string) => `${deviceKey(NOTE_ACCEPTED_KEY_PREFIX, at)}:${digest}`

/** Whether this device accepted this share's note in exactly this text. False when storage is unreadable or throws:
 *  a device that cannot remember asks again, it never skips the gate. */
export function isNoteAccepted(at: ShareAt, digest: string): boolean {
  try {
    return localStorage.getItem(acceptedKey(at, digest)) === '1'
  } catch {
    return false
  }
}

/** Remember on this device that this share's note was accepted in this text. Nothing else is stored — no account, no
 *  text. A storage failure is swallowed: the page clears its gate for this visit, and the next load asks again. */
export function recordNoteAccepted(at: ShareAt, digest: string): void {
  try {
    localStorage.setItem(acceptedKey(at, digest), '1')
  } catch {
    /* private mode / quota / blocked storage — nothing is remembered, so the next load asks again */
  }
}
