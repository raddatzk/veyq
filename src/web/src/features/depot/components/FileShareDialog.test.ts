// @vitest-environment happy-dom
// FILE's share dialog (inbox #378): core's link manager worded for ONE file. The data layer is stubbed so the dialog mounts
// crypto- and network-free (`core/sharing/folderShare.wire.test.ts` drives the real mint onto the wire for a folder, and
// `fileShare.wire.test.ts` for a file). What it proves: only READ and READ_WRITE are offered (the backend refuses
// WRITE_ONLY on a file), every sentence that says what the link shows is the FILE's — never "folder" — in both
// languages, and the mint is for the file with its own key and name.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../../core/sharing/shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn() }))
vi.mock('../../../core/sharing/manageShares', () => ({
  mintShare: vi.fn(), remintShare: vi.fn(), listSharesFor: vi.fn(), revokeShare: vi.fn(), reactivateShare: vi.fn(),
  deleteShare: vi.fn(), isReissueRequired: vi.fn(), isShareDeleted: vi.fn(), readShareName: vi.fn(), readShareNote: vi.fn(),
  hasLocaleText: vi.fn(),
}))

import FileShareDialog from './FileShareDialog.vue'
import * as manage from '../../../core/sharing/manageShares'
import { buildShareLink, recoverShareLink } from '../../../core/sharing/shareLink'
import { ShareRole } from '../../../api/generated/sharing'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { realI18n, t } from '../../../test/i18n'
import { i18n } from '../../../i18n'
import { FILE_KIND } from '../kinds'

const listSharesFor = vi.mocked(manage.listSharesFor)
const mintShare = vi.mocked(manage.mintShare)

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000dd'
const FILE_ID = '5c09e000-0000-4000-8000-0000000000f1'
const EPOCHS = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) }
const FILE_KEY = new Uint8Array(32).fill(9)

const stubs = {
  VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible"><h1 class="header">{{ header }}</h1><slot /></div>' },
  VeyqToggleGroup: {
    props: ['options', 'modelValue'],
    emits: ['update:modelValue'],
    template:
      '<div class="select-button"><button v-for="o in options" :key="o.value" class="opt" :data-value="o.value" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
  VeyqButton: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqInput: { props: ['modelValue'], template: '<input class="input" :value="modelValue" />' },
  VeyqSwitch: { props: ['modelValue'], emits: ['update:modelValue'], template: '<button v-bind="$attrs" @click="$emit(\'update:modelValue\', !modelValue)" />' },
  VeyqDateField: true,
  LocaleMarkdownEditor: true,
}
const mountDialog = (extra: { contribute?: boolean } = { contribute: true }) =>
  mount(FileShareDialog, {
    props: { scopeId: SCOPE_ID, nodeId: FILE_ID, nodeKey: FILE_KEY, epochs: EPOCHS, nodeEpoch: 0, nodeName: 'Report.pdf', nodeKind: FILE_KIND, ...extra },
    global: { plugins: [realI18n()], stubs },
  })
const byText = (w: ReturnType<typeof mountDialog>, text: string) => w.findAll('button').find((b) => b.text() === text)!

const row = (over: Partial<manage.ManagedShare>): manage.ManagedShare => ({
  shareId: 's', scopeId: SCOPE_ID, nodeId: FILE_ID, role: ShareRole.Read, enabled: true, mine: true,
  createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null, sealedNote: null, sealedShareName: null,
  sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
  reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
  ...over,
})

beforeEach(() => {
  vi.clearAllMocks()
  listSharesFor.mockResolvedValue([])
  mintShare.mockResolvedValue({ shareId: 'new-share', kShare: new Uint8Array(32) })
  vi.mocked(buildShareLink).mockImplementation((id) => `https://x/s/${id}#k=abc`)
  vi.mocked(recoverShareLink).mockImplementation((a) => `https://x/s/${a.shareId}#k=rec`)
  vi.mocked(manage.readShareName).mockReturnValue('')
  vi.mocked(manage.readShareNote).mockReturnValue({ texts: {}, mustAccept: false })
  vi.mocked(manage.hasLocaleText).mockReturnValue(false)
})

describe('FileShareDialog', () => {
  it('words the list for a file: its heading and intro, not the folder\'s', async () => {
    const w = mountDialog()
    await flushPromises()
    expect(w.find('.header').text()).toBe(t('depot.share.title'))
    expect(w.text()).toContain(t('depot.share.intro'))
    expect(w.text()).not.toContain(t('share.folder.intro'))
  })

  it('offers READ and READ_WRITE only — no WRITE_ONLY (uploader) option on a file — each with the file\'s sentence', async () => {
    const w = mountDialog()
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    expect(w.findAll('.opt').map((o) => o.attributes('data-value'))).toEqual([ShareRole.Read, ShareRole.ReadWrite])
    expect(w.text()).not.toContain(t('share.roleUploader'))
    expect(w.text()).toContain(t('depot.share.roleViewerHint'))
    await w.find('.opt[data-value="READ_WRITE"]').trigger('click')
    expect(w.text()).toContain(t('depot.share.roleContributorHint'))
    expect(w.text()).not.toContain(t('share.folder.roleContributorHint'))
  })

  it('offers READ only where the host hands no contribute (control of the test above)', async () => {
    const w = mountDialog({})
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    expect(w.findAll('.opt').map((o) => o.attributes('data-value'))).toEqual([ShareRole.Read])
  })

  it.each([ShareRole.Read, ShareRole.ReadWrite])('mints a %s link of THE FILE with its own key, and shows the link', async (role) => {
    const w = mountDialog()
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    await w.find(`.opt[data-value="${role}"]`).trigger('click')
    await byText(w, t('share.create')).trigger('click')
    await flushPromises()
    expect(mintShare).toHaveBeenCalledOnce()
    const args = mintShare.mock.calls[0]
    expect([args[0], args[1], args[4], args[5]]).toEqual([SCOPE_ID, FILE_ID, 'Report.pdf', role])
    expect(Array.from(args[2].key)).toEqual(Array.from(FILE_KEY))
    expect(args[2].epoch).toBe(0)
    expect(w.html()).toContain('new-share')
  })

  it('deleting a link asks the file\'s question', async () => {
    listSharesFor.mockResolvedValue([row({ enabled: false })])
    const w = mountDialog()
    await flushPromises()
    await w.find('.folder-share-fold').trigger('click')
    await w.find(`button[aria-label="${t('share.delete')}"]`).trigger('click')
    expect(confirmRequest.value?.message).toBe(t('depot.share.deleteConfirm'))
    settleConfirm(false)
  })

  it('every sentence it adds says nothing of folders or Memories, in both languages', () => {
    for (const locale of ['en', 'de'] as const) {
      const share = (i18n.global.getLocaleMessage(locale) as { depot: { share: Record<string, string> } }).depot.share
      expect(Object.keys(share).sort()).toEqual(['deleteConfirm', 'intro', 'reissueWarning', 'roleContributorHint', 'roleViewerHint', 'title'])
      for (const [key, text] of Object.entries(share)) expect(text, `${locale} ${key}`).not.toMatch(/folder|ordner|memor|erinnerung|photo|foto/i)
      expect(share.roleContributorHint, locale).toMatch(locale === 'en' ? /version history/ : /Versionsverlauf/)
    }
  })
})
