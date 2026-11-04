// @vitest-environment happy-dom
// An opened drop box (cr-files-application F3): its guests' folders from the tree, a repeated guest name numbered at
// display time (never stored), each folder's file count; a folder opens, a folder is deleted after a confirm, and the
// drop box's own share and delete go to the host.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'

const { showUndo } = vi.hoisted(() => ({ showUndo: vi.fn() }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
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

vi.mock('../../../core/blob/transport', async (orig) => ({ ...(await orig<object>()), openBlob: vi.fn(async () => new Uint8Array([1, 2, 3])) }))

import DropBoxDetail from './DropBoxDetail.vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { resetCovers } from '../../../core/node/cover/coverStore'
import { uploadedCover } from '../../../core/node/cover/coverModel'
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
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { chooseHeaderEntry, headerButtonIds, headerMenuIds } from '../../../test/nodeHeader'
import { mountNodePage } from '../../../test/nodePage'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const node = (id: string, kind: string, name: string, parentId: string | null, over: Partial<TreeNode> = {}): TreeNode => treeNode({
  id, kind, name, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0, ...over,
})
const BOX = node('b0000000-0000-4000-8000-000000000000', DROPBOX_KIND, 'Einsendungen', null)
// The Annas' ids sort against the order the server made their folders in, so the numbering below is the creation order's.
const ANNA_1 = node('a2000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Anna', BOX.id, { shareIdentityId: 'i1', createdAt: 1_000 })
const ANNA_2 = node('a1000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Anna', BOX.id, { shareIdentityId: 'i2', createdAt: 2_000 })
const BEN = node('c0000000-0000-4000-8000-000000000000', FOLDER_KIND, 'Ben', BOX.id, { shareIdentityId: 'i3', createdAt: 3_000 })

/** `dropBox`: the instance offers drop-box links (`core.public-shares.drop-box`, the share button's capability); the READ
 *  leaf `publicShares` stays on or off on its own (`publicShares`). */
function mountDetail(dropBox = true, publicShares = true) {
  return mountNodePage(DropBoxDetail, {
    props: {
      node: BOX, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), contentKey: null, children: { byKind: {}, folders: [] } as unknown as NodeDetailProps["children"],
      access: memberAccessOf({ scopeId: SCOPE, publicSharesDropBox: dropBox, publicShares }), tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
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

  it('core\'s Download is in the drop box\'s header (cr-download-is-the-export Decision 7): a real click asks the host to download it', async () => {
    // a member whose keys are open (core's `canDownload`); with them locked, none
    expect(mountDetail().find('.veyq-node-header-actions button[data-action="download"]').exists()).toBe(false)
    const epochs = epochsOf(new Uint8Array(32).fill(4))
    const w = mountNodePage(DropBoxDetail, {
      props: {
        node: BOX, scopeId: SCOPE, epochs, contentKey: null, children: { byKind: {}, folders: [] } as unknown as NodeDetailProps['children'],
        access: memberAccessOf({ scopeId: SCOPE, epochs }), tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
      },
      global: { plugins: [realI18n()] },
    })
    const action = w.find('.veyq-node-header-actions button[data-action="download"]')
    expect(action.attributes('aria-label')).toBe(t('export.action'))
    await action.trigger('click')
    expect(w.calls.download).toEqual([[BOX]])
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
      const opened = (w.calls.open as TreeNode[][]).at(-1)![0]
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
    await w.find(`button[aria-label="${t('depot.dropBox.moveFolder', { name: 'Anna (2)' })}"]`).trigger('click')
    await chooseHeaderEntry(w, 'move')
    expect(w.calls.move).toEqual([[ANNA_2], [BOX]])
  })

  it('a drop box\'s header in its priority (ADR 0110): favorite, share, download; move, copy, delete in the ⋯ - no rename, no cover entry', async () => {
    const epochs = epochsOf(new Uint8Array(32).fill(4))
    const w = mountNodePage(DropBoxDetail, {
      props: {
        node: BOX, scopeId: SCOPE, epochs, contentKey: null, children: { byKind: {}, folders: [] } as unknown as NodeDetailProps['children'],
        access: memberAccessOf({ scopeId: SCOPE, publicSharesDropBox: true, publicShares: true, epochs }), tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
      },
      global: { plugins: [realI18n()] },
    })
    await flushPromises()
    expect(headerButtonIds(w)).toEqual(['favorite', 'share', 'download'])
    expect(await headerMenuIds(w)).toEqual(['move', 'copy', 'delete'])
    // A drop box is never renamed here: its name is plain text.
    expect(w.find('h1.veyq-node-header-title').text()).toBe(BOX.name)
    expect(w.find('button[data-testid="node-header-rename"]').exists()).toBe(false)
  })

  it('opens a folder, and hands share and delete of the drop box to the host', async () => {
    const w = mountDetail()
    await w.find('button[aria-label="Open Anna (2)"]').trigger('click')
    expect(w.calls.open).toEqual([[ANNA_2]])
    // inbox #385 (b): both sit on core's header card, the one a folder opens with — the drop box's icon and name on it
    const card = w.find('.veyq-node-header')
    expect(card.find('.veyq-node-header-title').text()).toBe(BOX.name)
    expect(card.findComponent(VeyqIcon).props('name')).toBe('inbox')
    await card.find('button[aria-label="Share link"]').trigger('click')
    expect(card.find('button[aria-label="Delete drop box"]').exists(), 'delete is never a button (ADR 0110)').toBe(false)
    expect((await chooseHeaderEntry(w, 'delete')).textContent?.trim()).toBe('Delete drop box')
    // the offer is the rights answer's: the upload role alone (a drop box's dialog mints that one role anyway)
    expect(w.calls.share).toEqual([[BOX, { view: false, contribute: false, upload: true }]])
    expect(w.calls.delete).toEqual([[BOX]])

    // control: no share offered where the host says the kind cannot be shared (the header shows what the page contributed
    // once it has rendered: a tick after the mount)
    const unshareable = mountDetail(false)
    await flushPromises()
    expect(unshareable.find('button[aria-label="Share link"]').exists()).toBe(false)
    // …and the READ leaf is not the drop box's: public shares off alone keeps the WRITE_ONLY links manageable
    const readOff = mountDetail(true, false)
    await flushPromises()
    expect(readOff.find('button[aria-label="Share link"]').exists()).toBe(true)
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

// A drop box carries a cover (inbox #411): core's action in its header, the image its leading visual.
describe('DropBoxDetail - its cover', () => {
  const KEY = v1.randomBytes(32)
  const withCover = (): TreeNode => node(BOX.id, DROPBOX_KIND, 'Einsendungen', null, {
    metaRevision: 2,
    wrappedMeta: b64std(v1.sealField({ purpose: 'node-meta', scopeId: SCOPE, nodeId: BOX.id }, KEY, new TextEncoder().encode(JSON.stringify({
      v: 1, cover: uploadedCover({ blobId: 'b-1', chunkCount: 1, byteSize: 300, wrappedBlobKey: 'd3JhcA==', ownerId: BOX.id }),
    })))),
  })
  const mountWith = (box: TreeNode, over: Partial<ReturnType<typeof memberAccessOf>> = {}) => mountNodePage(DropBoxDetail, {
    props: {
      node: box, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), contentKey: null, children: { byKind: {}, folders: [] } as unknown as NodeDetailProps['children'],
      access: memberAccessOf({ scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), ...over }),
      tree: { ...memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }), keyOf: () => KEY },
    },
    global: { plugins: [realI18n()] },
  })
  beforeEach(() => {
    resetCovers()
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:box-cover'), revokeObjectURL: vi.fn() })
  })

  it('a writer with the keys sets the cover from the header\'s visual: the empty frame with the inbox icon says Set, a cover Change; no action', async () => {
    const plain = mountWith(BOX)
    await flushPromises()
    expect(plain.find('.veyq-node-header-actions [data-action^="cover"]').exists()).toBe(false)
    // The leading visual is core's (the kind's `cover`): the empty frame, a button that sets the cover where the writer may.
    const frame = plain.get('.veyq-node-header-leading button.veyq-node-header-cover.is-empty')
    expect(frame.attributes('aria-label')).toBe(t('node.cover.set'))
    expect(frame.findComponent(VeyqIcon).props('name')).toBe('inbox')

    const covered = mountWith(withCover())
    await flushPromises()
    await vi.waitFor(() => expect(covered.find('.veyq-node-header-cover img').attributes('src')).toBe('blob:box-cover'))
    expect(covered.get('.veyq-node-header-leading button.veyq-node-header-cover').attributes('aria-label')).toBe(t('node.cover.change'))
    expect(covered.find('[data-action^="cover"]').exists(), 'no header action: the visual is the way in, removal in its dialog').toBe(false)
  })

  it('the cover itself, clicked, opens core\'s cover dialog', async () => {
    const covered = mountWith(withCover())
    await flushPromises()
    await vi.waitFor(() => expect(covered.find('.veyq-node-header-cover img').exists()).toBe(true))
    await covered.get('.veyq-node-header-leading button.veyq-node-header-cover').trigger('click')
    await vi.waitFor(() => expect(document.body.querySelector('[role="dialog"]')).not.toBeNull())
    expect([...document.body.querySelectorAll('[role="dialog"] button')].map((b) => b.textContent?.trim())).toContain(t('node.cover.remove'))
    covered.unmount()
    await vi.waitFor(() => expect(document.body.querySelector('[role="dialog"]')).toBeNull())
  })

  it('the header\'s visual, clicked, opens core\'s cover dialog - Remove inside it only where the box has a cover', async () => {
    const plain = mountWith(BOX)
    await flushPromises()
    await plain.get('.veyq-node-header-leading button[data-testid="node-header-cover"]').trigger('click')
    await vi.waitFor(() => expect(document.body.querySelector('[role="dialog"]')).not.toBeNull())
    const buttons = () => [...document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].map((b) => b.textContent?.trim())
    expect(buttons()).toContain(t('node.cover.choose'))
    expect(buttons(), 'no cover yet: nothing to remove').not.toContain(t('node.cover.remove'))
    plain.unmount()
    await vi.waitFor(() => expect(document.body.querySelector('[role="dialog"]')).toBeNull())

    const covered = mountWith(withCover())
    await flushPromises()
    await covered.get('.veyq-node-header-leading button[data-testid="node-header-cover"]').trigger('click')
    await vi.waitFor(() => expect(document.body.querySelector('[role="dialog"]')).not.toBeNull())
    expect(buttons()).toContain(t('node.cover.remove'))
    expect(document.body.querySelector('[role="dialog"]')!.textContent, 'no application hint on a drop box').not.toContain('gallery')
  })

  it('locked keys or a pending deletion offer none, and the drop box still shows the cover it has', async () => {
    for (const over of [{ epochs: null }, { readonly: true }]) {
      const w = mountWith(withCover(), over)
      await flushPromises()
      expect(w.find('button[data-action="cover"]').exists()).toBe(false)
      await vi.waitFor(() => expect(w.find('.veyq-node-header-cover img').exists()).toBe(true))
      expect(w.find('.veyq-node-header-leading button').exists(), 'the cover is no button there').toBe(false)
    }
  })
})
