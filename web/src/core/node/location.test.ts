// @vitest-environment happy-dom
// (The route grammar asks the application registry which kinds carry which segment — `src/test/setup.ts` fills it for a
// DOM-environment file, as `main.ts` does: Memories claims `memories`, Depot declares no segment.)
import { describe, it, expect } from 'vitest'
import {
  parseHash,
  buildHash,
  resolveSlugPath,
  slugPathOf,
  hashForNode,
  compareByCreation,
  compareById,
  displayNamesOf,
  personHash,
  PEOPLE_SEGMENT,
  TENANT_SCOPE,
} from './location'
import { breadcrumbOf } from './browse'
import { registry } from '../applications/registry'
import { FOLDER_KIND, type NodeKind } from './kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memory/kinds'
import { DROPBOX_KIND } from '../../features/depot/kinds'
import type { TreeNode } from './tree'
import { treeNode } from '../../test/models'

// A concrete personal token — any `~<username>` parses as personal (ADR 0023); `~me` is the fallback.
const PERSONAL_SCOPE = '~me'

const node = (id: string, name: string, parentId: string | null, kind: NodeKind = FOLDER_KIND, createdAt = 0): TreeNode => treeNode({
  id, name, parentId, kind, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, createdAt,
})

// Reisen 2026 / Rom (a tree that lives in whichever scope is active)
const tree: TreeNode[] = [
  node('f1', 'Reisen 2026', null),
  node('m1', 'Rom', 'f1', MEMORY_KIND),
  node('f2', 'Sommer', 'f1'),
]

describe('parseHash', () => {
  it('defaults empty/#/token-less to the tenant scope (ADR 0019)', () => {
    expect(parseHash('')).toEqual({ scope: TENANT_SCOPE, segments: [], folder: false })
    expect(parseHash('#')).toEqual({ scope: TENANT_SCOPE, segments: [], folder: false })
    // A bare slug path with no leading token is the tenant root, not personal.
    expect(parseHash('#urlaube')).toEqual({ scope: TENANT_SCOPE, segments: ['urlaube'], folder: false })
    expect(parseHash('#reisen-2026/memories/rom')).toEqual({ scope: TENANT_SCOPE, segments: ['reisen-2026', 'memories', 'rom'], folder: false })
  })

  it('resolves the explicit `~me` token to the personal scope', () => {
    expect(parseHash('#~me')).toEqual({ scope: PERSONAL_SCOPE, segments: [], folder: false })
    expect(parseHash('#~me/reisen-2026/memories/rom')).toEqual({ scope: PERSONAL_SCOPE, segments: ['reisen-2026', 'memories', 'rom'], folder: false })
  })

  it('parses the (later) group token, leaving it unresolved', () => {
    expect(parseHash('#@familie/sommer')).toEqual({ scope: '@familie', segments: ['sommer'], folder: false })
  })

  it('keeps the trailing slash — it marks the target as a folder — in every scope, and never on the scope root', () => {
    expect(parseHash('#urlaube/')).toEqual({ scope: TENANT_SCOPE, segments: ['urlaube'], folder: true })
    expect(parseHash('#reisen-2026/sommer/')).toEqual({ scope: TENANT_SCOPE, segments: ['reisen-2026', 'sommer'], folder: true })
    expect(parseHash('#~kevin/reisen-2026/')).toEqual({ scope: '~kevin', segments: ['reisen-2026'], folder: true })
    expect(parseHash('#%40familie/sommer/')).toEqual({ scope: '@familie', segments: ['sommer'], folder: true })
    // the written-out leading slash of the grammar (`#/foo/memory/`) reads as `#foo/memory/`
    expect(parseHash('#/foo/memory/')).toEqual({ scope: TENANT_SCOPE, segments: ['foo', 'memory'], folder: true })
    // the scope root has no segment to end in a slash
    expect(parseHash('#~me/')).toEqual({ scope: PERSONAL_SCOPE, segments: [], folder: false })
    expect(parseHash('#/')).toEqual({ scope: TENANT_SCOPE, segments: [], folder: false })
    expect(parseHash('#urlaube').folder).toBe(false)
  })

  it('never parses a folder slug as a scope token (slugs are [a-z0-9-], so `~`/`@` never lead)', () => {
    // A slug can never begin with `~` or `@`, so a leading slug always selects the token-less tenant scope.
    expect(parseHash('#reisen-2026').scope).toBe(TENANT_SCOPE)
    expect(parseHash('#me').scope).toBe(TENANT_SCOPE) // `me` is a slug, not the `~me` token
  })
})

