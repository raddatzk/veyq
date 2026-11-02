// @vitest-environment happy-dom
// Core's folder share dialog (uc.sharing.share-public-viewer:folder-share-root): a member mints a READ or a READ_WRITE
// link of a folder — never a WRITE_ONLY one, which the backend refuses on a folder — sees the link, and manages the
// folder's existing links (state badges, revoke, delete). The data layer (`./manageShares`, `./shareLink`) is stubbed
// so the dialog mounts crypto- and network-free; `folderShare.wire.test.ts` drives the real mint onto the wire.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn() }))
vi.mock('./manageShares', async () => ({
  rekeyingClosedLinks: (await import('vue')).shallowReactive(new Set<string>()),
  mintShare: vi.fn(),
  remintShare: vi.fn(),
  listSharesFor: vi.fn(),
  revokeShare: vi.fn(),
  reactivateShare: vi.fn(),
  deleteShare: vi.fn(),
  isReissueRequired: vi.fn(),
  isShareDeleted: vi.fn(),
  readShareName: vi.fn(),
  readShareNote: vi.fn(),
  hasLocaleText: vi.fn(),
}))

import FolderShareDialog from './FolderShareDialog.vue'
import * as manage from './manageShares'
import { buildShareLink, recoverShareLink } from './shareLink'
import { ShareRole } from '../../api/generated/sharing'
import { confirmRequest, settleConfirm } from '../../shared/state/useConfirm'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'
import { setTenantCapabilities } from '../../shared/state/useCapabilities'
import { httpError } from '../../test/httpError'

const mintShare = vi.mocked(manage.mintShare)
const listSharesFor = vi.mocked(manage.listSharesFor)
const revokeShare = vi.mocked(manage.revokeShare)
const deleteShare = vi.mocked(manage.deleteShare)

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000dd'
const FOLDER_ID = '5c09e000-0000-4000-8000-0000000000f0'
// The scope's one key (ADR 0115) at epoch 3; the folder's own key is at its epoch 2 (ADR 0114 decision 4).
const EPOCHS = { epoch: 3, key: new Uint8Array(32) }
const FOLDER_EPOCH = 2
const FOLDER_KEY = new Uint8Array(32).fill(4)

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

const row = (over: Partial<manage.ManagedShare>): manage.ManagedShare => ({
  shareId: 's', scopeId: SCOPE_ID, nodeId: FOLDER_ID, role: ShareRole.Read, enabled: true, mine: true,
  createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null, sealedNote: null, sealedShareName: null,
  sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
  reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
  ...over,
})

