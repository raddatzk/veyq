// The kind-independent half of resolving a public share (uc.sharing.share-public-viewer, ADR 0029; cr-platform-web-
// boundaries). Everything here is the platform's: parsing the fragment key, the unauthenticated fetch, the password
// challenge and its guest token, `K_share`, the sealed name and note, the accountless terms gate, and the three error
// classes the shell maps to its states. None of it reads what a node IS: the one thing this half learns about the
// shared object's kind is the root node's `kind`, returned as `rootKind` so the shell can ask the registry which
// application's body renders the share.
//
// What this half does NOT do is open anything the application owns: the root `K_mem` (the share's `wrap`), the
// chapters' keys, the media rows and an uploader's drop key are the application's (`openSharedMemory` in
// features/memory/share.ts). The head hands them the unlocked response and `K_share`, nothing else.
//
// **The root kind of a WRITE_ONLY share is unknown to the client.** An unlocked uploader response serves no nodes
// (`PublicShare.nodeId` only), so `rootKind` is null for it and the shell dispatches such a share to the one
// application that claimed the kind-less drop body (registry.claimShareBody(DROP_SHARE, …)). A `rootKind` on
// `PublicShare` is the follow-up contract change that retires that rule before a second shareable kind exists.
import * as v1 from '@veyq/crypto/v1'
import { sharesPwTokenAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import type { LocaleMap } from '../../shared/ui/localeMap'
import type { NodeKind } from '../../api/generated/content'
import type { ShareRole, PublicShare, ShareTerms } from '../../api/generated/sharing'

// The seals under `K_share` name their purpose only (ADR 0072 Decision 2): `K_share` is unique to one share, so the
// key is the binding. These two are the ones the platform half opens (and the application's curator side seals).
export const SHARE_MEMORY_NAME = { purpose: 'share-memory-name' } as const
export const SHARE_NOTE = { purpose: 'share-note' } as const
const textDecoder = new TextDecoder()

/** Open a per-locale note map sealed under `K_share`; `{}` when it can't be opened or isn't a JSON object. */
export function openNoteMap(kShare: Uint8Array, sealed: string): LocaleMap {
  try {
    const parsed = JSON.parse(textDecoder.decode(v1.open(SHARE_NOTE, kShare, fromB64std(sealed))))
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as LocaleMap) : {}
  } catch {
    return {}
  }
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
  role: ShareRole
  /** The kind of the share's root node (`ShareNode.kind` of the node with `parentId: null`); null when none is served,
   *  which is every WRITE_ONLY share (see the header). */
  rootKind: NodeKind | null
}

/**
 * Resolve the kind-independent half of a public share for the anonymous viewer. Throws [ShareUnavailableError] on a
 * missing/bad fragment key or a 404 (unknown/revoked/expired — indistinguishable), [SharePasswordRequiredError] on
 * the locked shell, [SharePasswordIncorrectError] on a wrong guest token.
 */
export async function resolvePublicShare(shareId: string, fragmentKey: string, pwToken?: string): Promise<PublicShareHead> {
  let kShare: Uint8Array
  try {
    kShare = fromB64url(fragmentKey)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key')
  }
  if (kShare.length !== 32) throw new ShareUnavailableError('bad_fragment_key')

  let share: PublicShare
  try {
    share = await publicSharesApi().resolveShare({ shareId, xSharePassword: pwToken }) // cr-0118: the guest token gates it
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.forbidden)) throw new SharePasswordIncorrectError() // wrong password
    throw new ShareUnavailableError('not_found') // 404 (revoked/expired/unknown) or a network error
  }
  // cr-0118: a locked share (password required, no/valid-less token) serves only the challenge — prompt the viewer.
  // Locked is "no sealed name", never "no wrap": an unlocked WRITE_ONLY share carries no wrap either.
  if (share.passwordRequired && !share.sealedName) throw new SharePasswordRequiredError(share.pwSalt ?? '')
  if (!share.scopeId || !share.sealedName) throw new ShareUnavailableError('not_found') // unlocked ⇒ scope + name

  let name: string
  try {
    name = textDecoder.decode(v1.open(SHARE_MEMORY_NAME, kShare, fromB64std(share.sealedName)))
  } catch {
    throw new ShareUnavailableError('bad_fragment_key') // the id was valid but the fragment key doesn't match
  }
  // cr-0116: the optional per-locale curator note, opened with the same fragment key (a bad/absent one leaves `{}`).
  const noteByLocale = share.sealedNote ? openNoteMap(kShare, share.sealedNote) : {}
  const rootKind = (share.nodes ?? []).find((n) => n.parentId == null)?.kind ?? null
  return { shareId, share, kShare, scopeId: share.scopeId, name, noteByLocale, role: share.role, rootKind }
}

// --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ----------
// The accountless half of the two-tier terms gate. The terms are the operator's PUBLIC policy (never E2EE); a
// visitor accepts them before viewing/contributing, and acceptance is stored **client-side only** (localStorage
// keyed per tenant) so the server records NOTHING about the accountless visitor. This is an **operational gate,
// not enforcement**: a localStorage flag is bypassable by an accountless visitor (devtools / a fresh profile /
// resolving the share directly), so it records + surfaces consent — it does not *prevent* access. The
// legally-meaningful protection is the terms' content + the decryptable-on-report path, not this prompt.
const TERMS_ACCEPTED_KEY_PREFIX = 'veyq:terms-accepted:'

/**
 * The owning tenant's public terms for a share (a guest read keyed on the non-enumerable `shareId`, so unknown/
 * disabled/expired is the same non-enumerable 404 as `resolvePublicShare` — no `tenantId` existence oracle). Returns
 * `{ tenantId, termsVersion, terms }`; `termsVersion` 0 means the operator published no terms (the client never
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

/** The locally-recorded accepted terms version for a tenant (0 if never accepted or the store is unreadable). */
export function acceptedTermsVersion(tenantId: string): number {
  try {
    const raw = localStorage.getItem(`${TERMS_ACCEPTED_KEY_PREFIX}${tenantId}`)
    const n = raw ? Number.parseInt(raw, 10) : 0
    return Number.isFinite(n) ? n : 0
  } catch {
    return 0
  }
}

/** Record acceptance of [version] for [tenantId] — client-only (localStorage), keyed per tenant so accepting on
 *  one share of a tenant clears the gate on **every** share of that tenant. A storage failure is swallowed: the
 *  gate is operational, not enforcement, so it must never throw and block a visitor from viewing. */
export function recordTermsAccepted(tenantId: string, version: number): void {
  try {
    localStorage.setItem(`${TERMS_ACCEPTED_KEY_PREFIX}${tenantId}`, String(version))
  } catch {
    /* private-mode / quota — an unrecordable acceptance still clears the in-memory gate for this session */
  }
}
