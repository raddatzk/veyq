// @vitest-environment happy-dom
// A member's drop-box surfaces, the wording and the one confirmation inbox #385 (c) asked for: the create dialog says
// what a drop box is before it asks a name, and submits with "Create"; the level section with no drop box says what one
// is; deleting a link asks first, as core's folder share dialog does (declined → nothing deleted). The data layer
// (`dropBox.ts`, core's `manageShares`/`shareLink`) is stubbed; the real i18n catalog and PrimeVue render.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('../dropBox', () => ({ createDropBox: vi.fn(), mintDropBoxLink: vi.fn() }))
vi.mock('../../../core/sharing/manageShares', async (importOriginal) => ({
  isReissueRequired: (await importOriginal<typeof import('../../../core/sharing/manageShares')>()).isReissueRequired,
  listSharesFor: vi.fn(), deleteShare: vi.fn(), revokeShare: vi.fn(), reactivateShare: vi.fn(),
}))
vi.mock('../../../core/sharing/shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn(() => 'https://x/s/1#k=a') }))

import DropBoxCreateDialog from './DropBoxCreateDialog.vue'
import DropBoxSection from './DropBoxSection.vue'
import DropBoxShareDialog from './DropBoxShareDialog.vue'
import { deleteShare, listSharesFor, reactivateShare, revokeShare, type ManagedShare } from '../../../core/sharing/manageShares'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { ShareRole } from '../../../api/generated/sharing'
import type { TreeNode } from '../../../core/node/tree'
import { DROPBOX_KIND } from '../kinds'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { treeNode } from '../../../test/models'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const EPOCHS = epochsOf(new Uint8Array(32).fill(1))
const global = { plugins: [realI18n(), PrimeVue], stubs: { Dialog: { props: ['header'], template: '<div class="dialog"><h1>{{ header }}</h1><slot /></div>' } } }

beforeEach(() => {
  vi.clearAllMocks()
})

describe('the drop box create dialog', () => {
  it('says what a drop box is before it asks the name, and submits with "Create"', () => {
    const w = mount(DropBoxCreateDialog, { props: { kind: DROPBOX_KIND, scopeId: SCOPE, epochs: EPOCHS, parentId: null, parentKey: null }, global })
    expect(w.find('.veyq-form-copy').text()).toBe(t('files.dropBox.createHint'))
    expect(w.find('button[type="submit"]').text()).toBe(t('common.create'))
  })
})

describe('the drop boxes section', () => {
  const box: TreeNode = treeNode({ id: 'b1', kind: DROPBOX_KIND, name: 'Einsendungen', parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })
  const section = (nodes: TreeNode[]) => mount(DropBoxSection, { props: { folder: null, nodes, epochs: EPOCHS, readonly: false }, global })

  it('with none yet, says what a drop box is; with one, lists it instead', () => {
    expect(section([]).find('.veyq-empty').text()).toBe(t('files.dropBox.sectionEmpty'))
    const listed = section([box])
    expect(listed.find('.veyq-empty').exists()).toBe(false)
    expect(listed.find('.veyq-row-title').text()).toBe('Einsendungen')
  })
})

describe('the drop box share dialog', () => {
  const link: ManagedShare = {
    shareId: 'link-1', scopeId: SCOPE, nodeId: 'b1', role: ShareRole.WriteOnly, enabled: true, mine: true,
    createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null, sealedNote: null, sealedShareName: null,
    sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
    reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false,
  } as ManagedShare

  it('deletes a link only after the member confirmed, saying what its guests sent stays', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([link])
    const w = mount(DropBoxShareDialog, {
      props: { scopeId: SCOPE, nodeId: 'b1', nodeKey: new Uint8Array(32), epochs: EPOCHS, nodeName: 'Einsendungen', nodeKind: DROPBOX_KIND },
      global,
    })
    await flushPromises()
    const del = () => w.find(`button[aria-label="${t('files.dropBox.shareDelete')}"]`)

    await del().trigger('click')
    expect(confirmRequest.value?.message).toBe(t('files.dropBox.shareDeleteConfirm'))
    settleConfirm(false)
    await flushPromises()
    expect(deleteShare).not.toHaveBeenCalled()

    await del().trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(deleteShare).toHaveBeenCalledWith('link-1')
  })

  // Inbox #394: switching off asks first, and an off link is switched on again (core's reactivate), as on a folder.
  const mountDialog = async () => {
    const w = mount(DropBoxShareDialog, {
      props: { scopeId: SCOPE, nodeId: 'b1', nodeKey: new Uint8Array(32), epochs: EPOCHS, nodeName: 'Einsendungen', nodeKind: DROPBOX_KIND },
      global,
    })
    await flushPromises()
    return w
  }
  it('switches a link off only after the member confirmed', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([link])
    const w = await mountDialog()
    const off = () => w.find(`button[aria-label="${t('files.dropBox.shareRevoke')}"]`)
    await off().trigger('click')
    expect(confirmRequest.value?.message).toBe(t('files.dropBox.shareRevokeConfirm'))
    settleConfirm(false)
    await flushPromises()
    expect(revokeShare).not.toHaveBeenCalled()
    await off().trigger('click')
    settleConfirm(true)
    await flushPromises()
    expect(revokeShare).toHaveBeenCalledWith('link-1')
  })

  it('an off link shows its link marked off and switches on again; one a departed member may hold says to make a new one', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([{ ...link, enabled: false }, { ...link, shareId: 'link-2', enabled: false, reissueRequired: true }])
    const w = await mountDialog()
    const rows = w.findAll('li.veyq-list-row')
    expect(rows[0].text()).toContain('https://x/s/1#k=a')
    expect(rows[0].find('.dropbox-link-off').text()).toBe(t('files.dropBox.shareOff'))
    expect(rows[0].find(`button[aria-label="${t('files.dropBox.shareRevoke')}"]`).exists()).toBe(false)
    expect(rows[1].text()).toContain(t('files.dropBox.shareReissueHint'))
    expect(rows[1].find(`button[aria-label="${t('files.dropBox.shareReactivate')}"]`).exists()).toBe(false)
    await rows[0].find(`button[aria-label="${t('files.dropBox.shareReactivate')}"]`).trigger('click')
    await flushPromises()
    expect(confirmRequest.value, 'switching on asks nothing').toBeNull()
    expect(reactivateShare).toHaveBeenCalledWith('link-1')
    expect(listSharesFor).toHaveBeenCalledTimes(2)
  })
})
