// @vitest-environment happy-dom
// Core's folder share dialog (cr-node-key-hierarchy 5b.3): a member mints a READ or a READ_WRITE link of a folder — never
// a WRITE_ONLY one, which the backend refuses on a folder — sees the link, and manages the folder's existing links
// (state badges, revoke, delete). The data layer (`./manageShares`, `./shareLink`) is stubbed so the dialog mounts
// crypto- and network-free; `folderShare.wire.test.ts` drives the real mint onto the wire.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn() }))
vi.mock('./manageShares', () => ({
  mintShare: vi.fn(),
  remintShare: vi.fn(),
  listSharesFor: vi.fn(),
  revokeShare: vi.fn(),
  reactivateShare: vi.fn(),
  deleteShare: vi.fn(),
  isReissueRequired: vi.fn(),
  isShareDeleted: vi.fn(),
  readShareName: vi.fn(),
  readShareNoteMap: vi.fn(),
  hasLocaleText: vi.fn(),
}))

import FolderShareDialog from './FolderShareDialog.vue'
import * as manage from './manageShares'
import { buildShareLink, recoverShareLink } from './shareLink'
import { ShareRole } from '../../api/generated/sharing'
import { confirmRequest, settleConfirm } from '../../shared/state/useConfirm'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'

const mintShare = vi.mocked(manage.mintShare)
const listSharesFor = vi.mocked(manage.listSharesFor)
const revokeShare = vi.mocked(manage.revokeShare)
const deleteShare = vi.mocked(manage.deleteShare)

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000dd'
const FOLDER_ID = '5c09e000-0000-4000-8000-0000000000f0'
const EPOCHS = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) }
const FOLDER_KEY = new Uint8Array(32).fill(4)

