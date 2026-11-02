// @vitest-environment happy-dom
// A member's drop-box surfaces, the wording and the one confirmation inbox #385 (c) asked for: the create dialog says
// what a drop box is before it asks a name, and submits with "Create"; the drop-box tab with no drop box says what one
// is (inbox #407: the application's `emptyText` on core's unified level list, which replaced the drop-box section), and
// a drop box is a core item whose row data is Depot's; deleting a link asks first, as core's folder share dialog does (declined → nothing deleted). The data layer
// (`dropBox.ts`, core's `manageShares`/`shareLink`) is stubbed; the real i18n catalog and UI layer render.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../api', () => ({ filesApi: vi.fn() }))
vi.mock('../dropBox', () => ({ createDropBox: vi.fn(), mintDropBoxLink: vi.fn() }))
vi.mock('../../../core/sharing/manageShares', async (importOriginal) => ({
  isReissueRequired: (await importOriginal<typeof import('../../../core/sharing/manageShares')>()).isReissueRequired,
  listSharesFor: vi.fn(), deleteShare: vi.fn(), revokeShare: vi.fn(), reactivateShare: vi.fn(),
}))
vi.mock('../../../core/sharing/shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn(() => 'https://x/s/1#k=a') }))

import DropBoxCreateDialog from './DropBoxCreateDialog.vue'
import DropBoxShareDialog from './DropBoxShareDialog.vue'
import { deleteShare, listSharesFor, reactivateShare, revokeShare, type ManagedShare } from '../../../core/sharing/manageShares'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { ShareRole } from '../../../api/generated/sharing'
import type { TreeNode } from '../../../core/node/tree'
import { DROPBOX_KIND } from '../kinds'
import { registry } from '../../../core/applications/registry'
import { depotRows } from '../levelRows'
import { filesApi } from '../api'
import { stubApi } from '../../../test/apiMock'
import { mountRows, titlesOf } from '../../../test/levelRows'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const EPOCHS = epochsOf(new Uint8Array(32).fill(1))
const global = { plugins: [realI18n()], stubs: { VeyqDialog: { props: ['header'], template: '<div class="dialog"><h1>{{ header }}</h1><slot /></div>' } } }

beforeEach(() => {
  vi.clearAllMocks()
})

describe('the drop box create dialog', () => {
  it('says what a drop box is before it asks the name, and submits with "Create"', () => {
    const w = mount(DropBoxCreateDialog, { props: { kind: DROPBOX_KIND, scopeId: SCOPE, epochs: EPOCHS, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null }, global })
    expect(w.find('.veyq-form-copy').text()).toBe(t('depot.dropBox.createHint'))
    expect(w.find('button[type="submit"]').text()).toBe(t('common.create'))
  })
})

describe('the drop boxes in the unified level list', () => {
  const box: TreeNode = treeNode({ id: 'b1', kind: DROPBOX_KIND, name: 'Einsendungen', parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })
  const ctx = (nodes: TreeNode[]) => ({
    folder: null, nodes, epochs: EPOCHS, viewMode: 'grid' as const, liveTick: undefined,
    access: memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS }), tree: memberTreeOf(() => nodes, { scopeId: SCOPE, epochs: EPOCHS }),
  })

  it('the drop-box tab with none yet says what a drop box is (the registered tab text)', () => {
    const tab = registry.tabs().find((x) => x.kinds.includes(DROPBOX_KIND))!
    expect(tab.label).toBe('depot.dropBox.section')
    const nodes: TreeNode[] = []
    const text = tab.emptyText!({ access: memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS }), tree: memberTreeOf(() => nodes, { scopeId: SCOPE, epochs: EPOCHS }), folder: null, epochs: EPOCHS })
    expect(t(text)).toContain('a drop box collects files from people without an account')
  })

  it('with one, core draws it as an item with its name and the drop-box kind', async () => {
    stubApi(filesApi, { listFiles: { items: [] } })
    const h = mountRows(depotRows, ctx([box]), 'grid', { stubs: global.stubs })
    await flushPromises()
    expect(titlesOf(h.w)).toEqual(['Einsendungen'])
    expect(h.w.find('.veyq-level-item-meta').text()).toContain(t('depot.dropBox.kind'))
    expect(h.w.find('button.veyq-level-item-main').attributes('aria-label')).toBe(t('depot.dropBox.open', { name: 'Einsendungen' }))
  })
})

