import { describe, it, expect } from 'vitest'
import {
  parseHash,
  buildHash,
  resolveSlugPath,
  slugPathOf,
  hashForNode,
  TENANT_SCOPE,
} from './location'
import { NodeKind } from '../../api/generated/content'
import type { TreeNode } from './tree'

// A concrete personal token — any `~<username>` parses as personal (ADR 0023); `~me` is the fallback.
const PERSONAL_SCOPE = '~me'

const node = (id: string, name: string, parentId: string | null, kind: NodeKind = NodeKind.Folder): TreeNode => ({
  id, name, parentId, kind, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0,
})

// Reisen 2026 / Rom (a tree that lives in whichever scope is active)
const tree: TreeNode[] = [
  node('f1', 'Reisen 2026', null),
  node('m1', 'Rom', 'f1', NodeKind.Memory),
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
