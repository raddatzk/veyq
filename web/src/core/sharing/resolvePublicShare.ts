// The kind-independent half of resolving a public share (uc.sharing.share-public-viewer, ADR 0029; cr-platform-web-
// boundaries). Everything here is the platform's: parsing the fragment key, the unauthenticated fetch, the password
// challenge and its guest token, `K_share`, the sealed name and note, the accountless terms gate, and the three error
// classes the shell maps to its states. None of it reads what a node IS: the one thing this half learns about the
// shared object's kind is the root node's kind, which the server names as `PublicShare.rootKind` for every unlocked
// role — a WRITE_ONLY share too, though it is served no node (inbox #329) — and which the head passes on so the shell
// can ask the registry which application's body renders the share.
//
// What this half does NOT do is read or open anything the application owns: the root `K_node` (the share's `wrap`),
// the chapters' keys and an uploader's drop key are the application's to open (`openSharedMemory` in
// features/memory/share.ts), and what it attached under the nodes is its own guest read (`listShareMedia`) — the
// resolve serves no application data. The head hands the application the unlocked response and `K_share`, nothing else.
import * as v1 from '@veyq/crypto/v1'
import { sharesPwTokenAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import type { LocaleMap } from '../../shared/ui/localeMap'
import { decodeShareNote, hasLocaleText, noteDigest, type ShareNote } from './shareNote'
import type { NodeKind } from '../node/kind'
import type { ShareRole, PublicShare, ShareTerms } from '../../api/generated/sharing'
import { tripleKey, type TermsTriple } from '../terms/operatorSection'

// The seals under `K_share` name their purpose only (ADR 0072 Decision 2): `K_share` is unique to one share, so the
// key is the binding. These two are the ones the platform half opens (and the application's curator side seals).
export const SHARE_MEMORY_NAME = { purpose: 'share-memory-name' } as const
export const SHARE_NOTE = { purpose: 'share-note' } as const
const textDecoder = new TextDecoder()

/** A note opened with `K_share`: the note, and the digest of its plaintext that keys a device's acceptance of it. */
export type OpenedShareNote = { note: ShareNote; digest: string }

/** Open a curator note sealed under `K_share` (either plaintext shape, `./shareNote.ts`). A seal that does not open and
 *  a plaintext of neither shape are the same answer, `null`: no text, and no gate. */
export function openShareNote(kShare: Uint8Array, sealed: string): OpenedShareNote | null {
  let plaintext: Uint8Array
  try {
    plaintext = v1.openField(SHARE_NOTE, kShare, fromB64std(sealed))
  } catch {
    return null
  }
  const note = decodeShareNote(textDecoder.decode(plaintext))
  return note ? { note, digest: noteDigest(plaintext) } : null
}

/** Pick the best per-locale text for a viewer's locale (exact → base language → en → first present). '' if empty. */
export function pickLocaleText(map: LocaleMap, locale: string): string {
  const entries = Object.entries(map).filter(([, t]) => t && t.trim())
  if (!entries.length) return ''
  const req = locale.toLowerCase()
  const base = req.split('-')[0]
  const hit =
    entries.find(([l]) => l.toLowerCase() === req) ??
    entries.find(([l]) => l.toLowerCase() === base) ??
    entries.find(([l]) => l.toLowerCase() === 'en') ??
    entries[0]
  return hit[1]
}

/** The base64 guest token `Argon2id(password, pwSalt)` a viewer presents in `X-Share-Password` (the salt comes from
 *  the locked resolve). Heavy (Argon2id), so it runs in the Argon2 worker; a one-off per unlock. */
export async function derivePasswordToken(password: string, pwSalt: string): Promise<string> {
  return b64std(await sharesPwTokenAsync(password, fromB64std(pwSalt)))
}

/** The fragment key is missing/malformed, or the share is unknown/revoked/expired (a non-enumerable 404). */
export class ShareUnavailableError extends Error {}

/** cr-0118: the share is password-protected and no (valid) token was given — the caller prompts with `pwSalt`. */
export class SharePasswordRequiredError extends Error {
  constructor(public pwSalt: string) {
    super('password_required')
  }
}
/** cr-0118: the presented password token was wrong (403) — the caller re-prompts. */
export class SharePasswordIncorrectError extends Error {}

/** What the platform half of a resolve yields: the unlocked server response (the application opens its own seals from
 *  it), `K_share`, and the kind-independent fields already opened. `scopeId` is the shared object's scope — every seal
 *  the application opens is bound to it (ADR 0072). */
export type PublicShareHead = {
  shareId: string
  share: PublicShare
  /** `K_share`, from the URL fragment. Key material: never put it in a deep reactive ref. */
  kShare: Uint8Array
  scopeId: string
  /** The shared object's name, re-sealed for the viewer under `K_share`. */
  name: string
  /** The optional curator note per locale (`pickLocaleText` picks the viewer's). */
  noteByLocale: LocaleMap
  /** Present only when the note asks to be accepted before the share opens (its switch, sealed inside the note): the
   *  digest of the opened plaintext, which keys this device's memory of the acceptance (`./shareNote.ts`). Absent for a
   *  note without the switch, an older client's note, and a note that does not open. */
  noteAcceptance?: { digest: string }
  role: ShareRole
  /** The kind of the share's root node (`PublicShare.rootKind`); null when the server names none — a root in the
   *  trash, which serves nothing (the shell then shows its unavailable state). */
  rootKind: NodeKind | null
  /** A moderator's review only (`core/sharing/review.ts`): the root's key, handed over by a report share, in place of
   *  the `wrap` a link opens with `K_share` — the review holds no `K_share` and its share carries no wrap. Key material. */
  rootKey?: Uint8Array
}

/** `K_share` out of the URL fragment; [ShareUnavailableError] when it is missing or malformed. */
function shareKeyOf(fragmentKey: string): Uint8Array {
  let kShare: Uint8Array
  try {
    kShare = fromB64url(fragmentKey)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key')
  }
  if (kShare.length !== 32) throw new ShareUnavailableError('bad_fragment_key')
  return kShare
}

/** The head of an unlocked response, or the error its shape means (locked shell, no scope or name, a key that opens nothing). */
function headOf(shareId: string, kShare: Uint8Array, share: PublicShare): PublicShareHead {
  // cr-0118: a locked share (password required, no/valid-less token) serves only the challenge — prompt the viewer.
  // Locked is "no sealed name", never "no wrap": an unlocked WRITE_ONLY share carries no wrap either.
  if (share.passwordRequired && !share.sealedName) throw new SharePasswordRequiredError(share.pwSalt ?? '')
  if (!share.scopeId || !share.sealedName) throw new ShareUnavailableError('not_found') // unlocked ⇒ scope + name

  let name: string
  try {
    name = textDecoder.decode(v1.openField(SHARE_MEMORY_NAME, kShare, fromB64std(share.sealedName)))
  } catch {
    throw new ShareUnavailableError('bad_fragment_key') // the id was valid but the fragment key doesn't match
  }
  // cr-0116: the optional per-locale curator note, opened with the same fragment key (a bad/absent one leaves `{}`).
  const opened = share.sealedNote ? openShareNote(kShare, share.sealedNote) : null
  const noteByLocale = opened?.note.texts ?? {}
  // A note asks to be accepted only with text to accept: a switch on an empty note gates nothing.
  const noteAcceptance = opened?.note.mustAccept && hasLocaleText(opened.note.texts) ? { digest: opened.digest } : undefined
  return {
    shareId, share, kShare, scopeId: share.scopeId, name, noteByLocale, ...(noteAcceptance ? { noteAcceptance } : {}),
    role: share.role, rootKind: share.rootKind ?? null,
  }
}

/**
 * Resolve the kind-independent half of a public share for the anonymous viewer. Throws [ShareUnavailableError] on a
 * missing/bad fragment key or a 404 (unknown/revoked/expired — indistinguishable), [SharePasswordRequiredError] on
 * the locked shell, [SharePasswordIncorrectError] on a wrong guest token.
 */
export async function resolvePublicShare(shareId: string, fragmentKey: string, pwToken?: string): Promise<PublicShareHead> {
  const kShare = shareKeyOf(fragmentKey)
  let share: PublicShare
  try {
    share = await publicSharesApi().resolveShare({ shareId, xSharePassword: pwToken }) // cr-0118: the guest token gates it
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.forbidden)) throw new SharePasswordIncorrectError() // wrong password
    throw new ShareUnavailableError('not_found') // 404 (revoked/expired/unknown) or a network error
  }
  return headOf(shareId, kShare, share)
}

