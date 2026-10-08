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

/** Sibling order: the server-observed creation instant (`NodeSummary.createdAt`, to the millisecond, ADR 0015), then the
 *  node id in UTF-16 code units (locale-free) for two born in one millisecond — the one order that tells equal names
 *  apart, both in the URL ({@link segmentsOf}) and where an application numbers equal display names (Files' drop box).
 *  Neither part can be steered by a client: the instant is the server's, and the id breaks only a same-millisecond tie. */
export function compareByCreation(a: { id: string; createdAt: number }, b: { id: string; createdAt: number }): number {
  if (a.createdAt !== b.createdAt) return a.createdAt - b.createdAt
  if (a.id === b.id) return 0
  return a.id < b.id ? -1 : 1
}

/** Node-id order (UTF-16 code units, locale-free): where no creation instant is served — a guest's resolve (inbox #377
 *  (e)) — the one stable order left for {@link displayNamesOf}. */
export function compareById(a: { id: string }, b: { id: string }): number {
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

/**
 * The display label of each of one parent's children, keyed by node id: its name, and where several siblings carry the
 * SAME name — two drop-box guests named `Anna` (their entries sit under different guest keys, so the server's slug
 * uniqueness never sees the pair, ADR 0080 decision 11) — the first in `order` keeps it and the n-th reads `Anna (n)`.
 * Display only, never stored. The **one** numbering every view of such siblings uses (the drop box's list, the level
 * view's heading and cards, the breadcrumb, the guest's folder page, inbox #393), so a folder is called the same in the
 * list it is picked from and on the page it opens. A member's views order by {@link compareByCreation} — the order
 * {@link segmentsOf} gives the URL its `anna`, `anna-2` — and a guest's by {@link compareById}, its resolve serving no
 * creation time (the numbers a guest sees may then differ from the member's).
 */
export function displayNamesOf<T extends { id: string }>(
  siblings: readonly T[],
  nameOf: (n: T) => string,
  order: (a: T, b: T) => number,
): Map<string, string> {
  const seen = new Map<string, number>()
  const labels = new Map<string, string>()
  for (const n of [...siblings].sort(order)) {
    const name = nameOf(n)
    const count = (seen.get(name) ?? 0) + 1
    seen.set(name, count)
    labels.set(n.id, count === 1 ? name : `${name} (${count})`)
  }
  return labels
}

const childrenOfParent = (tree: readonly TreeNode[], parentId: string | null) =>
  tree.filter((n) => (n.parentId ?? null) === parentId)

/**
 * The URL segment of each of one parent's children, keyed by node id — the **one** rule both directions use, so a
 * path built here always resolves back to the node it was built for.
 *
 * A child's plain segment is `slugify(name)` whatever its kind (the URL folding; a kind's uniqueness folding,
 * `registry.slugFor`, is not the URL's). Siblings can share a plain segment: two drop-box guests of one display name
 * (their entries sit under different guest keys, so the server's slug uniqueness never sees the pair, ADR 0080
 * decision 11), or a file and a folder whose kinds fold differently (`a.b` and `a-b`). Among the siblings of one
 * plain segment, in {@link compareByCreation} order, the first keeps it and each further one gets `<slug>-<n>` with the
 * smallest `n ≥ 2` above the previous one's that no sibling holds as its plain segment — a real sibling named
 * `Anna 2` keeps `anna-2`, and the second `Anna` becomes `anna-3`. Consequences, all deterministic: a node whose
 * plain segment is unique among its siblings always keeps it; a sibling created later never changes the earlier ones'
 * segments (two born in one millisecond fall to their ids); a node keeps its creation instant through a rename, a move,
 * a trash and a restore, so an older sibling that comes back — restored, or moved or renamed in — takes its birth
 * place (the plain segment, if it is the oldest) and shifts the later ones' segments; and a sibling later given the
 * literal name `anna-3` takes that segment over. A suffixed segment can never equal another group's suffixed segment
 * (`<a>-<n>` = `<b>-<m>` with digits-only n, m forces a = b), so the result does not depend on the order the groups
 * are visited in.
 *
 * **A future folder export** (ADR 0080 names a ZIP export) names its directories by this same rule, so an exported
 * tree and the in-app paths agree; it is client-side only — nothing here is stored or sealed.
 */
export function segmentsOf(siblings: readonly TreeNode[]): Map<string, string> {
  const byPlain = new Map<string, TreeNode[]>()
  for (const n of siblings) {
    const plain = slugify(n.name)
    const group = byPlain.get(plain)
    if (group) group.push(n)
    else byPlain.set(plain, [n])
  }
  const segments = new Map<string, string>()
  for (const [plain, group] of byPlain) {
    const [first, ...rest] = [...group].sort(compareByCreation)
    segments.set(first.id, plain)
    let suffix = 1
    for (const n of rest) {
      do suffix++
      while (byPlain.has(`${plain}-${suffix}`))
      segments.set(n.id, `${plain}-${suffix}`)
    }
  }
  return segments
}

/**
 * Resolve a slug path under the scope root to a node id, walking the tree level by level, each level by
 * {@link segmentsOf}. `null` = the scope root (no segments); a node id = the addressed node; `false` = a segment
 * didn't match.
 */
export function resolveSlugPath(tree: TreeNode[], segments: string[]): string | null | false {
  let parentId: string | null = null
  for (const seg of segments) {
    let match: string | null = null
    for (const [id, segment] of segmentsOf(childrenOfParent(tree, parentId))) if (segment === seg) match = id
    if (match === null) return false
    parentId = match
  }
  return parentId
}

/** The slug path from the scope root down to `nodeId` (root = `[]`), each segment by {@link segmentsOf}. */
export function slugPathOf(tree: TreeNode[], nodeId: string | null): string[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const segments: string[] = []
  let cur = nodeId ? byId.get(nodeId) ?? null : null
  while (cur) {
    const parentId = cur.parentId ?? null
    segments.unshift(segmentsOf(childrenOfParent(tree, parentId)).get(cur.id) as string)
    cur = parentId ? byId.get(parentId) ?? null : null
  }
  return segments
}

/** The fragment for a node in the given scope (`null` → the scope root — `''` for tenant, `#~me` for personal). */
export function hashForNode(tree: TreeNode[], nodeId: string | null, scope: string): string {
  return buildHash(scope, slugPathOf(tree, nodeId))
}
