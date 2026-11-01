// Fragment-based content navigation (cr-0031, cr-0075, inbox #328). The current tree position lives in the URL
// **#fragment** — e.g. `#~me/reisen-2026/memories/rom` — which the browser never sends to the server, so the
// operator-blind invariant holds even though the path carries human-readable slugs. Everything here is pure +
// client-side: the slug path is resolved against the locally-decrypted tree, and reverse-built from it. This file is the
// ONE place a fragment path is built or read (`scripts/validate_web_fragment_paths_via_location.py`).
//
// **The grammar after the scope token (ADR 0015, amended 2026-10-11):**
//   - a **folder** is its slug path with a TRAILING SLASH: `#reisen-2026/` (the slash marks the target as a folder);
//   - a **Depot** node (file, drop box) is its plain slug, no slash: `#reisen-2026/rechnung`;
//   - a node of **any other application** carries the application's route slug before its name — a Memory `rom` is
//     `#reisen-2026/memories/rom`, a chapter of it extends that (`…/rom/tag-1`; the segment is written where the path
//     enters the application, not again below it). The slug is claimed once in the registry (`claimRouteSlug`).
// A claimed slug is therefore RESERVED among the unprefixed names: a folder (or file) called "Memories" is numbered
// `memories-2` ({@link segmentsOf}). Folders and files are separate numbering spaces: file `a.b` and folder `a-b` are both
// `a-b`. Without the trailing slash a path means the file when one has that slug, else the folder; with it, only the
// folder; `…/a-b/x` always reads `a-b` as a folder (a file has no children) — see {@link resolveSlugPath}.
//
// The leading token selects the scope (ADR 0015 + ADR 0019): its **absence** is the shared **tenant** scope
// (the token-less default home — `/`, `#urlaube`), `~<username>` (or `~me`) is the personal scope, and `@<group>` a
// group: its name's slug when no other of the member's groups shares it, else its scope id (`openGroupByToken`,
// `features/spaces/scopes.ts`). `slugify` emits only `[a-z0-9-]`, so a folder slug can never start with `~`/`@` and
// thus never collides with a scope token.
//
// **A person (ADR 0083, 2026-10-13):** `#people/<userId>` is a member's profile page, not a place in the tree. It is
// token-less (a profile spans the scopes the viewer is in), and `people` is the platform's word at a scope root: a
// root folder or file called "People" is numbered `people-2` ({@link segmentsOf}), and no application may claim it as a
// route slug (`RESERVED_ROUTE_SEGMENTS`). {@link parseHash} reads it into {@link ContentLocation.person}; {@link personHash}
// builds it. A share link never reaches it (the share page has no fragment routes of its own).
import { type TreeNode } from './tree'
import { slugify } from '../../shared/naming'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from './kind'

/** A personal scope token is `~<username>` (ADR 0023) — your own opens your personal scope; a foreign one is still a
 *  personal-scope token, never a profile (a person's profile is `#people/<userId>`, ADR 0083). `~me` remains a valid
 *  fallback token (it also starts with `~`). */
export function isPersonalScope(token: string): boolean {
  return token.startsWith('~')
}
/** Build the personal-scope token for a username (`~kevin`); falls back to `~me` when the handle is unknown. */
export function personalScope(username?: string): string {
  return `~${username || 'me'}`
}
/** The token-less scope marker (ADR 0019): the absence of a leading `~<username>`/`@` token *is* the tenant selector. */
export const TENANT_SCOPE = ''

export type ContentLocation = {
  scope: string
  /** The path segments below the scope root, application segments included (they are resolved, not stripped). */
  segments: string[]
  /** The path ended in a slash: its target is a folder ({@link resolveSlugPath}). Never set for the scope root. */
  folder: boolean
  /** Present only for a profile fragment (`#people/<userId>`): the user id. Then `segments` is empty and `scope` the tenant's. */
  person?: string
}

/** The platform's route word for a person's profile (ADR 0083); reserved at every scope root. */
export const PEOPLE_SEGMENT = 'people'