describe('the drop box share dialog', () => {
  const link: ManagedShare = {
    shareId: 'link-1', scopeId: SCOPE, nodeId: 'b1', role: ShareRole.WriteOnly, enabled: true, mine: true,
    createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null, sealedNote: null, sealedShareName: null,
    sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
    reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
  } as ManagedShare

  it('deletes a link only after the member confirmed, saying what its guests sent stays', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([link])
    const w = mount(DropBoxShareDialog, {
      props: { scopeId: SCOPE, nodeId: 'b1', nodeKey: new Uint8Array(32), epochs: EPOCHS, nodeEpoch: 0, nodeName: 'Einsendungen', nodeKind: DROPBOX_KIND },
      global,
    })
    await flushPromises()
    const del = () => w.find(`button[aria-label="${t('depot.dropBox.shareDelete')}"]`)

    await del().trigger('click')
    expect(confirmRequest.value?.message).toBe(t('depot.dropBox.shareDeleteConfirm'))
    settleConfirm(false)
    await flushPromises()
    expect(deleteShare).not.toHaveBeenCalled()

    await del().trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(deleteShare).toHaveBeenCalledWith(expect.objectContaining({ shareId: 'link-1', role: ShareRole.WriteOnly }))
  })

  // Inbox #394: switching off asks first, and an off link is switched on again (core's reactivate), as on a folder.
  const mountDialog = async () => {
    const w = mount(DropBoxShareDialog, {
      props: { scopeId: SCOPE, nodeId: 'b1', nodeKey: new Uint8Array(32), epochs: EPOCHS, nodeEpoch: 0, nodeName: 'Einsendungen', nodeKind: DROPBOX_KIND },
      global,
    })
    await flushPromises()
    return w
  }
  it('switches a link off only after the member confirmed', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([link])
    const w = await mountDialog()
    const off = () => w.find(`button[aria-label="${t('depot.dropBox.shareRevoke')}"]`)
    await off().trigger('click')
    expect(confirmRequest.value?.message).toBe(t('depot.dropBox.shareRevokeConfirm'))
    settleConfirm(false)
    await flushPromises()
    expect(revokeShare).not.toHaveBeenCalled()
    await off().trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(revokeShare).toHaveBeenCalledWith(expect.objectContaining({ shareId: 'link-1', role: ShareRole.WriteOnly }))
  })

  it('an off link shows its link marked off and switches on again; one a departed member may hold says to make a new one', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([{ ...link, enabled: false }, { ...link, shareId: 'link-2', enabled: false, reissueRequired: true }])
    const w = await mountDialog()
    const rows = w.findAll('li.veyq-list-row')
    expect(rows[0].text()).toContain('https://x/s/1#k=a')
    expect(rows[0].find('.dropbox-link-off').text()).toBe(t('depot.dropBox.shareOff'))
    expect(rows[0].find(`button[aria-label="${t('depot.dropBox.shareRevoke')}"]`).exists()).toBe(false)
    expect(rows[1].text()).toContain(t('depot.dropBox.shareReissueHint'))
    expect(rows[1].find(`button[aria-label="${t('depot.dropBox.shareReactivate')}"]`).exists()).toBe(false)
    await rows[0].find(`button[aria-label="${t('depot.dropBox.shareReactivate')}"]`).trigger('click')
    await flushPromises()
    expect(confirmRequest.value, 'switching on asks nothing').toBeNull()
    // the reopen re-seals the link's root under the drop box's key at its own epoch, under the scope's one key
    expect(reactivateShare).toHaveBeenCalledOnce()
    expect(reactivateShare).toHaveBeenCalledWith(
      expect.objectContaining({ shareId: 'link-1', nodeId: 'b1', role: ShareRole.WriteOnly, sealedShareKey: 'k', sealedShareKeyEpoch: 0 }),
      { key: new Uint8Array(32), epoch: 0 }, EPOCHS, 'Einsendungen',
    )
    expect(listSharesFor).toHaveBeenCalledTimes(2)
  })
})
