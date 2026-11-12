// @vitest-environment happy-dom
// "Back to where I was": a view that goes UP the tree (a breadcrumb, the browser's Back) brings the child it came from back
// into sight and focus, instead of opening the level at its top. This file pins the pure half (which child), the host's
// half (when a move is one up) and the DOM half (scroll only when out of sight, focus without a scroll of its own).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { childTowards, useReturnTo, bringIntoView } from './returnTo'
import { FOLDER_KIND } from './kind'
import { treeNode, memberTreeOf } from '../../test/models'
import type { TreeNode } from './tree'

function node(id: string, parentId: string | null, kind = FOLDER_KIND): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
}
// top ─┬─ mid ── leaf
//      └─ side
// other
const NODES = [node('top', null), node('mid', 'top'), node('leaf', 'mid', 'ALPHA'), node('side', 'top'), node('other', null)]
const tree = memberTreeOf(() => NODES)

describe('childTowards', () => {
  it('one level up: the node it came from', () => {
    expect(childTowards(tree, 'leaf', 'mid')).toBe('leaf')
  })
  it('several levels up (a crumb further up): the child of the target the old place lies in', () => {
    expect(childTowards(tree, 'leaf', 'top')).toBe('mid')
    expect(childTowards(tree, 'leaf', null), 'the scope root').toBe('top')
  })
  it('a step down, sideways, in place or from nowhere is no way up', () => {
    expect(childTowards(tree, 'top', 'mid'), 'down').toBeNull()
    expect(childTowards(tree, 'side', 'mid'), 'sideways').toBeNull()
    expect(childTowards(tree, 'other', 'top'), 'another branch').toBeNull()
    expect(childTowards(tree, 'mid', 'mid'), 'in place').toBeNull()
    expect(childTowards(tree, null, 'mid'), 'from the scope root').toBeNull()
    expect(childTowards(tree, 'gone', null), 'a node the tree no longer holds (deleted)').toBeNull()
  })
  it('a served cycle ends the walk', () => {
    const loop = memberTreeOf(() => [node('a', 'b'), node('b', 'a')])
    expect(childTowards(loop, 'a', 'x')).toBeNull()
  })
})

describe('useReturnTo', () => {
  function follow(start: string | null, startRealm = 'me') {
    const current = ref<string | null>(start)
    const realm = ref(startRealm)
    const scope = effectScope()
    const returnTo = scope.run(() => useReturnTo(() => current.value, () => tree, () => realm.value))!
    return { current, realm, returnTo, stop: () => scope.stop() }
  }

  it('answers the child after a move up, and nothing after a move down', () => {
    const f = follow('leaf')
    expect(f.returnTo.value, 'a first view came from nowhere').toBeNull()
    f.current.value = 'top'
    expect(f.returnTo.value, 'set with the move, before the level renders').toBe('mid')
    f.current.value = 'side'
    expect(f.returnTo.value).toBeNull()
    f.current.value = null
    expect(f.returnTo.value).toBe('top')
    f.stop()
  })

  it('a move into another scope is no way up, whatever the old tree still says', () => {
    const f = follow('leaf')
    f.current.value = null
    f.realm.value = 'tenant'
    expect(f.returnTo.value).toBeNull()
    f.stop()
  })

  it('a re-read that keeps the place (a live poll) leaves the answer alone', async () => {
    const f = follow('leaf')
    f.current.value = 'mid'
    f.current.value = 'mid'
    await nextTick()
    expect(f.returnTo.value).toBe('leaf')
    f.stop()
  })
})

describe('bringIntoView', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })
  function row(top: number, withButton = true): HTMLElement {
    const li = document.createElement('li')
    if (withButton) {
      const b = document.createElement('button')
      b.className = 'veyq-level-item-main'
      li.append(b)
    }
    document.body.append(li)
    vi.spyOn(li, 'getBoundingClientRect').mockReturnValue({ top, bottom: top + 60 } as DOMRect)
    li.scrollIntoView = vi.fn()
    return li
  }

  it('a row out of sight is centred, and its button takes the focus without a scroll of its own', () => {
    const li = row(window.innerHeight + 400)
    const focus = vi.spyOn(li.querySelector('button')!, 'focus')
    expect(bringIntoView(li)).toBe(true)
    expect(li.scrollIntoView).toHaveBeenCalledWith({ block: 'center' })
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    expect(document.activeElement).toBe(li.querySelector('button'))
  })

  it('a row already in sight stays where it is (the browser restored it, or it never left)', () => {
    const li = row(100)
    expect(bringIntoView(li)).toBe(true)
    expect(li.scrollIntoView).not.toHaveBeenCalled()
  })

  it('a row under the sticky top bar counts as out of sight', () => {
    const li = row(10)
    li.style.scrollMarginTop = '56px'
    bringIntoView(li)
    expect(li.scrollIntoView).toHaveBeenCalled()
  })

  it('a row with no button (one this client cannot show) is brought into sight, and the focus stays', () => {
    const li = row(-500, false)
    expect(bringIntoView(li)).toBe(false)
    expect(li.scrollIntoView).toHaveBeenCalled()
  })
})