describe('buildHash', () => {
  it('builds the token-less tenant fragment (`` at the root, `#urlaube` for a child)', () => {
    expect(buildHash(TENANT_SCOPE, [])).toBe('')
    expect(buildHash(TENANT_SCOPE, ['urlaube'])).toBe('#urlaube')
    expect(buildHash(TENANT_SCOPE, ['reisen-2026', 'rom'])).toBe('#reisen-2026/rom')
  })

  it('keeps the leading token for personal / group fragments', () => {
    expect(buildHash(PERSONAL_SCOPE, [])).toBe('#~me')
    expect(buildHash(PERSONAL_SCOPE, ['reisen-2026', 'rom'])).toBe('#~me/reisen-2026/rom')
    // `@` url-encodes to `%40` (unchanged from cr-0031); it decodes back on parse, so the fragment round-trips.
    expect(buildHash('@familie', ['sommer'])).toBe('#%40familie/sommer')
    expect(parseHash(buildHash('@familie', ['sommer']))).toEqual({ scope: '@familie', segments: ['sommer'], folder: false })
  })

  it('emits the trailing slash for a folder, in every scope, and none at the scope root', () => {
    expect(buildHash(TENANT_SCOPE, ['foo', 'memory'], true)).toBe('#foo/memory/')
    expect(buildHash(PERSONAL_SCOPE, ['foo'], true)).toBe('#~me/foo/')
    expect(buildHash('@familie', ['sommer'], true)).toBe('#%40familie/sommer/')
    expect(buildHash(TENANT_SCOPE, [], true)).toBe('')
    expect(buildHash(PERSONAL_SCOPE, [], true)).toBe('#~me')
  })

  it('round-trips with parseHash, the slash included', () => {
    for (const scope of [TENANT_SCOPE, PERSONAL_SCOPE, '@familie']) {
      for (const folder of [false, true]) {
        const segments = ['reisen-2026', 'memories', 'rom']
        expect(parseHash(buildHash(scope, segments, folder))).toEqual({ scope, segments, folder })
      }
    }
  })
})

describe('slugPathOf + hashForNode', () => {
  it('round-trips a node to its fragment in the personal scope', () => {
    // the Memory carries Memories' segment where the path enters the application
    expect(slugPathOf(tree, 'm1')).toEqual(['reisen-2026', 'memories', 'rom'])
    expect(hashForNode(tree, 'm1', PERSONAL_SCOPE)).toBe('#~me/reisen-2026/memories/rom')
    expect(hashForNode(tree, 'f1', PERSONAL_SCOPE)).toBe('#~me/reisen-2026/')
    expect(hashForNode(tree, null, PERSONAL_SCOPE)).toBe('#~me')
  })

  it('round-trips a node to its token-less fragment in the tenant scope', () => {
    expect(hashForNode(tree, 'm1', TENANT_SCOPE)).toBe('#reisen-2026/memories/rom')
    expect(hashForNode(tree, 'f2', TENANT_SCOPE)).toBe('#reisen-2026/sommer/')
    expect(hashForNode(tree, null, TENANT_SCOPE)).toBe('')
    // parse ∘ build is identity for the tenant scope
    const { scope, segments, folder } = parseHash(hashForNode(tree, 'm1', TENANT_SCOPE))
    expect(scope).toBe(TENANT_SCOPE)
    expect(resolveSlugPath(tree, segments, folder)).toBe('m1')
  })
})

