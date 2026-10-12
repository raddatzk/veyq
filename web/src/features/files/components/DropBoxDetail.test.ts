// @vitest-environment happy-dom
// An opened drop box (cr-files-application F3): its guests' folders from the tree, a repeated guest name numbered at
// display time (never stored), each folder's file count; a folder opens, a folder is deleted after a confirm, and the
// drop box's own share and delete go to the host.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const { showUndo } = vi.hoisted(() => ({ showUndo: vi.fn() }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo, dismissUndo: vi.fn() }) }))
vi.mock('../../../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../core/node/tree')>()),
  restoreNode: vi.fn(async () => {}),
  deleteNode: vi.fn(async () => {}),
}))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})

import DropBoxDetail from './DropBoxDetail.vue'
import type { NodeDetailProps } from '../../../core/applications/registry'
import { deleteNode, restoreNode, type TreeNode } from '../../../core/node/tree'
import { treeStore, loadTree } from '../../../core/node/treeStore'
import { FOLDER_KIND } from '../../../core/node/kind'
import { slugPathOf } from '../../../core/node/location'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { toggleFavorite } from '../../../core/favorites/favoritesStore'
import { DROPBOX_KIND, FILE_KIND } from '../kinds'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const node = (id: string, kind: string, name: string, parentId: string | null, over: Partial<TreeNode> = {}): TreeNode => treeNode({
  id, kind, name, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0, ...over,
})
const BOX = node('b0000000-0000-4000-8000-000000000000', DROPBOX_KIND, 'Einsendungen', null)
// The Annas' ids sort against the order the server made their folders in, so the numbering below is the creation order's.
const ANNA_1 = node('a2000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Anna', BOX.id, { shareIdentityId: 'i1', createdAt: 1_000 })
const ANNA_2 = node('a1000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Anna', BOX.id, { shareIdentityId: 'i2', createdAt: 2_000 })
const BEN = node('c0000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Ben', BOX.id, { shareIdentityId: 'i3', createdAt: 3_000 })

/** `publicLinks`: the instance offers public links (the share button's capability). */
function mountDetail(publicLinks = true) {
  return mount(DropBoxDetail, {
    props: {
      node: BOX, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), contentKey: null, children: { byKind: {}, folders: [] } as unknown as NodeDetailProps["children"],
      access: memberAccessOf({ scopeId: SCOPE, publicLinks }), tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
    },
    global: { plugins: [realI18n()] },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  treeStore.nodes = [
    BOX, BEN, ANNA_2, ANNA_1,
    node('f1000000-0000-4000-8000-000000000000', FILE_KIND, 'a.pdf', ANNA_1.id),
    node('f2000000-0000-4000-8000-000000000000', FILE_KIND, 'b.pdf', ANNA_1.id),
    node('f3000000-0000-4000-8000-000000000000', FILE_KIND, 'c.pdf', BEN.id),
    node('d0000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Elsewhere', null),
  ]
})

describe('DropBoxDetail', () => {
  it('core\'s heart is in the drop box\'s header, like every kind\'s: a real click toggles THIS drop box in the access\'s scope', async () => {
    const w = mountDetail()
    const heart = w.find('.veyq-node-header-actions button[data-action="favorite"]')
    expect(heart.attributes('aria-label')).toBe(t('node.favorite'))
    await heart.trigger('click')
    expect(toggleFavorite).toHaveBeenCalledWith(BOX.id, SCOPE)
  })

  it('lists the guests’ folders, a repeated name numbered in creation order, each with its file count', () => {
    const w = mountDetail()
    const rows = w.findAll('.veyq-row-title').map((e) => e.text())
    expect(rows).toEqual(['Anna', 'Anna (2)', 'Ben'])
    expect(w.findAll('.veyq-row-subtitle').map((e) => e.text())).toEqual(['2 files', 'No files', '1 file'])
  })

  it('numbers equal names in the order their URL segments are suffixed in: "Anna (2)" opens the folder at `anna-2`', async () => {
    const w = mountDetail()
    const pairs: [string, string[]][] = []
    for (const row of w.findAll('.dropbox-row-open')) {
      await row.trigger('click')
      const opened = (w.emitted('openNode') as TreeNode[][]).at(-1)![0]
      pairs.push([row.find('.veyq-row-title').text(), slugPathOf(treeStore.nodes, opened.id)])
    }
    expect(pairs).toEqual([
      ['Anna', ['einsendungen', 'anna']],
      ['Anna (2)', ['einsendungen', 'anna-2']],
      ['Ben', ['einsendungen', 'ben']],
    ])
  })

  // inbox #358 / ADR 0080 decision 11: a member moves a guest's folder OUT (the host's core move dialog asks first and
  // offers no drop box) and moves the drop box itself from its card.
  it('hands the move of a guest\'s folder out, and of the drop box, to the host', async () => {
    const w = mountDetail()
    await w.find(`button[aria-label="${t('files.dropBox.moveFolder', { name: 'Anna (2)' })}"]`).trigger('click')
    await w.find(`.veyq-node-header button[aria-label="${t('node.move.action')}"]`).trigger('click')
    expect(w.emitted('move')).toEqual([[ANNA_2], [BOX]])
  })

  it('opens a folder, and hands share and delete of the drop box to the host', async () => {
    const w = mountDetail()
    await w.find('button[aria-label="Open Anna (2)"]').trigger('click')
    expect(w.emitted('openNode')).toEqual([[ANNA_2]])
    // inbox #385 (b): both sit on core's header card, the one a folder opens with — the drop box's icon and name on it
    const card = w.find('.veyq-node-header')
    expect(card.find('.veyq-node-header-title').text()).toBe(BOX.name)
    expect(card.find('.pi-inbox').exists()).toBe(true)
    await card.find('button[aria-label="Share link"]').trigger('click')
    await card.find('button[aria-label="Delete drop box"]').trigger('click')
    expect(w.emitted('share')).toHaveLength(1)
    expect(w.emitted('delete')).toHaveLength(1)
    // control: no share offered where the host says the kind cannot be shared
    expect(mountDetail(false).find('button[aria-label="Share link"]').exists()).toBe(false)
  })

  it('deletes a guest’s folder after a confirm, and nothing without one', async () => {
    const w = mountDetail()
    await w.find('button[aria-label="Delete Ben"]').trigger('click')
    expect(confirmRequest.value?.message).toContain('Ben')
    settleConfirm(false)
    await flushPromises()
    expect(deleteNode).not.toHaveBeenCalled()
    await w.find('button[aria-label="Delete Ben"]').trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, BEN.id)
    expect(loadTree).toHaveBeenCalled()
    // inbox #385 (c): said, with an undo that restores the folder through core's trash restore
    expect(showUndo).toHaveBeenCalledOnce()
    expect(showUndo.mock.calls[0][0].summary).toBe(t('node.trash.moved', { name: 'Ben' }))
    showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(vi.mocked(restoreNode).mock.calls.map((c) => [c[0], (c[3] as TreeNode).id])).toEqual([[SCOPE, BEN.id]])
  })
})
