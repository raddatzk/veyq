// @vitest-environment happy-dom
// Drag and drop's own rules (inbox #421, `nodeDrag.ts`) over the REAL registry (Memories and Depot, filled by
// `src/test/setup.ts`): which places take a drag - exactly where the move dialog's "Move here" is enabled (`move.ts`
// `moveHereBlockOf`) -, that a drag of nodes is told apart from one of files by its own type, that a place that refuses is
// not taken (`dropEffect` none) and a drop on one that takes it hands the nodes on, and the breadcrumb's targets.
import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  NODE_DRAG_TYPE, activeNodeDrag, crumbDropTarget, dropOnNodeTarget, dropTakes, endNodeDrag, isNodeDrag, overNodeTarget, startNodeDrag,
  type NodeDrag,
} from './nodeDrag'
import { moveHereBlockOf } from './move'
import { FOLDER_KIND } from './kind'
import type { TreeNode } from './tree'
import type { VeyqMenuItem } from '../../shared/ui/menu'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import { memberTreeOf, treeNode } from '../../test/models'

const n = (id: string, kind: string, parentId: string | null, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name: id, parentId, ...over })
// (root) ─ Reisen/ ─ Rom/ ─ Forum/
//                  ─ Neapel/, bericht.pdf
//        ─ Einsendungen (drop box) ─ Anna/ (a guest's identity folder)
const NODES = [
  n('reisen', FOLDER_KIND, null), n('rom', FOLDER_KIND, 'reisen'), n('forum', FOLDER_KIND, 'rom'), n('neapel', FOLDER_KIND, 'reisen'),
  n('file', FILE_KIND, 'reisen'),
  n('box', DROPBOX_KIND, null), n('anna', FOLDER_KIND, 'box', { shareIdentityId: 'id-anna' }),
]
const tree = memberTreeOf(() => NODES)
const byId = (id: string) => NODES.find((x) => x.id === id)!

/** A drag event as the browser hands it: its types, and a `dataTransfer` that records what is set on it. */
function dragEvent(types: string[]) {
  const data = {
    types: [...types],
    dropEffect: 'unset',
    effectAllowed: 'unset',
    set: new Map<string, string>(),
    setData(type: string, value: string) {
      this.set.set(type, value)
      this.types.push(type)
    },
    setDragImage: vi.fn(),
  }
  const e = { dataTransfer: data, preventDefault: vi.fn(), stopPropagation: vi.fn() }
  return e as typeof e & DragEvent
}
function dragOf(accepts: (to: string | null) => boolean) {
  const dropped: (string | null)[] = []
  const drag: NodeDrag = { ids: new Set(['file']), accepts, drop: (to) => dropped.push(to) }
  return { drag, dropped }
}

afterEach(() => endNodeDrag())

describe('dropTakes: the move dialog\'s "Move here", no rule of its own', () => {
  it('a sibling folder and an ancestor take them; the place they are in, themselves and anything inside them do not', () => {
    const rom = [byId('rom')]
    expect(dropTakes(tree, rom, 'member', 'neapel'), 'a sibling folder').toBe(true)
    expect(dropTakes(tree, rom, 'member', null), 'the scope root, an ancestor').toBe(true)
    expect(dropTakes(tree, rom, 'member', 'reisen'), 'where it is now: no target, nothing would move').toBe(false)
    expect(dropTakes(tree, rom, 'member', 'rom'), 'itself').toBe(false)
    expect(dropTakes(tree, rom, 'member', 'forum'), 'its own descendant').toBe(false)
    expect(dropTakes(tree, rom, 'member', 'anna'), "a guest's identity folder").toBe(false)
    expect(dropTakes(tree, rom, 'member', 'box'), 'a drop box holds no folder').toBe(false)
  })

  it('is exactly the dialog\'s answer for one node and for several, at every place of the tree', () => {
    for (const moved of [['rom'], ['file'], ['rom', 'neapel', 'file'], ['box']]) {
      const nodes = moved.map(byId)
      for (const to of [null, ...NODES.map((x) => x.id)]) {
        for (const mode of ['member', 'guest'] as const) {
          expect(dropTakes(tree, nodes, mode, to), `${moved.join('+')} → ${to} as ${mode}`).toBe(moveHereBlockOf(tree, nodes, mode, to) === null)
        }
      }
    }
  })

  it('a guest drops only into a folder, never onto the scope root it holds no key of', () => {
    const file = [byId('file')]
    expect(dropTakes(tree, file, 'guest', 'rom')).toBe(true)
    expect(dropTakes(tree, file, 'guest', null)).toBe(false)
  })

  it('nothing dragged takes nowhere', () => {
    expect(dropTakes(tree, [], 'member', 'rom')).toBe(false)
  })
})