const stubs = {
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible"><h1 class="header">{{ header }}</h1><slot /></div>' },
  SelectButton: {
    props: ['options', 'modelValue'],
    emits: ['update:modelValue'],
    template:
      '<div class="select-button"><button v-for="o in options" :key="o.value" class="opt" :data-value="o.value" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: { props: ['modelValue'], template: '<input class="input" :value="modelValue" />' },
  ToggleSwitch: { props: ['modelValue'], emits: ['update:modelValue'], template: '<button v-bind="$attrs" @click="$emit(\'update:modelValue\', !modelValue)" />' },
  DatePicker: true,
  LocaleMarkdownEditor: true,
}

const row = (over: Partial<manage.ManagedShare>): manage.ManagedShare => ({
  shareId: 's', scopeId: SCOPE_ID, nodeId: FOLDER_ID, role: ShareRole.Read, enabled: true, mine: true,
  createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null, sealedNote: null, sealedShareName: null,
  sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
  reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false,
  ...over,
})

function mountDialog() {
  return mount(FolderShareDialog, {
    props: { scopeId: SCOPE_ID, nodeId: FOLDER_ID, nodeKey: FOLDER_KEY, epochs: EPOCHS, nodeName: 'Trips', nodeKind: 'FOLDER' },
    global: { plugins: [realI18n()], stubs },
  })
}
const byLabel = (w: ReturnType<typeof mountDialog>, label: string) => w.find(`button[aria-label="${label}"]`)
const byText = (w: ReturnType<typeof mountDialog>, text: string) => w.findAll('button').find((b) => b.text() === text)!

beforeEach(() => {
  vi.clearAllMocks()
  listSharesFor.mockResolvedValue([])
  mintShare.mockResolvedValue({ shareId: 'new-share', kShare: new Uint8Array(32) })
  vi.mocked(buildShareLink).mockImplementation((id) => `https://x/s/${id}#k=abc`)
  vi.mocked(recoverShareLink).mockImplementation((a) => `https://x/s/${a.shareId}#k=rec`)
  vi.mocked(manage.readShareName).mockReturnValue('')
  vi.mocked(manage.readShareNoteMap).mockReturnValue({})
  vi.mocked(manage.hasLocaleText).mockReturnValue(false)
})

describe('FolderShareDialog — a new link', () => {
  it('offers READ and READ_WRITE only — no WRITE_ONLY (uploader) option on a folder', async () => {
    const w = mountDialog()
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')

    expect(w.findAll('.opt').map((o) => o.attributes('data-value'))).toEqual([ShareRole.Read, ShareRole.ReadWrite])
    expect(w.text()).not.toContain(t('share.roleUploader'))
    // The folder wording, not the Memory's photo promise.
    expect(w.text()).toContain(t('share.folder.roleViewerHint'))
  })

  it('the contributor wording names no application and says a replace is final, in both languages (inbox #383)', () => {
    for (const locale of ['en', 'de'] as const) {
      const folder = (i18n.global.getLocaleMessage(locale) as { share: { folder: Record<string, string> } }).share.folder
      for (const key of ['roleContributorHint', 'contributorBadge']) {
        expect(folder[key], `${locale} ${key}`).not.toMatch(/memor|erinnerung|photo|foto/i)
      }
      expect(folder.roleContributorHint, locale).toMatch(locale === 'en' ? /version history/ : /Versionsverlauf/)
    }
  })

  it.each([
    [ShareRole.Read, 'share.folder.roleViewerHint'],
    [ShareRole.ReadWrite, 'share.folder.roleContributorHint'],
  ] as const)('mints a %s link of the folder with its own key and name, and shows the link', async (role, hint) => {
    const w = mountDialog()
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    await w.find(`.opt[data-value="${role}"]`).trigger('click')
    expect(w.text()).toContain(t(hint))

    await byText(w, t('share.create')).trigger('click')
    await flushPromises()

    expect(mintShare).toHaveBeenCalledTimes(1)
    const [scopeId, nodeId, key, epochs, name, mintedRole] = mintShare.mock.calls[0]
    expect({ scopeId, nodeId, name, mintedRole }).toEqual({ scopeId: SCOPE_ID, nodeId: FOLDER_ID, name: 'Trips', mintedRole: role })
    expect(key).toBe(FOLDER_KEY)
    expect(epochs).toEqual(EPOCHS)
    expect(w.text()).toContain(t('share.linkReady'))
    expect(w.find('input.input').attributes('value')).toBe('https://x/s/new-share#k=abc')
    expect(listSharesFor, 'the list is re-read behind the link').toHaveBeenCalledTimes(2)
  })

  it('says so when the mint fails, and stays on the form', async () => {
    mintShare.mockRejectedValueOnce(new Error('422'))
    const w = mountDialog()
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    await byText(w, t('share.create')).trigger('click')
    await flushPromises()

    expect(w.text()).toContain(t('share.createFailed'))
    expect(w.text()).not.toContain(t('share.linkReady'))
  })
})

describe('FolderShareDialog — the folder\'s links', () => {
  it('lists them with their state: role, by another member, switched off (folded)', async () => {
    listSharesFor.mockResolvedValue([
      row({ shareId: 'mine-rw', role: ShareRole.ReadWrite }),
      row({ shareId: 'theirs', mine: false }),
      row({ shareId: 'off', enabled: false }),
    ])
    const w = mountDialog()
    await flushPromises()

    expect(listSharesFor).toHaveBeenCalledWith(FOLDER_ID)
    expect(w.findAll('.folder-share-row')).toHaveLength(2) // the switched-off one sits under a count
    expect(w.text()).toContain(t('share.folder.roleContributor'))
    expect(w.text()).toContain(t('share.byMember'))
    await w.find('.folder-share-fold').trigger('click')
    expect(w.findAll('.folder-share-row')).toHaveLength(3)
    expect(w.find('.folder-share-off').text()).toBe(t('share.disabled'))
  })

  it('shows the empty state when the folder has no link', async () => {
    const w = mountDialog()
    await flushPromises()
    expect(w.text()).toContain(t('share.none'))
    expect(w.find('.header').text()).toBe(t('share.folder.title'))
  })

  it('revokes the caller\'s own live link and re-reads the list; a co-member\'s offers no revoke', async () => {
    listSharesFor.mockResolvedValueOnce([row({ shareId: 'mine' })]).mockResolvedValueOnce([row({ shareId: 'mine', enabled: false })])
    const w = mountDialog()
    await flushPromises()

    await byLabel(w, t('share.revoke')).trigger('click')
    await flushPromises()
    expect(revokeShare).toHaveBeenCalledWith('mine')
    expect(listSharesFor).toHaveBeenCalledTimes(2)

    listSharesFor.mockResolvedValue([row({ shareId: 'theirs', mine: false })])
    const other = mountDialog()
    await flushPromises()
    expect(byLabel(other, t('share.revoke')).exists()).toBe(false)
    expect(byLabel(other, t('share.copy')).exists(), 'control: the row renders its actions').toBe(true)
  })

  it('deletes a switched-off link after the folder-worded confirm', async () => {
    listSharesFor.mockResolvedValue([row({ shareId: 'off', enabled: false })])
    const w = mountDialog()
    await flushPromises()
    await w.find('.folder-share-fold').trigger('click')

    await byLabel(w, t('share.delete')).trigger('click')
    expect(confirmRequest.value?.message).toBe(t('share.folder.deleteConfirm'))
    settleConfirm(true)
    await flushPromises()

    expect(deleteShare).toHaveBeenCalledWith('off')
    expect(w.findAll('.folder-share-row')).toHaveLength(0)
  })
})
