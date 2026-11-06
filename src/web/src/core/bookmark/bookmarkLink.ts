// The link a bookmark keeps (ADR 0120 decision 4): a public link as a member pasted it, checked and normalized before it
// is sealed, never fetched. Pure, so it is the one place the shape of a link is decided, for the create dialog, the meta
// codec that re-checks what it opens, and the open and report hand-off.
//
// A link is `<origin>/s/<shareId>#k=<base64url K_share>` (`core/sharing/shareLink.ts` builds it, `views/ShareView.vue` reads
// the fragment's `k=` among `&`-separated parameters). What is kept is exactly that: the origin, the path and the key —
// any query, any other fragment parameter (`&report`, a `#moderation` review's mode) dropped — so two pastes of one link
// are one string and nothing but the key travels on.
import { fromB64url } from '@veyq/crypto/base64'

/** The longest link kept (`contracts/platform/share-bookmark-meta.v1.schema.json`). */
export const MAX_LINK_LENGTH = 2048

/** A share id and a key are both 32 bytes, 43 base64url characters (`sharing.openapi.yaml` `ShareId`). */
const TOKEN = /^[A-Za-z0-9_-]{43}$/
const SHARE_PATH = /^\/s\/([A-Za-z0-9_-]{43})$/

/** Why a pasted text is no link to keep — each its own words in the create dialog (`node.bookmark.problem.*`). */
export type LinkProblem = 'empty' | 'tooLong' | 'notAUrl' | 'notHttps' | 'credentials' | 'notAShare' | 'noKey'

/** A link that may be kept: its normalized form, its origin and its share id. */
export interface ShareLink {
  link: string
  origin: string
  shareId: string
}

/** A development instance's host, the only one a plain `http` link may name: loopback, or `*.localhost`. */
function devHost(hostname: string): boolean {
  const h = hostname.toLowerCase()
  return h === 'localhost' || h.endsWith('.localhost') || h === '127.0.0.1' || h === '[::1]'
}

/** The fragment's `k=`, among `&`-separated parameters, as the share page reads it; null when there is none. */
function fragmentKey(hash: string): string | null {
  const fragment = hash.startsWith('#') ? hash.slice(1) : hash
  for (const part of fragment.split('&')) {
    if (part.startsWith('k=')) return part.slice(2)
  }
  return null
}

/** Whether `key` is a 32-byte key in base64url (43 characters that decode to 32 bytes). */
function isKey(key: string): boolean {
  if (!TOKEN.test(key)) return false
  try {
    return fromB64url(key).length === 32
  } catch {
    return false
  }
}

/** Check and normalize a pasted link. Fails closed: anything that is not exactly a share link with its key is refused. */
export function parseShareLink(input: string): { ok: true; value: ShareLink } | { ok: false; problem: LinkProblem } {
  const text = input.trim()
  if (!text) return { ok: false, problem: 'empty' }
  if (text.length > MAX_LINK_LENGTH) return { ok: false, problem: 'tooLong' }
  let url: URL
  try {
    url = new URL(text)
  } catch {
    return { ok: false, problem: 'notAUrl' }
  }
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && devHost(url.hostname))) {
    return { ok: false, problem: url.protocol === 'http:' ? 'notHttps' : 'notAUrl' }
  }
  if (url.username || url.password) return { ok: false, problem: 'credentials' }
  const path = SHARE_PATH.exec(url.pathname)
  if (!path) return { ok: false, problem: 'notAShare' }
  const key = fragmentKey(url.hash)
  if (key === null || !isKey(key)) return { ok: false, problem: 'noKey' }
  const shareId = path[1]!
  const link = `${url.origin}/s/${shareId}#k=${key}`
  if (link.length > MAX_LINK_LENGTH) return { ok: false, problem: 'tooLong' }
  return { ok: true, value: { link, origin: url.origin, shareId } }
}

/** A kept link read back: the same check, and it must be the normalized form itself (a meta written by anything else opens
 *  nothing). Null otherwise. */
export function keptLink(link: unknown): ShareLink | null {
  if (typeof link !== 'string') return null
  const parsed = parseShareLink(link)
  return parsed.ok && parsed.value.link === link ? parsed.value : null
}

/** The host a kept link names, as a reader is shown it (`photos.example.org`, a port kept). */
export function hostOf(link: ShareLink): string {
  return new URL(link.origin).host
}

/** The same link with the share page asked to open its own report dialog (ADR 0120 decision 6): one more fragment
 *  parameter, which a share page that predates it ignores (it reads only `k=`). */
export function reportLinkOf(link: ShareLink): string {
  return `${link.link}&report`
}

/** Whether a fragment asks the share page to open its report dialog: the `report` parameter of {@link reportLinkOf}. */
export function asksForReport(hash: string): boolean {
  const fragment = hash.startsWith('#') ? hash.slice(1) : hash
  return fragment.split('&').includes('report')
}

/** The fragment without the `report` parameter: what the share page keeps in its URL once it has read it, so a reload or
 *  a copied address does not open the dialog again. */
export function withoutReport(hash: string): string {
  const fragment = hash.startsWith('#') ? hash.slice(1) : hash
  const kept = fragment.split('&').filter((p) => p !== 'report' && p !== '')
  return kept.length ? `#${kept.join('&')}` : ''
}
