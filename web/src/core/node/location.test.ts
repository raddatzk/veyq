import { describe, it, expect } from 'vitest'
import {
  parseHash,
  buildHash,
  resolveSlugPath,
  slugPathOf,
  hashForNode,
  compareNodeIds,
  TENANT_SCOPE,
} from './location'
import { breadcrumbOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import { MEMORY_KIND } from '../../features/memory/kinds'
import { DROPBOX_KIND } from '../../features/files/kinds'
import type { TreeNode } from './tree'

// A concrete personal token — any `~<username>` parses as personal (ADR 0023); `~me` is the fallback.
const PERSONAL_SCOPE = '~me'

const node = (id: string, name: string, parentId: string | null, kind: NodeKind = FOLDER_KIND): TreeNode => ({
  id, name, parentId, kind, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0,
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
// the slug path tells them apart (`segmentsOf`): `anna`, `anna-2`, `anna-3` in node-id order, built and resolved by one
// rule. Before it, both folders built `…/anna` and that path opened whichever the tree list held first (inbox #372).
describe('equal sibling slugs', () => {
  const BOX = 'b0000000-0000-4000-8000-000000000000'
  const ANNA_1 = 'a1000000-0000-4000-8000-000000000000'
  const ANNA_2 = 'a2000000-0000-4000-8000-000000000000'
  const ANNA_3 = 'a3000000-0000-4000-8000-000000000000'
  const identityFolder = (id: string, name: string): TreeNode => ({ ...node(id, name, BOX), shareIdentityId: `i-${id}` })
  const box = node(BOX, 'Uploads', null, DROPBOX_KIND)
  // Listed against id order on purpose: neither direction may hinge on the list order.
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

  it('gives the first by node id the plain slug and the second `-2`, whatever the list order', () => {
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

  it('keeps every earlier path when a sibling whose id sorts later arrives; one that sorts earlier takes the plain slug', () => {
    const later = [...dropTree, identityFolder(ANNA_3, 'Anna')]
    expect([ANNA_1, ANNA_2].map((id) => lastSegment(later, id))).toEqual(['anna', 'anna-2'])
    // Node ids are random (UUIDv4, ADR 0072), so a later-CREATED sibling may sort first: the order is id order, stated.
    const EARLIER = 'a0000000-0000-4000-8000-000000000000'
    const earlier = [...dropTree, identityFolder(EARLIER, 'Anna')]
    expect([EARLIER, ANNA_1, ANNA_2].map((id) => lastSegment(earlier, id))).toEqual(['anna', 'anna-2', 'anna-3'])
  })

  it('never hands out a segment a real sibling holds as its own name: `Anna 2` keeps `anna-2`, the second Anna skips it', () => {
    // Its id sorts first AND last, so the outcome hinges on the name, not on where the literal sits in id order.
    for (const literalId of ['00000000-0000-4000-8000-000000000000', 'ff000000-0000-4000-8000-000000000000']) {
      const t = [...dropTree, identityFolder(ANNA_3, 'Anna'), identityFolder(literalId, 'Anna 2')]
      expect(lastSegment(t, literalId)).toBe('anna-2')
      expect([ANNA_1, ANNA_2, ANNA_3].map((id) => lastSegment(t, id))).toEqual(['anna', 'anna-3', 'anna-4'])
      expectUnambiguous(t)
    }
    // Two literal `Anna 2`s are their own group: `anna-2` and `anna-2-2`, never meeting the Annas' `anna-<n>`.
    const t = [...dropTree, identityFolder('01000000-0000-4000-8000-000000000000', 'Anna 2'), identityFolder('02000000-0000-4000-8000-000000000000', 'anna-2')]
    expect(lastSegment(t, '02000000-0000-4000-8000-000000000000')).toBe('anna-2-2')
    expect(lastSegment(t, ANNA_2)).toBe('anna-3')
    expectUnambiguous(t)
  })

  it('is kind-neutral: a file and a folder of one URL segment are told apart the same way, a unique name keeps its slug', () => {
    // A folder `a-b` and a file `a.b` fold to different uniqueness slugs (ADR 0080, both may exist) but to one URL segment.
    const FOLDER_AB = 'e1000000-0000-4000-8000-000000000000'
    const FILE_AB = 'e0000000-0000-4000-8000-000000000000'
    const t = [...tree, node(FOLDER_AB, 'a-b', 'f1'), node(FILE_AB, 'a.b', 'f1', 'FILE')]
    expect(lastSegment(t, FILE_AB)).toBe('a-b')
    expect(lastSegment(t, FOLDER_AB)).toBe('a-b-2')
    expect(slugPathOf(t, 'm1')).toEqual(['reisen-2026', 'rom'])
    expectUnambiguous(t)
  })

  it('orders by node id in code units, not by locale', () => {
    // `localeCompare` puts `a…` before `B…`; code units put `B` (0x42) first. The rule is the code-unit one.
    const t = [box, identityFolder('a9000000', 'Anna'), identityFolder('B9000000', 'Anna')]
    expect(lastSegment(t, 'B9000000')).toBe('anna')
    expect(lastSegment(t, 'a9000000')).toBe('anna-2')
    expect(compareNodeIds({ id: 'B' }, { id: 'a' })).toBeLessThan(0)
    expect(compareNodeIds({ id: 'a' }, { id: 'a' })).toBe(0)
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
})
