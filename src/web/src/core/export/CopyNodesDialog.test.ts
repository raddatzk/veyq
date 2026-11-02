// @vitest-environment happy-dom
// Core's copy dialog (uc.platform.copy-node) over the REAL registry (Memories and Depot,
// filled by `src/test/setup.ts`), the real `VeyqDialog` and the move's real browser, every step a DOM click. The walk and
// the replay are `prepareCopy`'s (mocked here: `copySubtree.test.ts` and `copy.wire.test.ts` pin them). What this pins: the
// browser for a copy allows the nodes' own place and blocks them and their inside; the summary says what is created, its
// bytes, what is left out and only the losses of the copied kinds; a quota "no" disables "Copy here" and writes nothing; a
// copy runs under the place on screen and names the copy; a stop says N of M and that it was trashed.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./copySubtree', () => ({ prepareCopy: vi.fn() }))
vi.mock('../../features/tenant/tenantStorage', () => ({ tenantStorageFits: vi.fn() }))
vi.mock('../node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [] as unknown[], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => toast }))

import CopyNodesDialog from './CopyNodesDialog.vue'
import { prepareCopy, type PreparedCopy } from './copySubtree'
import { ImportFailed } from './importSubtree'
import { FOLDER_KIND } from '../node/kind'
import type { TreeNode } from '../node/tree'
import { treeStore } from '../node/treeStore'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import { CHAPTER_KIND, MEMORY_KIND } from '../../features/memories/kinds'
import { copiesKind } from './copyActions'
import { tenantStorageFits } from '../../features/tenant/tenantStorage'
import { activeSpace } from '../../app/activeSpace'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { memberAccessOf, memberTreeOf, treeNode } from '../../test/models'
import { realI18n, t } from '../../test/i18n'
import { crumbs, goTo, places, whyNotHere } from '../../test/moveBrowser'

const n = (id: string, kind: string, parentId: string | null, name: string, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name, parentId, ...over })
// Familie (root) ─ Reisen/ ─ Rom/, bericht.pdf, Lissabon (Memory)
//                ─ Archiv/
//                ─ Postfach (drop box) ─ Anna/ (a guest's folder)
const NODES = [
  n('reisen', FOLDER_KIND, null, 'Reisen'), n('rom', FOLDER_KIND, 'reisen', 'Rom'), n('file', FILE_KIND, 'reisen', 'bericht.pdf'),
  n('lissabon', MEMORY_KIND, 'reisen', 'Lissabon'),
  n('archiv', FOLDER_KIND, null, 'Archiv', { epoch: 2 }), // its key replaced twice under scope epoch 0
  n('box', DROPBOX_KIND, null, 'Postfach'), n('anna', FOLDER_KIND, 'box', 'Anna', { shareIdentityId: 'id-anna' }),
]
const byId = (id: string) => NODES.find((x) => x.id === id)!
const epochs = epochsOf(new Uint8Array(32))
const access = memberAccessOf({ scopeId: 'scope-1', epochs })

function prepared(over: Partial<PreparedCopy> = {}): PreparedCopy & { run: ReturnType<typeof vi.fn> } {
  return { nodes: 3, bytes: 2048, leftOut: [], kinds: [FOLDER_KIND, FILE_KIND], run: vi.fn(async () => ({ nodeIds: ['new1'], nodes: 3, skipped: [] })), ...over } as PreparedCopy & { run: ReturnType<typeof vi.fn> }
}
async function mountDialog(nodes: TreeNode[]) {
  const w = mount(CopyNodesDialog, {
    attachTo: document.body,
    // every key open, as for an unlocked member
    props: { nodes, access, tree: { ...memberTreeOf(() => NODES), keyOf: (id: string) => (byId(id) ? new Uint8Array(32).fill(7) : null) }, rootLabel: 'Familie' },
    global: { plugins: [realI18n()] },
  })
  await flushPromises()
  return w
}
const body = () => document.body
const submit = () => body().querySelector<HTMLButtonElement>('[data-testid="copy-submit"]')!
const text = (id: string) => body().querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
const listed = (id: string) => [...body().querySelectorAll(`[data-testid="${id}"] li`)].map((li) => li.textContent?.trim())
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
  await flushPromises()
}

