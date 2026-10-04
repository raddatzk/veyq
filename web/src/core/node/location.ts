// Fragment-based content navigation (cr-0031, cr-0075). The current Memory tree position lives in the URL
// **#fragment** — e.g. `#~me/reisen-2026/rom` — which the browser never sends to the server, so the
// operator-blind invariant holds even though the path carries human-readable slugs. Everything here is pure +
// client-side: the slug path is resolved against the locally-decrypted tree, and reverse-built from it.
//
// The leading token selects the scope (ADR 0015 + ADR 0019): its **absence** is the shared **tenant** scope
// (the token-less default home — `/`, `#urlaube`), `~me` is the personal scope, and the `@group` form is
// reserved for the (later) groups slice — parsed, but only tenant/personal resolve today. `slugify` emits only
// `[a-z0-9-]`, so a folder slug can never start with `~`/`@` and thus never collides with a scope token.
import { type TreeNode } from './tree'
import { slugify } from '../../shared/naming'

/** A personal/profile scope token is `~<username>` (ADR 0023) — your own opens your personal scope, a foreign
 *  one is the later profile view. `~me` remains a valid fallback token (it also starts with `~`). */
export function isPersonalScope(token: string): boolean {
  return token.startsWith('~')
}
/** Build the personal-scope token for a username (`~kevin`); falls back to `~me` when the handle is unknown. */
export function personalScope(username?: string): string {
  return `~${username || 'me'}`
}
/** The token-less scope marker (ADR 0019): the absence of a leading `~<username>`/`@` token *is* the tenant selector. */
export const TENANT_SCOPE = ''

export type ContentLocation = { scope: string; segments: string[] }

/** Parse a `location.hash` into a scope token + slug segments. Empty/`#`/token-less → the tenant scope. */
export function parseHash(hash: string): ContentLocation {
  const parts = hash
    .replace(/^#/, '')
    .split('/')
    .filter(Boolean)
    .map(decodeURIComponent)
  if (parts.length === 0) return { scope: TENANT_SCOPE, segments: [] }
  const [head, ...rest] = parts
  // A leading scope token (`~<username>` / `@group`) selects the scope; no token → the token-less tenant scope,
  // and every part is a slug under the tenant root.
  if (isPersonalScope(head) || head.startsWith('@')) return { scope: head, segments: rest }
  return { scope: TENANT_SCOPE, segments: parts }
}

/** Build the fragment for a scope + slug path. Tenant is token-less (`#urlaube`, `''` at the root); `~me`/`@`
 *  carry their leading token (`#~me/reisen-2026/rom`). */
export function buildHash(scope: string, segments: string[]): string {
  const tokens = scope === TENANT_SCOPE ? segments : [scope, ...segments]
  return tokens.length ? `#${tokens.map(encodeURIComponent).join('/')}` : ''
}

/**
 * Resolve a slug path under the scope root to a node id, walking the tree level by level.
 * `null` = the scope root (no segments); a node id = the addressed node; `false` = a segment didn't match.
 */
export function resolveSlugPath(tree: TreeNode[], segments: string[]): string | null | false {
  let parentId: string | null = null
  for (const seg of segments) {
    const match = tree.find((n) => (n.parentId ?? null) === parentId && slugify(n.name) === seg)
    if (!match) return false
    parentId = match.id
  }
  return parentId
}

/** The slug path from the scope root down to `nodeId` (root = `[]`). */
export function slugPathOf(tree: TreeNode[], nodeId: string | null): string[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const segments: string[] = []
  let cur = nodeId ? byId.get(nodeId) ?? null : null
  while (cur) {
    segments.unshift(slugify(cur.name))
    cur = cur.parentId ? byId.get(cur.parentId) ?? null : null
  }
  return segments
}

/** The fragment for a node in the given scope (`null` → the scope root — `''` for tenant, `#~me` for personal). */
export function hashForNode(tree: TreeNode[], nodeId: string | null, scope: string): string {
  return buildHash(scope, slugPathOf(tree, nodeId))
}