/** The fragment of a member's profile page: `#people/<userId>`. */
export function personHash(userId: string): string {
  return `#${PEOPLE_SEGMENT}/${encodeURIComponent(userId)}`
}

/** Parse a `location.hash` into a scope token + path segments + the trailing-slash mark. Empty/`#`/token-less → the
 *  tenant scope. */
export function parseHash(hash: string): ContentLocation {
  const raw = hash.replace(/^#/, '')
  const parts = raw
    .split('/')
    .filter(Boolean)
    .map(decodeURIComponent)
  const slash = raw.endsWith('/')
  if (parts.length === 0) return { scope: TENANT_SCOPE, segments: [], folder: false }
  const [head, ...rest] = parts
  // A leading scope token (`~<username>` / `@group`) selects the scope; no token → the token-less tenant scope,
  // and every part is a slug under the tenant root.
  if (isPersonalScope(head) || head.startsWith('@')) return { scope: head, segments: rest, folder: slash && rest.length > 0 }
  if (parts.length === 2 && head === PEOPLE_SEGMENT) return { scope: TENANT_SCOPE, segments: [], folder: false, person: parts[1] }
  return { scope: TENANT_SCOPE, segments: parts, folder: slash }
}

/** Build the fragment for a scope + path. Tenant is token-less (`#urlaube`, `''` at the root); `~<username>`/`@<group>`
 *  carry their leading token (`#~me/reisen-2026/memories/rom`, `#@familie/sommer`). `folder`: the target is a folder,
 *  marked by a trailing slash (`#reisen-2026/`) — meaningless at the scope root, which has no segment to end.
 *
 *  **The scope's `~`/`@` is written as it is, never `%40`.** The fragment reaches the URL through vue-router, which encodes
 *  the hash it is handed once more (`encodeURI`): an `@` written as `%40` became `#%2540familie/…` in the address bar
 *  (red in lane L1, 2026-10-29). Everything else goes through `encodeURIComponent`, which leaves the grammar's own
 *  characters alone — a slug is `[a-z0-9-]`, a username `[A-Za-z0-9_.-]`, a group token a slug or a scope id — and
 *  escapes only what a hand-typed address could carry, so {@link parseHash} reads every fragment back. */
export function buildHash(scope: string, segments: string[], folder = false): string {
  const parts = segments.map(encodeURIComponent)
  const tokens = scope === TENANT_SCOPE ? parts : [scope.charAt(0) + encodeURIComponent(scope.slice(1)), ...parts]
  return tokens.length ? `#${tokens.join('/')}${folder && segments.length > 0 ? '/' : ''}` : ''
}

/** Sibling order: the server-observed creation instant (`NodeSummary.createdAt`, to the millisecond, ADR 0015), then the
 *  node id in UTF-16 code units (locale-free) for two born in one millisecond — the one order that tells equal names
 *  apart, both in the URL ({@link segmentsOf}) and where an application numbers equal display names (Depot's drop box).
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
 * The URL name segment of each of one parent's children, keyed by node id — the **one** rule both directions use, so a
 * path built here always resolves back to the node it was built for. (The application segment in front of it, where one
 * belongs, is {@link slugPathOf}'s.)
 *
 * A child's plain segment is `slugify(name)` whatever its kind (the URL folding; a name's uniqueness,
 * `registry.tokenInputFor`, is not the URL's). Equal plain segments are numbered **within a class** of siblings — the folders;
 * one application's nodes (`registry.routeOf(kind).group`) — so a file `a.b` and a folder `a-b` are both `a-b` (the
 * trailing slash tells them apart), and a Memory and a folder of one name never meet. Among the siblings of one plain
 * segment of one class, in {@link compareByCreation} order, the first keeps it and each further one gets `<slug>-<n>`
 * with the smallest `n ≥ 2` above the previous one's that no sibling of the class holds as its plain segment — a real
 * sibling named `Anna 2` keeps `anna-2`, and the second `Anna` becomes `anna-3`. **A reserved plain segment** — an
 * application's route slug, among the classes whose nodes carry no application segment (folders, Depot) — has no
 * keeper: the application segment stands at that spot of the path, so a folder `Memories` is `memories-2`, a second one
 * `memories-3`. Consequences, all deterministic: a node whose plain segment is unique among its class and not reserved
 * always keeps it; a sibling created later never changes the earlier ones' segments (two born in one millisecond fall to
 * their ids); a node keeps its creation instant through a rename, a move, a trash and a restore, so an older sibling that
 * comes back — restored, or moved or renamed in — takes its birth place (the plain segment, if it is the oldest) and
 * shifts the later ones' segments; and a sibling later given the literal name `anna-3` takes that segment over. A
 * suffixed segment can never equal another group's suffixed segment (`<a>-<n>` = `<b>-<m>` with digits-only n, m forces
 * a = b), so the result does not depend on the order the groups are visited in.
 *
 * **A future folder export** (ADR 0080 names a ZIP export) names its directories by this same rule, so an exported
 * tree and the in-app paths agree; it is client-side only — nothing here is stored or sealed.
 */
export function segmentsOf(siblings: readonly TreeNode[]): Map<string, string> {
  // A scope root also keeps the platform's `people` for the profile route (`#people/<userId>`, ADR 0083).
  const atRoot = siblings.length > 0 && siblings.every((n) => (n.parentId ?? null) === null)
  const reserved = new Set(atRoot ? [...registry.routeSlugs(), PEOPLE_SEGMENT] : registry.routeSlugs())
  const keyOf = (group: string, plain: string) => `${group}\0${plain}`
  const buckets = new Map<string, { group: string; plain: string; unprefixed: boolean; nodes: TreeNode[] }>()
  for (const n of siblings) {
    const { slug, group } = registry.routeOf(n.kind)
    const plain = slugify(n.name)
    const key = keyOf(group, plain)
    const bucket = buckets.get(key)
    if (bucket) bucket.nodes.push(n)
    else buckets.set(key, { group, plain, unprefixed: slug === null, nodes: [n] })
  }
  const segments = new Map<string, string>()
  for (const { group, plain, unprefixed, nodes } of buckets.values()) {
    const sorted = [...nodes].sort(compareByCreation)
    // Reserved: the application segment stands where the plain one would, so nobody keeps it.
    const keeper = unprefixed && reserved.has(plain) ? null : sorted[0]
    if (keeper) segments.set(keeper.id, plain)
    let suffix = 1
    for (const n of keeper ? sorted.slice(1) : sorted) {
      do suffix++
      while (buckets.has(keyOf(group, `${plain}-${suffix}`)))
      segments.set(n.id, `${plain}-${suffix}`)
    }
  }
  return segments
}

/** The application segment a node needs in front of its name: its application's route slug, where the path ENTERS the
 *  application (the parent belongs to another one, or is a folder, or the scope root); none below it (a chapter in a
 *  Memory) and none for a folder or an application without a segment. */
function appSegmentOf(node: TreeNode, parent: TreeNode | null): string | null {
  const slug = registry.routeOf(node.kind).slug
  if (slug === null) return null
  return parent && registry.routeOf(parent.kind).slug === slug ? null : slug
}

/**
 * Resolve a path under the scope root to a node id, walking the tree level by level, each name by {@link segmentsOf}.
 * `null` = the scope root (no segments); a node id = the addressed node; `false` = a segment didn't match.
 *
 * At each level a segment is either an **application segment** — a registered route slug where the parent does not
 * already belong to that application, taking the next segment as the name of one of that application's nodes — or a
 * plain name among the nodes that need no application segment. Trailing slash (`folder`): the last segment names a
 * folder, nothing else. Without it, the last segment names the non-folder node of that name if there is one (a file, a
 * drop box), else the folder; a segment before the last prefers a folder but falls back to a non-folder that has
 * children (a drop box's guest folders), so `…/a-b/x` reads `a-b` as the folder whenever it holds an `x`. The search
 * backtracks over these candidates, so a path resolves if any reading of it does.
 */
export function resolveSlugPath(tree: readonly TreeNode[], segments: string[], folder = false): string | null | false {
  const children = new Map<string | null, TreeNode[]>()
  for (const n of tree) {
    const key = n.parentId ?? null
    const list = children.get(key)
    if (list) list.push(n)
    else children.set(key, [n])
  }
  const names = new Map<string | null, Map<string, string>>()
  const namesOf = (parentId: string | null) => {
    let m = names.get(parentId)
    if (!m) names.set(parentId, (m = segmentsOf(children.get(parentId) ?? [])))
    return m
  }
  const slugs = new Set(registry.routeSlugs())
  const byId = new Map(tree.map((n) => [n.id, n]))
  const count = segments.length

  /** Read the segments from `i` on, below the node `parentId` (null: the scope root). */
  function walk(parentId: string | null, i: number): string | null | false {
    if (i === count) return parentId
    const seg = segments[i]
    const parent = parentId ? byId.get(parentId) ?? null : null
    const parentSlug = parent ? registry.routeOf(parent.kind).slug : null
    const kids = children.get(parentId) ?? []
    const names_ = namesOf(parentId)
    /** Step into `kid`; the last segment of a trailing-slash path must be a folder. */
    const into = (kid: TreeNode, next: number): string | null | false =>
      next === count && folder && kid.kind !== FOLDER_KIND ? false : walk(kid.id, next)
    // 1. An application segment: the next segment names one of that application's nodes.
    if (slugs.has(seg) && seg !== parentSlug && i + 1 < count) {
      for (const kid of kids) {
        if (registry.routeOf(kid.kind).slug === seg && names_.get(kid.id) === segments[i + 1]) {
          const r = into(kid, i + 2)
          if (r !== false) return r
        }
      }
    }
    // 2. A plain name among the nodes that need no application segment, in the order this reading prefers: the last
    //    segment a file before a folder (a trailing slash already ruled files out), any other a folder first.
    const plain = kids.filter((k) => {
      const slug = registry.routeOf(k.kind).slug
      return (slug === null || slug === parentSlug) && names_.get(k.id) === seg
    })
    const folderFirst = i < count - 1
    const ordered = [
      ...plain.filter((k) => (k.kind === FOLDER_KIND) === folderFirst),
      ...plain.filter((k) => (k.kind === FOLDER_KIND) !== folderFirst),
    ]
    for (const kid of ordered) {
      const r = into(kid, i + 1)
      if (r !== false) return r
    }
    return false
  }
  return walk(null, 0)
}

/** The path segments from the scope root down to `nodeId` (root = `[]`): each node's name by {@link segmentsOf}, an
 *  application's segment in front of the first of its nodes on the way ({@link appSegmentOf}). */
export function slugPathOf(tree: TreeNode[], nodeId: string | null): string[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const chain: TreeNode[] = []
  const seen = new Set<string>()
  let cur = nodeId ? byId.get(nodeId) ?? null : null
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id)
    chain.unshift(cur)
    cur = cur.parentId ? byId.get(cur.parentId) ?? null : null
  }
  const segments: string[] = []
  chain.forEach((node, index) => {
    const app = appSegmentOf(node, index > 0 ? chain[index - 1] : null)
    if (app) segments.push(app)
    segments.push(segmentsOf(childrenOfParent(tree, node.parentId ?? null)).get(node.id) as string)
  })
  return segments
}

/** The fragment for a node in the given scope (`null` → the scope root — `''` for tenant, `#~me` for personal). A folder
 *  ends in the slash that marks it ({@link buildHash}). */
export function hashForNode(tree: TreeNode[], nodeId: string | null, scope: string): string {
  const node = nodeId ? tree.find((n) => n.id === nodeId) : undefined
  return buildHash(scope, slugPathOf(tree, nodeId), node?.kind === FOLDER_KIND)
}
