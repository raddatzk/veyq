import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'tree.mjs'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const t = await import('@veyq/core-js/tree')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/tree/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/tree/v1-vectors.json'), 'utf8'))

// The node tree's façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers every case
// the core's TreeVectorsTest answers on the JVM and natively, over the file's own kind table.
const kinds = t.kindFacts(vectors.kinds)
const trees = Object.fromEntries(
  Object.entries(vectors.trees).map(([name, tree]: [string, any]) => [name, { facts: t.treeFacts(tree.nodes), top: tree.rootId, nodes: tree.nodes }]),
)
const treeOf = (c: any) => trees[c.tree]
const moved = (c: any) => c.nodes.map((id: string) => treeOf(c).nodes.find((n: any) => n.id === id))
const collisionNodes = (c: any) => (typeof c.nodes === 'string' ? vectors.collisionNodes[c.nodes] : c.nodes)
  .map((n: any) => ({ ...n, createdAt: null, identity: false }))

const answers: Record<string, (c: any) => unknown> = {
  order: (c) => [...t.orderLevel(c.folders, c.others)],
  displayNames: (c) => Object.fromEntries(t.displayNames(c.siblings, c.order === 'id')),
  collision: (c) => t.collidingSibling(kinds, t.treeFacts(collisionNodes(c)), c.parentId, c.kind, c.name, c.self) ?? null,
  moveBlock: (c) => t.moveHereBlock(kinds, treeOf(c).facts, moved(c), c.mode === 'guest', c.at, c.intent === 'copy') ?? null,
  moveBlockOf: (c) => t.moveHereBlockOf(kinds, treeOf(c).facts, moved(c), c.mode === 'guest', c.at, c.intent === 'copy') ?? null,
  planMove: (c) => {
    const plan = t.planMove(kinds, treeOf(c).facts, moved(c), c.to)
    return {
      send: [...plan.send],
      leftOut: plan.leftOut.map((l) => (l.sibling == null ? { node: l.node, reason: l.reason } : { node: l.node, reason: l.reason, sibling: l.sibling })),
    }
  },
  movePlaces: (c) => t.movePlaces(kinds, treeOf(c).facts, moved(c), c.mode === 'guest', c.at).map((p) => ({ node: p.node, blocked: p.blocked ?? null })),
  moveTrail: (c) => [...t.moveTrail(treeOf(c).facts, treeOf(c).top, c.at)].map((id) => id ?? null),
  moveStart: (c) => t.moveStart(treeOf(c).facts, treeOf(c).top, moved(c)) ?? null,
}

describe('the tree vectors through the façade (ADR 0155 decision 6)', () => {
  for (const [section, answer] of Object.entries(answers)) {
    describe(section, () => {
      ;(vectors[section] as any[]).forEach((c, i) => {
        it(`#${i} ${c.rule}`, () => expect(answer(c)).toEqual(c.expected))
      })
    })
  }
})

describe('the handles', () => {
  it('a tree handle answers the generation it was built from; the next list gets its own', () => {
    const first = [{ id: 'f', parentId: null, kind: 'FOLDER', name: 'Trips', createdAt: 1, identity: false }]
    const one = t.treeFacts(first)
    expect(t.collidingSibling(kinds, one, null, 'FOLDER', 'trips', null)).toBe('f')
    // the next generation: the folder renamed; the old handle still answers its own list
    const two = t.treeFacts([{ ...first[0], name: 'Reisen' }])
    expect(t.collidingSibling(kinds, two, null, 'FOLDER', 'trips', null)).toBeNull()
    expect(t.collidingSibling(kinds, two, null, 'FOLDER', 'reisen', null)).toBe('f')
    expect(t.collidingSibling(kinds, one, null, 'FOLDER', 'trips', null)).toBe('f')
  })

  it('the creation order: the instant, then the id in UTF-16 order', () => {
    expect(t.compareByCreation({ id: 'B', createdAt: 1 }, { id: 'a', createdAt: 1 })).toBeLessThan(0)
    expect(t.compareByCreation({ id: 'a', createdAt: 1 }, { id: 'B', createdAt: 2 })).toBeLessThan(0)
    expect(t.compareByCreation({ id: 'a', createdAt: 1 }, { id: 'a', createdAt: 1 })).toBe(0)
  })

  it('no shape the façade takes or returns carries a key, a sealed byte or a slug token', () => {
    const types = readFileSync(resolve(dist, 'veyq-mobile-composition.d.mts'), 'utf8')
    const shapes = ['KindShape', 'TreeNodeShape', 'EntryShape', 'CreationShape', 'MovedShape', 'BlockOfShape', 'LeftOutShape', 'MovePlanShape', 'PlaceShape']
    for (const shape of shapes) {
      const body = new RegExp(`interface ${shape} \\{([^}]*)\\}`).exec(types)?.[1]
      expect(body, `${shape} is declared`).toBeDefined()
      expect(body!).not.toMatch(/key|token|sealed|wrapped|encrypted/i)
    }
  })
})