describe('resolveSlugPath', () => {
  it('walks the slug path to a node id (level by level)', () => {
    expect(resolveSlugPath(tree, [])).toBeNull() // root
    expect(resolveSlugPath(tree, ['reisen-2026'])).toBe('f1')
    expect(resolveSlugPath(tree, ['reisen-2026', 'memories', 'rom'])).toBe('m1')
  })

  it('returns false when a segment does not match', () => {
    expect(resolveSlugPath(tree, ['nope'])).toBe(false)
    expect(resolveSlugPath(tree, ['reisen-2026', 'venedig'])).toBe(false)
    // a Memory is reached through its application's segment only, and the segment alone names nothing
    expect(resolveSlugPath(tree, ['reisen-2026', 'rom'])).toBe(false)
    expect(resolveSlugPath(tree, ['reisen-2026', 'memories'])).toBe(false)
    expect(resolveSlugPath(tree, ['memories', 'rom'])).toBe(false)
    // …and a folder is not reachable through it
    expect(resolveSlugPath(tree, ['reisen-2026', 'memories', 'sommer'])).toBe(false)
  })

  it('round-trips with hashForNode for every node (both scopes)', () => {
    for (const scope of [PERSONAL_SCOPE, TENANT_SCOPE]) {
      for (const n of tree) {
        const { segments, folder } = parseHash(hashForNode(tree, n.id, scope))
        expect(resolveSlugPath(tree, segments, folder)).toBe(n.id)
      }
    }
  })
})

