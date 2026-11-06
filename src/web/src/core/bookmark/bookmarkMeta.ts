// A bookmark's sealed meta (ADR 0120 decision 3, contracts/platform/share-bookmark-meta.v1.schema.json): the `bookmark`
// sub-object of core's node-meta envelope (`core/node/nodeMeta.ts`), under the bookmark's own `K_node`. It holds the link
// and, where one was given, the share's password, padded so the sealed envelope's length is a multiple of 512 characters:
// the operator sees a node of this kind and a meta of one of a few sizes, and nothing of the host or of a password. A
// rotation re-seals the whole envelope under the node's new key (`core/node/rekey/rekeyScope.ts`); nothing in it is a key
// of this space, so the kind re-wraps nothing of its own.
import type { RawNodeMeta } from '../node/nodeMeta'
import { keptLink, type ShareLink } from './bookmarkLink'

export const BOOKMARK_META_KEY = 'bookmark'
/** The longest password kept (the schema's `maxLength`). */
export const MAX_PASSWORD_LENGTH = 256
/** The sealed plaintext is rounded up to a multiple of this many bytes. */
export const META_PAD = 512

/** What a bookmark keeps, opened. */
export interface Bookmark {
  link: ShareLink
  password: string | null
}

const utf8Length = (s: string) => new TextEncoder().encode(s).length

/**
 * The node-meta envelope of a new bookmark (sealed by the caller with `sealNodeMeta`), padded: `pad` holds as many spaces
 * as round the envelope's JSON — exactly what `sealNodeMeta` encodes — up to a multiple of {@link META_PAD} bytes.
 */
export function bookmarkMetaObject(link: ShareLink, password: string | null): RawNodeMeta {
  const sub: Record<string, unknown> = { v: 1, link: link.link }
  if (password) sub.password = password
  sub.pad = ''
  const meta: RawNodeMeta = { v: 1, [BOOKMARK_META_KEY]: sub }
  const length = utf8Length(JSON.stringify({ ...meta, v: 1 }))
  sub.pad = ' '.repeat((META_PAD - (length % META_PAD)) % META_PAD)
  return meta
}

/** The bookmark an opened envelope holds, or null when it holds none this build reads: no `bookmark` object, a link that is
 *  not a kept share link, a password that is no string. Fail closed - a bookmark that does not read opens nothing. */
export function bookmarkOf(meta: RawNodeMeta | null): Bookmark | null {
  const sub = meta?.[BOOKMARK_META_KEY]
  if (!sub || typeof sub !== 'object' || Array.isArray(sub)) return null
  const { link, password } = sub as { link?: unknown; password?: unknown }
  const kept = keptLink(link)
  if (!kept) return null
  if (password !== undefined && (typeof password !== 'string' || password.length === 0 || password.length > MAX_PASSWORD_LENGTH)) return null
  return { link: kept, password: typeof password === 'string' ? password : null }
}
