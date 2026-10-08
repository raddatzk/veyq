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
  TENANT_SCOPE,
} from './location'
import { breadcrumbOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import { MEMORY_KIND } from '../../features/memory/kinds'
import { DROPBOX_KIND } from '../../features/files/kinds'
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
    expect(parseHash('')).toEqual({ scope: TENANT_SCOPE, segments: [] })
    expect(parseHash('#')).toEqual({ scope: TENANT_SCOPE, segments: [] })
    // A bare slug path with no leading token is the tenant root, not personal.
    expect(parseHash('#urlaube')).toEqual({ scope: TENANT_SCOPE, segments: ['urlaube'] })
    expect(parseHash('#reisen-2026/rom')).toEqual({ scope: TENANT_SCOPE, segments: ['reisen-2026', 'rom'] })
  })

  it('resolves the explicit `~me` token to the personal scope', () => {
    expect(parseHash('#~me')).toEqual({ scope: PERSONAL_SCOPE, segments: [] })
    expect(parseHash('#~me/reisen-2026/rom')).toEqual({ scope: PERSONAL_SCOPE, segments: ['reisen-2026', 'rom'] })
  })

  it('parses the (later) group token, leaving it unresolved', () => {
    expect(parseHash('#@familie/sommer')).toEqual({ scope: '@familie', segments: ['sommer'] })
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
    expect(parseHash(buildHash('@familie', ['sommer']))).toEqual({ scope: '@familie', segments: ['sommer'] })
  })
})

describe('slugPathOf + hashForNode', () => {
  it('round-trips a node to its fragment in the personal scope', () => {
    expect(slugPathOf(tree, 'm1')).toEqual(['reisen-2026', 'rom'])
    expect(hashForNode(tree, 'm1', PERSONAL_SCOPE)).toBe('#~me/reisen-2026/rom')
    expect(hashForNode(tree, null, PERSONAL_SCOPE)).toBe('#~me')
  })

  it('round-trips a node to its token-less fragment in the tenant scope', () => {
    expect(hashForNode(tree, 'm1', TENANT_SCOPE)).toBe('#reisen-2026/rom')
    expect(hashForNode(tree, null, TENANT_SCOPE)).toBe('')
    // parse ∘ build is identity for the tenant scope
    const { scope, segments } = parseHash(hashForNode(tree, 'm1', TENANT_SCOPE))
    expect(scope).toBe(TENANT_SCOPE)
    expect(resolveSlugPath(tree, segments)).toBe('m1')
  })
})

describe('resolveSlugPath', () => {
  it('walks the slug path to a node id (level by level)', () => {
    expect(resolveSlugPath(tree, [])).toBeNull() // root
    expect(resolveSlugPath(tree, ['reisen-2026'])).toBe('f1')
    expect(resolveSlugPath(tree, ['reisen-2026', 'rom'])).toBe('m1')
  })

  it('returns false when a segment does not match', () => {
    expect(resolveSlugPath(tree, ['nope'])).toBe(false)
    expect(resolveSlugPath(tree, ['reisen-2026', 'venedig'])).toBe(false)
  })

  it('round-trips with hashForNode for every node (both scopes)', () => {
    for (const scope of [PERSONAL_SCOPE, TENANT_SCOPE]) {
      for (const n of tree) {
        const { segments } = parseHash(hashForNode(tree, n.id, scope))
        expect(resolveSlugPath(tree, segments)).toBe(n.id)
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
      for (const n of list) expect(resolveSlugPath(list, parseHash(hashForNode(list, n.id, PERSONAL_SCOPE)).segments)).toBe(n.id)
    }
  }

  it('gives the first created the plain slug and the second `-2`, whatever the list order', () => {
    for (const list of [dropTree, [...dropTree].reverse()]) {
      expect(slugPathOf(list, ANNA_1)).toEqual(['uploads', 'anna'])
      expect(slugPathOf(list, ANNA_2)).toEqual(['uploads', 'anna-2'])
      expect(resolveSlugPath(list, ['uploads', 'anna'])).toBe(ANNA_1)
      expect(resolveSlugPath(list, ['uploads', 'anna-2'])).toBe(ANNA_2)
    }
    expect(hashForNode(dropTree, ANNA_2, TENANT_SCOPE)).toBe('#uploads/anna-2')
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

  it('is kind-neutral: a file and a folder of one URL segment are told apart the same way, a unique name keeps its slug', () => {
    // A folder `a-b` and a file `a.b` fold to different uniqueness slugs (ADR 0080, both may exist) but to one URL segment.
    const FOLDER_AB = 'e0000000-0000-4000-8000-000000000000'
    const FILE_AB = 'e1000000-0000-4000-8000-000000000000'
    const t = [...tree, node(FOLDER_AB, 'a-b', 'f1', FOLDER_KIND, 20), node(FILE_AB, 'a.b', 'f1', 'FILE', 10)]
    expect(lastSegment(t, FILE_AB)).toBe('a-b')
    expect(lastSegment(t, FOLDER_AB)).toBe('a-b-2')
    expect(slugPathOf(t, 'm1')).toEqual(['reisen-2026', 'rom'])
    expectUnambiguous(t)
  })

  it('carries the suffix into the breadcrumb links', () => {
    const t = [...dropTree, node('c2000000-0000-4000-8000-000000000000', 'Fotos', ANNA_2)]
    expect(breadcrumbOf(t, 'c2000000-0000-4000-8000-000000000000', 'Root', TENANT_SCOPE).map((c) => c.hash)).toEqual([
      '',
      '#uploads',
      '#uploads/anna-2',
      '#uploads/anna-2/fotos',
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
