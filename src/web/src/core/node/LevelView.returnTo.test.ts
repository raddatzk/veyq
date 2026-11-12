// @vitest-environment happy-dom
// The level's half of "back to where I was" (`returnTo.ts`): the host names the child the view came up from, and the
// level brings that row back into sight and gives it the focus, unmarked (owner 2026-11-12: the row looks as it always
// does; only a keyboard's `:focus-visible` rings it) - once per arrival, never again on a re-render under the same place
// (a live poll).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})

import LevelView from './LevelView.vue'
import { createRegistry } from '../applications/registry'
import { childrenOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { TreeNode } from './tree'
import { setViewMode } from '../../shared/state/useViewMode'
import { realI18n } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf } from '../../test/models'
import { hostFor } from '../../test/nodeHost'
import { stubRows } from '../../test/stubRows'
import { rowOf } from '../../test/levelRows'
import { nothingOfItsOwnToRekey } from './rekey/hooks'
import { testKind } from '../../test/testApplication'

function node(id: string, kind: NodeKind, parentId: string | null): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
}
const TRIPS = node('Trips', FOLDER_KIND, null)
const tree = [TRIPS, node('Alps', FOLDER_KIND, 'Trips'), node('first', 'ALPHA', 'Trips'), node('last', 'ALPHA', 'Trips')]

beforeEach(() => {
  setViewMode('list')
  holder.reg = createRegistry()
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'images', list: { rows: stubRows('a').factory } })
})
let w: VueWrapper | null = null
afterEach(() => {
  w?.unmount()
  w = null
})

async function mountWith(returnTo: string | null) {
  const rec = hostFor(memberAccessOf())
  w = mount(LevelView, {
    props: { folder: TRIPS, level: childrenOf(tree, TRIPS.id), access: memberAccessOf(), tree: memberTreeOf(() => tree), returnTo },
    global: { plugins: [realI18n()], provide: rec.provide },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}
const mainOf = (title: string) => rowOf(w!, title).find('button.veyq-level-item-main').element as HTMLElement

describe('the level brings the row it was left through back', () => {
  it('on arrival: that row holds the focus, and looks as it does on any other arrival', async () => {
    await mountWith(null)
    const plain = rowOf(w!, 'last').classes()
    w!.unmount()
    await mountWith('last')
    expect(document.activeElement).toBe(mainOf('last'))
    expect(rowOf(w!, 'last').classes(), 'no mark of its own').toEqual(plain)
    expect(rowOf(w!, 'last').attributes('data-node-id')).toBe('last')
  })

  it('a folder row too (folders are their own group)', async () => {
    await mountWith('Alps')
    expect(document.activeElement).toBe(mainOf('Alps'))
  })

  it('without a returnTo the level opens as ever: no focus taken', async () => {
    await mountWith(null)
    expect(document.activeElement).toBe(document.body)
  })

  it('once per arrival: a re-render under the same place (a live poll) does not take the focus back', async () => {
    await mountWith('last')
    mainOf('first').focus()
    await w!.setProps({ liveTick: 1, level: childrenOf([...tree], TRIPS.id) })
    await flushPromises()
    expect(document.activeElement).toBe(mainOf('first'))
  })

  it('a new arrival in a reused level (folder to folder) brings its own row back', async () => {
    await mountWith(null)
    await w!.setProps({ returnTo: 'first' })
    await flushPromises()
    expect(document.activeElement).toBe(mainOf('first'))
  })

  it('a child the level does not show (deleted, filtered) is not looked for', async () => {
    await mountWith('gone')
    expect(document.activeElement).toBe(document.body)
  })
})