describe('a drag of nodes and a drag of files', () => {
  it('a drag of nodes carries its own type and its ids, as a move; several draw a picture that says how many', () => {
    const e = dragEvent([])
    startNodeDrag(e, { ids: new Set(['a', 'b']), accepts: () => true, drop: () => {} }, '2 items')
    expect(isNodeDrag(e)).toBe(true)
    expect(e.dataTransfer.set.get(NODE_DRAG_TYPE)).toBe('["a","b"]')
    expect(e.dataTransfer.effectAllowed).toBe('move')
    expect(e.dataTransfer.setDragImage).toHaveBeenCalledOnce()
    expect((e.dataTransfer.setDragImage.mock.calls[0][0] as HTMLElement).textContent).toBe('2 items')
    expect(activeNodeDrag()?.ids).toEqual(new Set(['a', 'b']))
  })

  it('one node draws the browser\'s own picture', () => {
    const e = dragEvent([])
    startNodeDrag(e, { ids: new Set(['a']), accepts: () => true, drop: () => {} })
    expect(e.dataTransfer.setDragImage).not.toHaveBeenCalled()
  })

  it('a drag of files from the operating system is no drag of nodes: no place takes it as one, and its drop is left alone', () => {
    startNodeDrag(dragEvent([]), dragOf(() => true).drag) // even while a drag of nodes is under way
    const files = dragEvent(['Files'])
    expect(isNodeDrag(files)).toBe(false)
    expect(overNodeTarget(files, 'rom')).toBe(false)
    expect(files.preventDefault).not.toHaveBeenCalled()
    expect(files.dataTransfer.dropEffect, 'not even told it is refused').toBe('unset')
    expect(dropOnNodeTarget(files, 'rom')).toBe(false)
    expect(files.stopPropagation, 'its drop reaches the level').not.toHaveBeenCalled()
  })
})

describe('a place under a drag of nodes', () => {
  it('one that takes it accepts it as a move; one that refuses says none and is not accepted', () => {
    const { drag } = dragOf((to) => to === 'rom')
    startNodeDrag(dragEvent([]), drag)
    const yes = dragEvent([NODE_DRAG_TYPE])
    expect(overNodeTarget(yes, 'rom')).toBe(true)
    expect(yes.preventDefault).toHaveBeenCalled()
    expect(yes.dataTransfer.dropEffect).toBe('move')
    const no = dragEvent([NODE_DRAG_TYPE])
    expect(overNodeTarget(no, 'neapel')).toBe(false)
    expect(no.preventDefault).not.toHaveBeenCalled()
    expect(no.dataTransfer.dropEffect).toBe('none')
  })

  it('a drop on one that takes it hands the place on and ends the drag; a drop on one that refuses moves nothing', () => {
    const { drag, dropped } = dragOf((to) => to === 'rom')
    startNodeDrag(dragEvent([]), drag)
    expect(dropOnNodeTarget(dragEvent([NODE_DRAG_TYPE]), 'neapel')).toBe(false)
    expect(dropped).toEqual([])
    startNodeDrag(dragEvent([]), drag)
    const e = dragEvent([NODE_DRAG_TYPE])
    expect(dropOnNodeTarget(e, 'rom')).toBe(true)
    expect(dropped).toEqual(['rom'])
    expect(e.stopPropagation, 'never reaches the level\'s upload').toHaveBeenCalled()
    expect(activeNodeDrag()).toBeNull()
  })

  it('with no drag of this page under way (another tab\'s), nothing takes it', () => {
    const e = dragEvent([NODE_DRAG_TYPE])
    expect(overNodeTarget(e, 'rom')).toBe(false)
    expect(e.dataTransfer.dropEffect).toBe('none')
  })
})

describe('crumbDropTarget: the breadcrumb\'s places', () => {
  it('an entry that stands for a place takes the drag where the drag says so; one that stands for none never', () => {
    const { drag, dropped } = dragOf((to) => to === null || to === 'reisen')
    const places = new Map<VeyqMenuItem, string | null>()
    const root: VeyqMenuItem = { label: 'Familie' }
    const reisen: VeyqMenuItem = { label: 'Reisen' }
    const rom: VeyqMenuItem = { label: 'Rom' }
    const other: VeyqMenuItem = { label: 'Profile' }
    places.set(root, null).set(reisen, 'reisen').set(rom, 'rom')
    const target = crumbDropTarget((item) => places.get(item))
    startNodeDrag(dragEvent([]), drag)
    expect(target.over(root, dragEvent([NODE_DRAG_TYPE])), 'the scope root (null) is a place').toBe(true)
    expect(target.over(reisen, dragEvent([NODE_DRAG_TYPE]))).toBe(true)
    expect(target.over(rom, dragEvent([NODE_DRAG_TYPE])), 'refused by the drag').toBe(false)
    expect(target.over(other, dragEvent([NODE_DRAG_TYPE])), 'no place').toBe(false)
    target.drop(root, dragEvent([NODE_DRAG_TYPE]))
    expect(dropped).toEqual([null])
  })
})