/** What a polling resolve yields: the head again and the tag its response carried (`null`: the server sent none). */
export type PolledShare = { head: PublicShareHead; etag: string | null }

/**
 * The live refresh's resolve (ADR 0081): the same head, asked CONDITIONALLY — `ifNoneMatch` is the tag of the last
 * response, and a resolve that would serve exactly that answers `304`, which is `null` here (nothing to open, nothing
 * to compare). The tag is a digest of what the guest is served, never the scope's revision, so a change outside the
 * shared subtree leaves it alone.
 *
 * Unlike [resolvePublicShare] it tells a refusal from a hiccup: a `404` (revoked, expired, deleted, its space going —
 * one non-enumerable answer) is [ShareUnavailableError], a `403` [SharePasswordIncorrectError], the locked shell
 * [SharePasswordRequiredError]; a network failure, a `429` or a `5xx` is rethrown as it is, because a poll that cannot
 * reach the server knows nothing about the link and must not take the page down.
 */
export async function pollPublicShare(
  shareId: string, fragmentKey: string, pwToken: string | undefined, ifNoneMatch: string | null,
): Promise<PolledShare | null> {
  const kShare = shareKeyOf(fragmentKey)
  let share: PublicShare
  let etag: string | null
  try {
    const res = await publicSharesApi().resolveShareRaw({ shareId, xSharePassword: pwToken, ifNoneMatch: ifNoneMatch ?? undefined })
    share = await res.value()
    etag = res.raw.headers.get('ETag')
  } catch (e) {
    if (ifNoneMatch && isHttpStatus(e, HttpStatus.notModified)) return null
    if (isHttpStatus(e, HttpStatus.forbidden)) throw new SharePasswordIncorrectError()
    if (isHttpStatus(e, HttpStatus.notFound)) throw new ShareUnavailableError('not_found')
    throw e
  }
  return { head: headOf(shareId, kShare, share), etag }
}

