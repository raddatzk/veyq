// @vitest-environment happy-dom
// The node tree's rules against contracts/platform/tree/v1-vectors.json: a level's order, the numbering of equal names, a
// name's sibling collision and the move's plan, every case through the web's adapter (`treeRules.ts`) over the platform's
// Kotlin (`@veyq/core-js/tree`) and the real registry
// (`src/test/setup.ts` registers the applications, as `main.ts` does), whose kind table must be the file's. The core
// records the vectors (its TreeVectorsTest's write mode, ADR 0155 § Consequences) and answers the same cases.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { registry } from '../applications/registry'
import { fileNameSlug } from '../../shared/naming'
import {
  collidingSibling, displayNamesOf, moveHereBlock, moveHereBlockOf, movePlaces, moveStart, moveTrail, orderLevel, planMove, type PlaceIntent,
} from './treeRules'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { memberTreeOf, treeNode } from '../../test/models'

const FILE = resolve(__dirname, '../../../../../contracts/platform/tree/v1-vectors.json')
const vectors = JSON.parse(readFileSync(FILE, 'utf8')) as any

type Entry = { id: string; name: string; createdAt: number | null }
const nodeOf = (e: Entry): TreeNode => treeNode({ id: e.id, name: e.name, createdAt: e.createdAt ?? 0 })

/** The registry's facts of each kind, as the core's `KindFacts` reads them: its route slug, its folding, its placement; by kind. */
function kindTable() {
  return [...registry.kinds()].sort().map((kind) => {
    const d = registry.kind(kind)
    const slug = d.supported ? d.slug : undefined
    if (slug !== undefined && slug !== fileNameSlug) throw new Error(`${kind} folds by a function no vector names`)
    const placement = d.supported ? d.placement : undefined
    return {
      kind,
      route: registry.routeOf(kind).slug,
      folding: slug === fileNameSlug ? 'FILE_NAME' : 'NAME',
      root: placement?.root ?? false,
      parents: [...(placement?.parents ?? [])],
    }
  })
}

/** A tree of the vectors as the session's `TreeSource`: a member's over every node, a guest's rooted at its link. */
function treeOf(name: string): { source: TreeSource<TreeNode>; byId: Map<string, TreeNode> } {
  const t = vectors.trees[name]
  const nodes: TreeNode[] = t.nodes.map((n: any) =>
    treeNode({ id: n.id, parentId: n.parentId, kind: n.kind, name: n.name, createdAt: n.createdAt ?? 0, shareIdentityId: n.identity ? `identity-${n.id}` : null }),
  )
  return { source: { ...memberTreeOf(() => nodes), rootId: t.rootId }, byId: new Map(nodes.map((n) => [n.id, n])) }
}

const collisionNodes = (c: any): TreeNode[] =>
  (typeof c.nodes === 'string' ? vectors.collisionNodes[c.nodes] : c.nodes).map((n: any) => treeNode({ id: n.id, kind: n.kind, name: n.name, parentId: n.parentId }))

const answers: Record<string, (c: any) => unknown> = {
  order: (c) => {
    return orderLevel(c.folders.map(nodeOf), c.others.map(nodeOf)).map((n) => n.id)
  },
  displayNames: (c) => Object.fromEntries(displayNamesOf(c.siblings.map(nodeOf), c.order)),
  collision: (c) => collidingSibling(collisionNodes(c), c.parentId, c.kind, c.name, c.self)?.id ?? null,
  moveBlock: (c) => {
    const { source, byId } = treeOf(c.tree)
    return moveHereBlock(source, c.nodes.map((id: string) => byId.get(id)!), c.mode as Access['mode'], c.at, c.intent as PlaceIntent)
  },
  moveBlockOf: (c) => {
    const { source, byId } = treeOf(c.tree)
    const r = moveHereBlockOf(source, c.nodes.map((id: string) => byId.get(id)!), c.mode as Access['mode'], c.at, c.intent as PlaceIntent)
    return r && { block: r.block, node: r.node?.id ?? null }
  },
  planMove: (c) => {
    const { source, byId } = treeOf(c.tree)
    const { send, leftOut } = planMove(source, c.nodes.map((id: string) => byId.get(id)!), c.to)
    return {
      send: send.map((n) => n.id),
      leftOut: leftOut.map((l) => (l.reason === 'nameTaken' ? { node: l.node.id, reason: l.reason, sibling: l.sibling.id } : { node: l.node.id, reason: l.reason })),
    }
  },
  movePlaces: (c) => {
    const { source, byId } = treeOf(c.tree)
    return movePlaces(source, c.nodes.map((id: string) => byId.get(id)!), c.mode as Access['mode'], c.at).map((p) => ({ node: p.node.id, blocked: p.blocked }))
  },
  moveTrail: (c) => moveTrail(treeOf(c.tree).source, c.at),
  moveStart: (c) => {
    const { source, byId } = treeOf(c.tree)
    return moveStart(source, c.nodes.map((id: string) => byId.get(id)!))
  },
}

describe('the tree vectors (contracts/platform/tree/v1-vectors.json)', () => {
  it('the registry\'s kinds are the vectors\' kind table', () => {
    expect(kindTable()).toEqual(vectors.kinds)
  })

  for (const [section, answer] of Object.entries(answers)) {
    describe(section, () => {
      ;(vectors[section] as any[]).forEach((c, i) => {
        it(`#${i} ${c.rule}`, () => expect(answer(c)).toEqual(c.expected))
      })
    })
  }

  it('the file has its schema\'s shape (v1-vectors.schema.json): its sections, each case\'s fields', () => {
    const schema = JSON.parse(readFileSync(FILE.replace(/\.json$/, '.schema.json'), 'utf8')) as any
    expect(Object.keys(vectors).sort()).toEqual([...schema.required].sort())
    for (const [section, spec] of Object.entries<any>(schema.properties)) {
      const ref = spec.items?.$ref as string | undefined
      const item = ref ? schema.$defs[ref.split('/').pop()!] : spec.items
      if (!item?.required || !Array.isArray(vectors[section])) continue
      for (const c of vectors[section]) expect(Object.keys(c).sort(), `${section}: ${c.rule ?? c.kind}`).toEqual([...item.required].sort())
    }
  })

  it('every case is recorded', () => {
    for (const section of Object.keys(answers)) for (const c of vectors[section]) expect('expected' in c, `${section}: ${c.rule}`).toBe(true)
  })
})