// Two drop-box guests who give the same display name get two identity folders of that name (ADR 0080 decision 11:
// each entry is sealed under its own guest's K_up, so the server's slug uniqueness cannot see the pair). This pins
// the slug path tells them apart (`segmentsOf`): `anna`, `anna-2`, `anna-3` in the order the server made them
// (`createdAt`, then the id for one millisecond), built and resolved by one rule. Before it, both folders built
// `…/anna` and that path opened whichever the tree list held first (inbox #372).
describe('equal sibling slugs', () => {
  const BOX = 'b0000000-0000-4000-8000-000000000000'
  // Created in this order — and their ids sort AGAINST it on purpose, so every outcome below is the creation order's.
  const ANNA_1 = 'a3000000-0000-4000-8000-000000000000'
  const ANNA_2 = 'a2000000-0000-4000-8000-000000000000'
  const ANNA_3 = 'a1000000-0000-4000-8000-000000000000'
  const BORN: Record<string, number> = { [ANNA_1]: 1_000, [ANNA_2]: 2_000, [ANNA_3]: 3_000 }
  const identityFolder = (id: string, name: string, createdAt = BORN[id]): TreeNode =>
    ({ ...node(id, name, BOX, FOLDER_KIND, createdAt), shareIdentityId: `i-${id}` })
  const box = node(BOX, 'Uploads', null, DROPBOX_KIND)
  // Listed against creation order on purpose: neither direction may hinge on the list order.
  const dropTree: TreeNode[] = [box, identityFolder(ANNA_2, 'Anna'), identityFolder(ANNA_1, 'Anna')]
  const lastSegment = (t: TreeNode[], id: string) => slugPathOf(t, id).at(-1)

  /** Every node's built path resolves back to it, and no two nodes build the same path — in both list orders. */
  function expectUnambiguous(t: TreeNode[]) {
    for (const list of [t, [...t].reverse()]) {
      const paths = list.map((n) => hashForNode(list, n.id, PERSONAL_SCOPE))
      expect(new Set(paths).size).toBe(list.length)
      for (const n of list) {
        const { segments, folder } = parseHash(hashForNode(list, n.id, PERSONAL_SCOPE))
        expect(resolveSlugPath(list, segments, folder)).toBe(n.id)
      }
    }
  }

  it('gives the first created the plain slug and the second `-2`, whatever the list order', () => {
    for (const list of [dropTree, [...dropTree].reverse()]) {
      expect(slugPathOf(list, ANNA_1)).toEqual(['uploads', 'anna'])
      expect(slugPathOf(list, ANNA_2)).toEqual(['uploads', 'anna-2'])
      expect(resolveSlugPath(list, ['uploads', 'anna'])).toBe(ANNA_1)
      expect(resolveSlugPath(list, ['uploads', 'anna-2'])).toBe(ANNA_2)
    }
    expect(hashForNode(dropTree, ANNA_2, TENANT_SCOPE)).toBe('#uploads/anna-2/')
    expectUnambiguous(dropTree)
  })

  it('numbers a third `-3`, and the levels below each folder resolve through its own segment', () => {
    const t = [
      ...dropTree,
      identityFolder(ANNA_3, 'Anna'),
      node('c3000000-0000-4000-8000-000000000000', 'Fotos', ANNA_3),
      node('c2000000-0000-4000-8000-000000000000', 'Fotos', ANNA_2),
    ]
    expect(lastSegment(t, ANNA_3)).toBe('anna-3')
    expect(resolveSlugPath(t, ['uploads', 'anna-3', 'fotos'])).toBe('c3000000-0000-4000-8000-000000000000')
    expect(resolveSlugPath(t, ['uploads', 'anna-2', 'fotos'])).toBe('c2000000-0000-4000-8000-000000000000')
    expect(resolveSlugPath(t, ['uploads', 'anna-4'])).toBe(false)
    expectUnambiguous(t)
  })

  it('never shifts an earlier path when a later-created sibling arrives, wherever its id sorts', () => {
    // Its id sorts first, between and last: under the id order this replaced, the first of them took `anna` away.
    for (const id of ['00000000-0000-4000-8000-000000000000', 'a2800000-0000-4000-8000-000000000000', 'ff000000-0000-4000-8000-000000000000']) {
      const t = [...dropTree, identityFolder(id, 'Anna', 2_001)]
      expect([ANNA_1, ANNA_2, id].map((n) => lastSegment(t, n))).toEqual(['anna', 'anna-2', 'anna-3'])
      expectUnambiguous(t)
    }
  })

  it('falls to the node id, in code units, for two created in one millisecond', () => {
    const LOW = 'B9000000'
    const HIGH = 'a9000000'
    // `localeCompare` puts `a…` before `B…`; code units put `B` (0x42) first. The tie-break is the code-unit one.
    const pair = [identityFolder(HIGH, 'Anna', 5_000), identityFolder(LOW, 'Anna', 5_000)]
    for (const list of [[box, ...pair], [box, ...[...pair].reverse()]]) {
      expect(lastSegment(list, LOW)).toBe('anna')
      expect(lastSegment(list, HIGH)).toBe('anna-2')
    }
    expect(compareByCreation({ id: 'B', createdAt: 1 }, { id: 'a', createdAt: 1 })).toBeLessThan(0)
    expect(compareByCreation({ id: 'a', createdAt: 1 }, { id: 'a', createdAt: 1 })).toBe(0)
    // the instant decides before the id does
    expect(compareByCreation({ id: 'a', createdAt: 1 }, { id: 'B', createdAt: 2 })).toBeLessThan(0)
  })

  // By design, and deterministic: a node keeps its createdAt through a trash and a restore (and a move or a rename), so
  // an older sibling that comes back takes its birth place and the younger ones' segments shift by one. A link to
  // `…/anna` made while it was away then opens the restored, older folder — the price of an order no client chooses.
  it('gives a restored older sibling back its birth place, shifting the later ones', () => {
    const away = [box, identityFolder(ANNA_2, 'Anna'), identityFolder(ANNA_3, 'Anna')]
    expect([ANNA_2, ANNA_3].map((id) => lastSegment(away, id))).toEqual(['anna', 'anna-2'])
    const restored = [...away, identityFolder(ANNA_1, 'Anna')]
    expect([ANNA_1, ANNA_2, ANNA_3].map((id) => lastSegment(restored, id))).toEqual(['anna', 'anna-2', 'anna-3'])
    expectUnambiguous(restored)
  })

  it('never hands out a segment a real sibling holds as its own name: `Anna 2` keeps `anna-2`, the second Anna skips it', () => {
    // It is created first AND last, so the outcome hinges on the name, not on where the literal sits in creation order.
    const literalId = 'e0000000-0000-4000-8000-000000000000'
    for (const literalAt of [0, 9_000]) {
      const t = [...dropTree, identityFolder(ANNA_3, 'Anna'), identityFolder(literalId, 'Anna 2', literalAt)]
      expect(lastSegment(t, literalId)).toBe('anna-2')
      expect([ANNA_1, ANNA_2, ANNA_3].map((id) => lastSegment(t, id))).toEqual(['anna', 'anna-3', 'anna-4'])
      expectUnambiguous(t)
    }
    // Two literal `Anna 2`s are their own group: `anna-2` and `anna-2-2`, never meeting the Annas' `anna-<n>`.
    const OLDER = '02000000-0000-4000-8000-000000000000'
    const YOUNGER = '01000000-0000-4000-8000-000000000000'
    const t = [...dropTree, identityFolder(OLDER, 'Anna 2', 4_000), identityFolder(YOUNGER, 'anna-2', 4_001)]
    expect(lastSegment(t, OLDER)).toBe('anna-2')
    expect(lastSegment(t, YOUNGER)).toBe('anna-2-2')
    expect(lastSegment(t, ANNA_2)).toBe('anna-3')
    expectUnambiguous(t)
  })

  it('numbers folders and files apart: file `a.b` and folder `a-b` are both `a-b`, told apart by the trailing slash', () => {
    // A folder `a-b` and a file `a.b` fold to different uniqueness slugs (ADR 0080, both may exist) and to one URL segment.
    const FOLDER_AB = 'e0000000-0000-4000-8000-000000000000'
    const FILE_AB = 'e1000000-0000-4000-8000-000000000000'
    const t = [...tree, node(FOLDER_AB, 'a-b', 'f1', FOLDER_KIND, 20), node(FILE_AB, 'a.b', 'f1', 'FILE', 10)]
    expect(lastSegment(t, FILE_AB)).toBe('a-b')
    expect(lastSegment(t, FOLDER_AB)).toBe('a-b')
    expect(hashForNode(t, FILE_AB, TENANT_SCOPE)).toBe('#reisen-2026/a-b')
    expect(hashForNode(t, FOLDER_AB, TENANT_SCOPE)).toBe('#reisen-2026/a-b/')
    expect(slugPathOf(t, 'm1')).toEqual(['reisen-2026', 'memories', 'rom'])
    expectUnambiguous(t)
  })

  it('carries the suffix into the breadcrumb links', () => {
    const t = [...dropTree, node('c2000000-0000-4000-8000-000000000000', 'Fotos', ANNA_2)]
    expect(breadcrumbOf(t, 'c2000000-0000-4000-8000-000000000000', 'Root', TENANT_SCOPE).map((c) => c.hash)).toEqual([
      '',
      '#uploads', // the drop box: a Depot node, its plain name
      '#uploads/anna-2/', // its guest's folder: a folder, the trailing slash
      '#uploads/anna-2/fotos/',
    ])
  })

  // Inbox #393 (b): the crumbs NAME the folder as its link addresses it — `Anna (2)` for the one at `anna-2`.
  it('names the crumbs as the list numbers them: the second Anna reads "Anna (2)"', () => {
    const t = [...dropTree, node('c2000000-0000-4000-8000-000000000000', 'Fotos', ANNA_2)]
    expect(breadcrumbOf(t, 'c2000000-0000-4000-8000-000000000000', 'Root', TENANT_SCOPE).map((c) => c.name)).toEqual([
      'Root', 'Uploads', 'Anna (2)', 'Fotos',
    ])
    expect(breadcrumbOf(t, ANNA_1, 'Root', TENANT_SCOPE).at(-1)!.name).toBe('Anna')
  })
})