// --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ----------
// The accountless half of the two-tier terms gate. The terms are the space owner's PUBLIC policy (never E2EE); a
// visitor accepts them before viewing/contributing, and acceptance is stored **client-side only** (localStorage
// keyed per tenant) so the server records NOTHING about the accountless visitor. This is an **operational gate,
// not enforcement**: a localStorage flag is bypassable by an accountless visitor (devtools / a fresh profile /
// resolving the share directly), so it records + surfaces consent — it does not *prevent* access. The
// legally-meaningful protection is the terms' content + the decryptable-on-report path, not this prompt.
// What is stored per tenant is the accepted TRIPLE (ADR 0035 decision 6): the space's revision, the operator section's
// revision and its variant, as `tripleKey` spells it. A value an older client stored (a bare revision) equals no triple,
// so that visitor is asked once more — rightly, since the operator section was not part of what they accepted.
const TERMS_ACCEPTED_KEY_PREFIX = 'veyq:terms-accepted:'

/**
 * The owning tenant's public terms for a share (a guest read keyed on the non-enumerable `shareId`, so unknown/
 * disabled/expired is the same non-enumerable 404 as `resolvePublicShare` — no `tenantId` existence oracle). Returns
 * `{ tenantId, termsRevision, terms }`; `termsRevision` 0 means the space owner published no terms (the client never
 * gates). No auth, no password — terms are public.
 */
export async function fetchShareTerms(shareId: string): Promise<ShareTerms> {
  return publicSharesApi().getShareTerms({ shareId })
}

/** Pick the viewer's-locale terms markdown out of a [ShareTerms] (exact → base → en → first), '' when none. */
export function pickShareTerms(terms: ShareTerms, locale: string): string {
  const map: LocaleMap = {}
  if (terms.terms?.de) map.de = terms.terms.de
  if (terms.terms?.en) map.en = terms.terms.en
  return pickLocaleText(map, locale)
}

/** The locally-recorded accepted triple for a tenant, as `tripleKey` spells it ('' if never accepted or the store is
 *  unreadable). */
export function acceptedTermsKey(tenantId: string): string {
  try {
    return localStorage.getItem(`${TERMS_ACCEPTED_KEY_PREFIX}${tenantId}`) ?? ''
  } catch {
    return ''
  }
}

/** Record acceptance of [triple] for [tenantId] — client-only (localStorage), keyed per tenant so accepting on one
 *  share of a tenant clears the gate on **every** share of that tenant. A storage failure is swallowed: the gate is
 *  operational, not enforcement, so it must never throw and block a visitor from viewing. */
export function recordTermsAccepted(tenantId: string, triple: TermsTriple): void {
  try {
    localStorage.setItem(`${TERMS_ACCEPTED_KEY_PREFIX}${tenantId}`, tripleKey(triple))
  } catch {
    /* private-mode / quota — an unrecordable acceptance still clears the in-memory gate for this session */
  }
}