beforeEach(() => {
  document.body.innerHTML = ''
  vi.mocked(prepareCopy).mockReset()
  vi.mocked(tenantStorageFits).mockReset().mockResolvedValue(true)
  for (const f of Object.values(toast)) f.mockClear()
  activeSpace.tenantId = 'tenant-1'
  treeStore.nodes = NODES
})

describe('CopyNodesDialog: the browser', () => {
  it('opens where the node is, which a copy may take ("Name (2)" there); the node itself is listed blocked', async () => {
    vi.mocked(prepareCopy).mockResolvedValue(prepared())
    await mountDialog([byId('rom')])
    expect(body().textContent).toContain(t('node.copy.title', { name: 'Rom' }))
    expect(crumbs()).toEqual(['Familie', 'Reisen'])
    expect(places()).toEqual([['Rom', false, t('node.move.blocked.self')]])
    expect(whyNotHere()).toBe('')
    expect(submit().disabled).toBe(false)
  })

  it('another folder may take it', async () => {
    vi.mocked(prepareCopy).mockResolvedValue(prepared())
    await mountDialog([byId('reisen')])
    await goTo('Familie', 'Archiv')
    expect(submit().disabled).toBe(false)
    expect(prepareCopy).toHaveBeenCalledWith(expect.objectContaining({ rootIds: ['reisen'] }))
  })

  it('several: their level is allowed, a place inside one of them is not - naming it', async () => {
    vi.mocked(prepareCopy).mockResolvedValue(prepared())
    await mountDialog([byId('reisen'), byId('archiv')])
    expect(body().textContent).toContain(t('node.copy.many.title', { count: 2 }))
    expect(crumbs()).toEqual(['Familie'])
    expect(submit().disabled).toBe(false)
    expect(places().map(([name, open]) => [name, open])).toEqual([['Archiv', false], ['Reisen', false]])
  })
})

describe('CopyNodesDialog: the summary, before anything is written', () => {
  it('says what it creates, its bytes and where; what it leaves out by name; the losses of the copied kinds only', async () => {
    vi.mocked(prepareCopy).mockResolvedValue(prepared({
      nodes: 3, bytes: 2048, kinds: [FOLDER_KIND, FILE_KIND, DROPBOX_KIND],
      leftOut: [{ name: 'Lissabon', kind: MEMORY_KIND, reason: 'not_copyable' }],
    }))
    await mountDialog([byId('reisen')])
    expect(text('copy-summary')).toBe(t('node.copy.summary', { nodes: 3, size: '2 kB', target: 'Familie' }, 3))
    expect(listed('copy-left-out')).toEqual([t('node.copy.leftOutLine', { name: 'Lissabon', reason: t('node.copy.reason.not_copyable') })])
    expect(listed('copy-losses')).toEqual([t('node.copy.lossAlways'), t('node.copy.lossFolderCover'), t('depot.dropBox.copyLoss')])
    expect(listed('copy-losses')).not.toContain(t('memory.copyLoss'))
  })

  it('a file alone loses only what every copy loses', async () => {
    vi.mocked(prepareCopy).mockResolvedValue(prepared({ nodes: 1, kinds: [FILE_KIND] }))
    await mountDialog([byId('file')])
    expect(listed('copy-losses')).toEqual([t('node.copy.lossAlways')])
    expect(listed('copy-left-out')).toEqual([])
  })

  it('asks the quota question; a "no" says so, disables "Copy here" and writes nothing', async () => {
    const p = prepared()
    vi.mocked(prepareCopy).mockResolvedValue(p)
    vi.mocked(tenantStorageFits).mockResolvedValue(false)
    await mountDialog([byId('reisen')])
    expect(tenantStorageFits).toHaveBeenCalledWith('tenant-1', 2048)
    expect(text('copy-no-room')).toBe(t('node.copy.noRoom', { size: '2 kB' }))
    expect(submit().disabled).toBe(true)
    body().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await flushPromises()
    expect(p.run).not.toHaveBeenCalled()
  })

  it('nothing copyable: says so and offers no copy', async () => {
    vi.mocked(prepareCopy).mockResolvedValue(prepared({ nodes: 0, bytes: 0, kinds: [], leftOut: [{ name: 'Lissabon', kind: MEMORY_KIND, reason: 'not_copyable' }] }))
    await mountDialog([byId('lissabon')])
    expect(text('copy-summary')).toBe(t('node.copy.nothing'))
    expect(submit().disabled).toBe(true)
    expect(tenantStorageFits).not.toHaveBeenCalled()
  })
})