// The one numbering of equal sibling names (inbox #393): display only, in the order the caller gives.
describe('displayNamesOf', () => {
  const s = (id: string, name: string, createdAt: number) => ({ id, name, createdAt })
  it('keeps a unique name, and numbers a repeated one from (2) in the given order', () => {
    const siblings = [s('c', 'Anna', 3), s('a', 'Anna', 1), s('b', 'Ben', 2), s('d', 'Anna', 2)]
    expect(Object.fromEntries(displayNamesOf(siblings, (n) => n.name, compareByCreation))).toEqual({
      a: 'Anna', d: 'Anna (2)', c: 'Anna (3)', b: 'Ben',
    })
    // a guest's view has no creation time: node-id order
    expect(Object.fromEntries(displayNamesOf(siblings, (n) => n.name, compareById))).toEqual({
      a: 'Anna', c: 'Anna (2)', d: 'Anna (3)', b: 'Ben',
    })
  })
})

// The grammar of inbox #328 (ADR 0015, amended 2026-10-11): a folder ends in a slash, a Depot node is its plain name, any
// other application's node carries the application's slug, and a claimed slug is reserved among the plain names.
describe('the route grammar', () => {
  it('rests on what the applications claimed in the real registry: Memories `memories`, Depot no segment', () => {
    expect(registry.routeSlugs()).toEqual(['memories'])
    expect(() => registry.claimRouteSlug('memories', ['SOMETHING'])).toThrow(/already claimed/) // Memories holds it
    expect(() => registry.claimNoRouteSegment(['SOMETHING'])).toThrow(/no route segment/) // Depot declared it
    for (const kind of [MEMORY_KIND, CHAPTER_KIND]) expect(registry.routeOf(kind).slug).toBe('memories')
    for (const kind of ['FILE', DROPBOX_KIND]) expect(registry.routeOf(kind).slug).toBeNull()
  })

  const FILE = 'FILE'
  /** Everything one parent `foo` can hold: a folder, a file and a Memory — all called "memory". */
  const mixed: TreeNode[] = [
    node('foo', 'foo', null),
    node('d-memory', 'memory', 'foo', FOLDER_KIND, 3),
    node('f-memory', 'memory', 'foo', FILE, 2),
    node('m-memory', 'memory', 'foo', MEMORY_KIND, 1),
    node('c-1', 'Tag 1', 'm-memory', CHAPTER_KIND, 4),
    node('d-inner', 'inner', 'd-memory', FOLDER_KIND, 5),
  ]
  const hashOf = (t: TreeNode[], id: string, scope = TENANT_SCOPE) => hashForNode(t, id, scope)
  const resolveHash = (t: TreeNode[], hash: string) => {
    const { segments, folder } = parseHash(hash)
    return resolveSlugPath(t, segments, folder)
  }

  it('writes a folder with the trailing slash, a file plain, a Memory behind its application segment', () => {
    expect(hashOf(mixed, 'd-memory')).toBe('#foo/memory/')
    expect(hashOf(mixed, 'f-memory')).toBe('#foo/memory')
    expect(hashOf(mixed, 'm-memory')).toBe('#foo/memories/memory')
    expect(hashOf(mixed, 'foo')).toBe('#foo/')
  })

  it('extends a Memory\'s path with its chapters and states the application segment once', () => {
    expect(hashOf(mixed, 'c-1')).toBe('#foo/memories/memory/tag-1')
    expect(slugPathOf(mixed, 'c-1')).toEqual(['foo', 'memories', 'memory', 'tag-1'])
    expect(hashOf(mixed, 'c-1', '~kevin')).toBe('#~kevin/foo/memories/memory/tag-1')
    expect(resolveHash(mixed, '#foo/memories/memory/tag-1')).toBe('c-1')
    expect(resolveHash(mixed, '#foo/memories/memory/memories/tag-1')).toBe(false) // the segment is not repeated below
  })

  it('resolves one slug three ways: file without slash, folder with it, Memory behind its segment', () => {
    expect(resolveHash(mixed, '#foo/memory')).toBe('f-memory') // without a slash the file wins…
    expect(resolveHash(mixed, '#foo/memory/')).toBe('d-memory') // …the slash asks for the folder only
    expect(resolveHash(mixed, '#foo/memories/memory')).toBe('m-memory')
    expect(resolveHash(mixed, '#foo/memories/memory/')).toBe(false) // the slash is a folder's: a Memory is no folder
    // a file has no children: a longer path reads the slug as the folder
    expect(resolveHash(mixed, '#foo/memory/inner')).toBe('d-inner')
    expect(resolveHash(mixed, '#foo/memory/inner/')).toBe('d-inner')
    expect(resolveHash(mixed, '#foo/memory/inner/nope')).toBe(false)
    // a path with no file of that slug resolves to the folder, the slash or not
    expect(resolveHash(mixed, '#foo')).toBe('foo')
    expect(resolveHash(mixed, '#foo/')).toBe('foo')
  })

  it('a trailing slash never reaches a file', () => {
    const t = [node('foo', 'foo', null), node('rechnung', 'Rechnung', 'foo', FILE, 1)]
    expect(resolveHash(t, '#foo/rechnung')).toBe('rechnung')
    expect(resolveHash(t, '#foo/rechnung/')).toBe(false)
    expect(resolveHash(t, '#foo/rechnung/x')).toBe(false)
  })

  it('round-trips every node of the mixed parent, in every kind of scope and either list order', () => {
    for (const scope of [TENANT_SCOPE, PERSONAL_SCOPE, '~kevin', '@familie']) {
      for (const list of [mixed, [...mixed].reverse()]) {
        const seen = new Set<string>()
        for (const n of list) {
          const hash = hashOf(list, n.id, scope)
          expect(seen.has(hash), `${hash} is built twice`).toBe(false)
          seen.add(hash)
          expect(parseHash(hash).scope).toBe(scope)
          expect(resolveHash(list, hash), hash).toBe(n.id)
        }
      }
    }
  })

  it('reserves a claimed slug: a folder, a file or a drop box called "Memories" is numbered, a Memory is not', () => {
    const t = [
      node('d1', 'Memories', null, FOLDER_KIND, 1),
      node('d2', 'memories', null, FOLDER_KIND, 2),
      node('f1', 'MEMORIES', null, FILE, 3),
      node('x1', 'Memories', null, DROPBOX_KIND, 4),
      node('m1', 'Memories', null, MEMORY_KIND, 5),
    ]
    expect(slugPathOf(t, 'd1')).toEqual(['memories-2'])
    expect(slugPathOf(t, 'd2')).toEqual(['memories-3'])
    expect(slugPathOf(t, 'f1')).toEqual(['memories-2'])
    expect(slugPathOf(t, 'x1')).toEqual(['memories-3'])
    expect(slugPathOf(t, 'm1')).toEqual(['memories', 'memories']) // application segment, then its own name
    expect(hashOf(t, 'd1')).toBe('#memories-2/')
    expect(hashOf(t, 'm1')).toBe('#memories/memories')
    for (const n of t) expect(resolveHash(t, hashOf(t, n.id)), n.id).toBe(n.id)
    // the bare segment names nothing: it is the application's
    expect(resolveHash(t, '#memories')).toBe(false)
    expect(resolveHash(t, '#memories/')).toBe(false)
  })

  it('skips a numbered segment a sibling holds literally: `Memories 2` keeps `memories-2`', () => {
    const t = [node('d1', 'Memories', null, FOLDER_KIND, 1), node('d2', 'Memories 2', null, FOLDER_KIND, 2)]
    expect(slugPathOf(t, 'd2')).toEqual(['memories-2'])
    expect(slugPathOf(t, 'd1')).toEqual(['memories-3'])
  })

  it('does not reserve the slug inside a Memory, where a chapter has no application segment', () => {
    const t = [node('m', 'Rom', null, MEMORY_KIND, 1), node('c', 'Memories', 'm', CHAPTER_KIND, 2)]
    expect(slugPathOf(t, 'c')).toEqual(['memories', 'rom', 'memories'])
    expect(resolveHash(t, '#memories/rom/memories')).toBe('c')
  })

  it('numbers within a class only: equal Memories, equal folders and a file with a Memory\'s name', () => {
    const t = [
      node('m1', 'Rom', null, MEMORY_KIND, 1),
      node('m2', 'Rom', null, MEMORY_KIND, 2),
      node('d1', 'Rom', null, FOLDER_KIND, 3),
      node('d2', 'Rom', null, FOLDER_KIND, 4),
      node('f1', 'rom', null, FILE, 5),
    ]
    expect(t.map((n) => hashOf(t, n.id))).toEqual([
      '#memories/rom', '#memories/rom-2', '#rom/', '#rom-2/', '#rom',
    ])
    for (const n of t) expect(resolveHash(t, hashOf(t, n.id))).toBe(n.id)
  })

  it('walks a deep path through folders, a drop box, its guest folder and a Memory with a chapter', () => {
    const t = [
      node('a', 'A', null),
      node('b', 'B', 'a'),
      node('box', 'Box', 'b', DROPBOX_KIND, 1),
      node('anna', 'Anna', 'box', FOLDER_KIND, 2),
      node('deep', 'Deep', 'anna', FOLDER_KIND, 3),
      node('rom', 'Rom', 'b', MEMORY_KIND, 4),
      node('tag', 'Tag', 'rom', CHAPTER_KIND, 5),
    ]
    expect(hashOf(t, 'deep', '@familie')).toBe('#%40familie/a/b/box/anna/deep/')
    expect(hashOf(t, 'tag', '~kevin')).toBe('#~kevin/a/b/memories/rom/tag')
    for (const scope of [TENANT_SCOPE, '~kevin', '@familie']) {
      for (const n of t) expect(resolveHash(t, hashOf(t, n.id, scope)), `${n.id} in ${scope}`).toBe(n.id)
    }
    // a drop box has children although it is no folder: its path continues
    expect(resolveHash(t, '#a/b/box/anna')).toBe('anna')
  })

  it('reads a drop box and a folder of one name by backtracking, so the guest\'s folder stays reachable', () => {
    const t = [
      node('box', 'Box', null, DROPBOX_KIND, 1),
      node('dir', 'Box', null, FOLDER_KIND, 2),
      node('anna', 'Anna', 'box', FOLDER_KIND, 3),
      node('inner', 'Inner', 'dir', FOLDER_KIND, 4),
    ]
    expect(resolveHash(t, '#box')).toBe('box')
    expect(resolveHash(t, '#box/')).toBe('dir')
    expect(resolveHash(t, '#box/anna/')).toBe('anna')
    expect(resolveHash(t, '#box/inner/')).toBe('inner')
  })

  it('prefers the folder for a segment with more behind it when the drop box could be read too', () => {
    const t = [
      node('box', 'Box', null, DROPBOX_KIND, 1),
      node('dir', 'Box', null, FOLDER_KIND, 2),
      node('box-x', 'X', 'box', FOLDER_KIND, 3),
      node('dir-x', 'X', 'dir', FOLDER_KIND, 4),
    ]
    expect(resolveHash(t, '#box/x/')).toBe('dir-x') // the folder, as `…/a-b/x` always reads `a-b` as one
    expect(resolveHash(t, '#box/x')).toBe('dir-x')
  })

  it('numbers a kind nobody claimed among the plain names of Depot, so two nodes never build one URL', () => {
    const t = [node('u', 'Thing', null, 'FUTURE', 1), node('f', 'Thing', null, FILE, 2), node('d', 'Thing', null, FOLDER_KIND, 3)]
    expect(t.map((n) => hashOf(t, n.id))).toEqual(['#thing', '#thing-2', '#thing/'])
    for (const n of t) expect(resolveHash(t, hashOf(t, n.id))).toBe(n.id)
  })
})