function mountDialog(extra: { contribute?: boolean; readonly?: boolean } = { contribute: true }) {
  return mount(FolderShareDialog, {
    props: { scopeId: SCOPE_ID, nodeId: FOLDER_ID, nodeKey: FOLDER_KEY, epochs: EPOCHS, nodeEpoch: FOLDER_EPOCH, nodeName: 'Trips', nodeKind: 'FOLDER', ...extra },
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
  vi.mocked(manage.readShareNote).mockReturnValue({ texts: {}, mustAccept: false })
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

  it('offers READ only where the host hands no contribute (core.public-shares.contribute off); the READ mint stays possible', async () => {
    const w = mountDialog({})
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    expect(w.findAll('.opt').map((o) => o.attributes('data-value'))).toEqual([ShareRole.Read])
    await byText(w, t('share.create')).trigger('click')
    await flushPromises()
    expect(mintShare).toHaveBeenCalledTimes(1)
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
    expect(key).toEqual({ key: FOLDER_KEY, epoch: FOLDER_EPOCH }) // the folder's key with its own epoch, as read (ADR 0114 decision 4)
    expect(key.key).toBe(FOLDER_KEY)
    expect(epochs).toEqual(EPOCHS)
    expect(w.text()).toContain(t('share.linkReady'))
    expect(w.find('input.input').attributes('value')).toBe('https://x/s/new-share#k=abc')
    expect(listSharesFor, 'the list is re-read behind the link').toHaveBeenCalledTimes(2)
  })

  it('a space\'s maximum lifetime fills the end date with the last day it allows and sends it (ADR 0088 decision 8)', async () => {
    setTenantCapabilities([{ id: 'core.public-shares', linkMaxDays: 7 }])
    try {
      const w = mountDialog()
      await flushPromises()
      await byText(w, t('share.createNew')).trigger('click')
      expect(w.text()).toContain(t('share.expiryRequiredByRule', { days: 7 }))
      await byText(w, t('share.create')).trigger('click')
      await flushPromises()
      const expiry = mintShare.mock.calls[0]![6] as Date
      expect(expiry).toBeInstanceOf(Date)
      expect(expiry.getTime()).toBeLessThanOrEqual(Date.now() + 7 * 24 * 3600 * 1000) // within the limit
      expect(expiry.getTime()).toBeGreaterThan(Date.now() + 5 * 24 * 3600 * 1000) // and the last day it allows
    } finally {
      setTenantCapabilities(null)
    }
  })

  it('names the requirement when the server refuses a link for one', async () => {
    mintShare.mockRejectedValueOnce(httpError(422, 'link_password_required'))
    const w = mountDialog()
    await flushPromises()
    await byText(w, t('share.createNew')).trigger('click')
    await byText(w, t('share.create')).trigger('click')
    await flushPromises()
    expect(w.text()).toContain(t('share.requirementRefused'))
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
    // the close names the link where this dialog lists it and its role: its subtree re-key starts from it (ADR 0114)
    expect(revokeShare).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: FOLDER_ID, shareId: 'mine', role: ShareRole.Read })
    expect(listSharesFor).toHaveBeenCalledTimes(2)

    listSharesFor.mockResolvedValue([row({ shareId: 'theirs', mine: false })])
    const other = mountDialog()
    await flushPromises()
    expect(byLabel(other, t('share.revoke')).exists()).toBe(false)
    expect(byLabel(other, t('share.copy')).exists(), 'control: the row renders its actions').toBe(true)
  })

  // Owner decisions 2026-11-01: during a pending deletion the dialog stays to SEE the links; the server refuses every share
  // write in the grace window but a revoke (uc.spaces.scope-access A1), so it offers only that - besides the copy - and
  // says so.
  it('a pending deletion (`readonly`): the links are listed and copied, the own live one revoked, nothing else changed', async () => {
    const list = [row({ shareId: 'live' }), row({ shareId: 'off', enabled: false }), row({ shareId: 'renew', enabled: false, reissueRequired: true })]
    listSharesFor.mockResolvedValue(list)
    const w = mountDialog({ contribute: true, readonly: true })
    await flushPromises()
    await w.find('.folder-share-fold').trigger('click')
    expect(w.findAll('.folder-share-row')).toHaveLength(3)
    expect(byLabel(w, t('share.copy')).exists(), 'the live link copies').toBe(true)
    expect(w.text()).toContain(t('share.pendingDeletion'))
    expect(w.find('.folder-share-new').exists(), 'no new link').toBe(false)
    for (const label of ['share.edit', 'share.reissue', 'share.reactivate', 'share.delete']) {
      expect(byLabel(w, t(label)).exists(), label).toBe(false)
    }
    expect(w.findAll(`button[aria-label="${t('share.revoke')}"]`), 'the one live link of mine, and only it').toHaveLength(1)
    await byLabel(w, t('share.revoke')).trigger('click')
    await flushPromises()
    expect(revokeShare).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: FOLDER_ID, shareId: 'live', role: ShareRole.Read })
    // control: the same list outside a pending deletion offers them
    const live = mountDialog({ contribute: true })
    await flushPromises()
    await live.find('.folder-share-fold').trigger('click')
    expect(live.find('.folder-share-new').exists()).toBe(true)
    for (const label of ['share.edit', 'share.reissue', 'share.reactivate', 'share.revoke', 'share.delete']) {
      expect(byLabel(live, t(label)).exists(), label).toBe(true)
    }
    expect(live.text()).not.toContain(t('share.pendingDeletion'))
  })

  // ADR 0114 decision 7: the close cleared the link's key, so its reopen carries the folder's current key and name.
  it("switches a link back on with the folder's own key at its epoch, the scope key and its name, and re-reads the list", async () => {
    const off = row({ shareId: 'off', enabled: false })
    listSharesFor.mockResolvedValueOnce([off]).mockResolvedValueOnce([{ ...off, enabled: true }])
    const w = mountDialog()
    await flushPromises()
    await w.find('.folder-share-fold').trigger('click')

    await byLabel(w, t('share.reactivate')).trigger('click')
    await flushPromises()

    const reactivate = vi.mocked(manage.reactivateShare)
    expect(reactivate).toHaveBeenCalledTimes(1)
    const [share, root, epochs, name] = reactivate.mock.calls[0]
    expect(share).toMatchObject({ shareId: 'off', scopeId: SCOPE_ID, nodeId: FOLDER_ID, role: ShareRole.Read, sealedShareKey: 'k', sealedShareKeyEpoch: 0 })
    expect(root).toEqual({ key: FOLDER_KEY, epoch: FOLDER_EPOCH })
    expect(root.key).toBe(FOLDER_KEY)
    expect(epochs).toEqual(EPOCHS)
    expect(name).toBe('Trips')
    expect(listSharesFor).toHaveBeenCalledTimes(2)
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

    expect(deleteShare).toHaveBeenCalledWith(expect.objectContaining({ scopeId: SCOPE_ID, nodeId: FOLDER_ID, shareId: 'off', role: ShareRole.Read, enabled: false }))
    expect(w.findAll('.folder-share-row')).toHaveLength(0)
  })

  // ADR 0114 decision 9: a switch-off re-keys what lies below the folder in the background; the link's row says so while
  // it runs, its fold opened so the row is in view. Control: a switched-off link not re-keying here says nothing.
  it("after a switch-off the fold opens and the link's row says what lies below it gets a new lock, while that runs", async () => {
    listSharesFor.mockResolvedValueOnce([row({ shareId: 'mine' }), row({ shareId: 'old', enabled: false })])
      .mockResolvedValue([row({ shareId: 'mine', enabled: false }), row({ shareId: 'old', enabled: false })])
    revokeShare.mockImplementation(async (link) => { manage.rekeyingClosedLinks.add(link.shareId) })
    const w = mountDialog()
    await flushPromises()
    expect(w.findAll('.folder-share-row')).toHaveLength(1) // the switched-off one folded

    await byLabel(w, t('share.revoke')).trigger('click')
    await flushPromises()
    const rows = w.findAll('.folder-share-row')
    expect(rows).toHaveLength(2)
    const said = (r: (typeof rows)[number]) => r.find('.folder-share-rekeying').exists()
    expect(rows.map(said)).toEqual([true, false])
    expect(rows[0].text()).toContain(t('share.closedRekeying'))

    manage.rekeyingClosedLinks.delete('mine') // the re-key ended
    await flushPromises()
    expect(w.find('.folder-share-rekeying').exists()).toBe(false)
  })

  // inbox #385 (c): an unnamed link is titled by its role, so its role badge would repeat it ("View only" twice).
  it('says an unnamed link\'s role once (its title); a named link keeps the role badge beside its name', async () => {
    listSharesFor.mockResolvedValue([row({ shareId: 'plain' }), row({ shareId: 'named', sealedShareName: 'sealed' })])
    vi.mocked(manage.readShareName).mockImplementation((_e, a) => (a.shareId === 'named' ? 'For Grandma' : ''))
    const w = mountDialog()
    await flushPromises()
    const [plain, named] = w.findAll('.folder-share-row')
    expect(plain.find('.veyq-row-title').text()).toBe(t('share.roleViewer'))
    expect(plain.text().split(t('share.roleViewer')).length - 1, 'the role said once').toBe(1)
    expect(named.find('.veyq-row-title').text()).toBe('For Grandma')
    expect(named.find('.folder-share-role').text()).toBe(t('share.roleViewer'))
  })
})