describe('CopyNodesDialog: the copy', () => {
  it('copies under the place on screen with its key, names the copy, and closes', async () => {
    const p = prepared()
    p.run.mockImplementation(async () => {
      treeStore.nodes = [...NODES, n('new1', FOLDER_KIND, 'archiv', 'Reisen')]
      return { nodeIds: ['new1'], nodes: 3, skipped: [] }
    })
    vi.mocked(prepareCopy).mockResolvedValue(p)
    const w = await mountDialog([byId('reisen')])
    await goTo('Archiv')
    await click(submit())
    expect(p.run).toHaveBeenCalledWith(expect.objectContaining({ scopeId: 'scope-1', epochs, parent: { id: 'archiv', key: expect.any(Uint8Array), epoch: 2, scopeEpoch: 0 } }))
    expect(toast.success).toHaveBeenCalledWith(t('node.copy.copied', { name: 'Reisen', to: 'Archiv' }))
    expect(w.emitted('copied')).toEqual([[{ nodeIds: ['new1'], to: 'archiv' }]])
    expect(w.emitted('close')).toBeTruthy()
  })

  it('at its own place: the root, as the copy named "Name (2)"', async () => {
    const p = prepared()
    p.run.mockImplementation(async () => {
      treeStore.nodes = [...NODES, n('new1', FOLDER_KIND, null, 'Reisen (2)')]
      return { nodeIds: ['new1'], nodes: 3, skipped: [] }
    })
    vi.mocked(prepareCopy).mockResolvedValue(p)
    await mountDialog([byId('reisen')])
    await click(submit())
    expect(p.run).toHaveBeenCalledWith(expect.objectContaining({ parent: null }))
    expect(toast.success).toHaveBeenCalledWith(t('node.copy.copied', { name: 'Reisen (2)', to: 'Familie' }))
  })

  it('a stop part-way says N of M and that what was created is in the trash; the dialog stays', async () => {
    const p = prepared()
    p.run.mockRejectedValue(new ImportFailed(2, 3, true, null, new Error('upload_failed')))
    vi.mocked(prepareCopy).mockResolvedValue(p)
    const w = await mountDialog([byId('reisen')])
    await click(submit())
    expect(text('copy-error')).toBe(t('node.copy.failed', { done: 2, total: 3 }))
    expect(w.emitted('close')).toBeFalsy()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('a stop on the quota says so too; one whose trash failed says where to look', async () => {
    const p = prepared()
    p.run.mockRejectedValueOnce(new ImportFailed(1, 3, true, null, new Error('quota_exceeded')))
    p.run.mockRejectedValueOnce(new ImportFailed(1, 3, false, 'new1', new Error('upload_failed')))
    vi.mocked(prepareCopy).mockResolvedValue(p)
    await mountDialog([byId('reisen')])
    await click(submit())
    expect(text('copy-error')).toBe(`${t('node.copy.quota')} ${t('node.copy.failed', { done: 1, total: 3 })}`)
    await click(submit())
    expect(text('copy-error')).toBe(t('node.copy.failedLeft', { done: 1, total: 3, to: 'Familie' }))
  })

  it('a walk that fails says nothing was copied', async () => {
    vi.mocked(prepareCopy).mockRejectedValue(new Error('network'))
    await mountDialog([byId('reisen')])
    expect(text('copy-error')).toBe(t('node.copy.walkFailed'))
    expect(submit().disabled).toBe(true)
  })
})

describe('the applications\' kinds (the real registry)', () => {
  it('every kind of both applications copies: a folder, a file, a drop box, a Memory and a chapter (ADR 0082 decision 11)', () => {
    expect([FOLDER_KIND, FILE_KIND, DROPBOX_KIND, MEMORY_KIND, CHAPTER_KIND].map(copiesKind)).toEqual([true, true, true, true, true])
  })
})