// A person's profile is a fragment route (ADR 0083): token-less, `people` is the platform's word at a scope root.
describe('a person\'s profile: #people/<userId>', () => {
  const FILE = 'FILE'
  const hashOf = (t: TreeNode[], id: string) => hashForNode(t, id, TENANT_SCOPE)
  const resolveHash = (t: TreeNode[], hash: string) => {
    const { segments, folder } = parseHash(hash)
    return resolveSlugPath(t, segments, folder)
  }
  const UID = '3f2c9e1a-0000-4000-8000-00000000abcd'

  it('is built by personHash and read back by parseHash, in either spelling of the leading slash', () => {
    expect(personHash(UID)).toBe(`#people/${UID}`)
    expect(parseHash(personHash(UID))).toEqual({ scope: TENANT_SCOPE, segments: [], folder: false, person: UID })
    expect(parseHash(`#/people/${UID}`).person).toBe(UID)
    expect(parseHash(personHash('a b/c'))).toEqual({ scope: TENANT_SCOPE, segments: [], folder: false, person: 'a b/c' })
  })

  it('is no path of the tree: other shapes stay plain segments, and a foreign ~username stays a personal-scope token', () => {
    expect(parseHash('#people').person).toBeUndefined()
    expect(parseHash(`#people/${UID}/more`).person).toBeUndefined()
    expect(parseHash(`#~kevin/people/${UID}`)).toEqual({ scope: '~kevin', segments: ['people', UID], folder: false })
    expect(parseHash('#~anna')).toEqual({ scope: '~anna', segments: [], folder: false })
  })

  it('reserves the word at a scope root: a folder, file or drop box called "People" is numbered, never "people"', () => {
    const t = [
      node('d1', 'People', null, FOLDER_KIND, 1),
      node('d2', 'people', null, FOLDER_KIND, 2),
      node('f1', 'PEOPLE', null, FILE, 3),
      node('m1', 'People', null, MEMORY_KIND, 4),
    ]
    expect(slugPathOf(t, 'd1')).toEqual(['people-2'])
    expect(slugPathOf(t, 'd2')).toEqual(['people-3'])
    expect(slugPathOf(t, 'f1')).toEqual(['people-2'])
    expect(slugPathOf(t, 'm1')).toEqual(['memories', 'people']) // a Memory carries its application segment first
    for (const n of t) {
      const hash = hashOf(t, n.id)
      expect(parseHash(hash).person, hash).toBeUndefined() // no node ever lands on the profile route
      expect(resolveHash(t, hash), hash).toBe(n.id)
    }
    expect(PEOPLE_SEGMENT).toBe('people')
  })

  it('does not reserve it below the root: a folder "People" inside a folder keeps its name', () => {
    const t = [node('f', 'Reisen', null), node('p', 'People', 'f')]
    expect(slugPathOf(t, 'p')).toEqual(['reisen', 'people'])
  })
})