// inbox #457: a link the server says serves no guest says why on its row; a fixable requirement opens this dialog's
// own edit, and a link older than the lifetime its renew.
describe('FolderShareDialog — a link that serves no guest says why (#457)', () => {
  it('names the reasons on the row and offers the edit that sets an end date', async () => {
    listSharesFor.mockResolvedValue([
      row({ shareId: 'stopped', createdAt: new Date(), guestStops: [{ reason: 'instanceSwitch', capabilityId: 'core.public-shares.contribute' }, { reason: 'linkMaxDays', maxDays: 7 }] }),
      row({ shareId: 'served' }),
    ])
    const w = mountDialog()
    await flushPromises()

    const rows = w.findAll('.folder-share-row')
    expect(rows[0]!.findAll('.share-guest-stop').map((s) => s.text())).toEqual([
      t('share.guestStops.instanceSwitch', { what: t('share.guestStops.entry.contribute') }),
      t('share.guestStops.linkMaxDays', { days: 7 }),
    ])
    expect(rows[1]!.find('.share-guest-stops').exists()).toBe(false)

    expect(rows[0]!.find('.share-guest-stops-edit').text()).toBe(t('share.guestStops.linkMaxDaysFix'))
    // (that it opens the edit is FolderShareDialog.edit.test.ts's, which mounts the edit view)
  })

  it('a link older than the lifetime asks for its renew', async () => {
    listSharesFor.mockResolvedValue([
      row({ shareId: 'old', createdAt: new Date(Date.now() - 40 * 24 * 3600 * 1000), guestStops: [{ reason: 'linkMaxDays', maxDays: 30 }] }),
    ])
    const w = mountDialog()
    await flushPromises()
    await w.find('.share-guest-stops-renew').trigger('click')
    expect(confirmRequest.value?.header).toBe(t('share.reissueTitle'))
    settleConfirm(false)
  })
})
