// @vitest-environment happy-dom
// inbox #364: a member edits an existing folder link in place — label, role, note, password, expiry — with
// `updateShare` on the same link, offered on its own links only (the server lets only a link's owner edit it, ADR 0036
// point 3). The real layer buttons, inputs and switches are mounted and clicked with real DOM clicks (src/web/CLAUDE.md:
// a test that asserts what a handler calls has not clicked anything); only the Dialog (it teleports), the date picker
// and the note editor are stubbed. The data layer is stubbed as in `FolderShareDialog.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn() }))
vi.mock('./manageShares', () => ({
  mintShare: vi.fn(),
  remintShare: vi.fn(),
  updateShare: vi.fn(),
  listSharesFor: vi.fn(),
  revokeShare: vi.fn(),
  reactivateShare: vi.fn(),
  deleteShare: vi.fn(),
  isReissueRequired: vi.fn(),
  isShareDeleted: vi.fn(),
  readShareName: vi.fn(),
  readShareNote: vi.fn(),
  readSharePassword: vi.fn(),
  hasLocaleText: vi.fn(),
}))

import FolderShareDialog from './FolderShareDialog.vue'
import * as manage from './manageShares'
import { recoverShareLink } from './shareLink'
import { ShareRole } from '../../api/generated/sharing'
import { confirmRequest, settleConfirm } from '../../shared/state/useConfirm'
import { realI18n, t } from '../../test/i18n'
import { setTenantCapabilities } from '../../shared/state/useCapabilities'

const updateShare = vi.mocked(manage.updateShare)
const listSharesFor = vi.mocked(manage.listSharesFor)

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000dd'
const FOLDER_ID = '5c09e000-0000-4000-8000-0000000000f0'
const EPOCHS = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) }
const EXPIRES = new Date('2027-03-01T22:59:59.999Z')

const row = (over: Partial<manage.ManagedShare>): manage.ManagedShare => ({
  shareId: 's-own', scopeId: SCOPE_ID, nodeId: FOLDER_ID, role: ShareRole.Read, enabled: true, mine: true,
  createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null, sealedNote: null, sealedShareName: 'sealed',
  sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
  reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
  ...over,
})

function mountDialog(extra: { contribute?: boolean } = { contribute: true }) {
  return mount(FolderShareDialog, {
    attachTo: document.body,
    props: { scopeId: SCOPE_ID, nodeId: FOLDER_ID, nodeKey: new Uint8Array(32), epochs: EPOCHS, nodeName: 'Trips', nodeKind: 'FOLDER', ...extra },
    global: {
      plugins: [realI18n()],
      stubs: {
        VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible"><h1 class="header">{{ header }}</h1><slot /></div>' },
        VeyqDateField: { props: ['modelValue'], template: '<input class="datepicker" :value="modelValue" />' },
        LocaleMarkdownEditor: true,
      },
    },
  })
}
type W = ReturnType<typeof mountDialog>
const click = (el: Element) => el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
const byLabel = (w: W, label: string) => w.findAll(`button[aria-label="${label}"]`)
const byText = (w: W, text: string) => w.findAll('button').find((b) => b.text() === text)
/** A `VeyqSwitch` is a checkbox input carrying the switch's aria-label. */
const toggle = (w: W, label: string) => w.find(`input[role="switch"][aria-label="${label}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  settleConfirm(false)
  vi.mocked(recoverShareLink).mockImplementation((a) => `https://x/s/${a.shareId}#k=rec`)
  vi.mocked(manage.readShareName).mockImplementation((_e, _a, _copy, sealed) => (sealed ? 'Family' : ''))
  vi.mocked(manage.readShareNote).mockReturnValue({ texts: { en: 'Hello' }, mustAccept: false })
  vi.mocked(manage.hasLocaleText).mockImplementation((m) => Object.values(m).some((v) => !!v))
  vi.mocked(manage.readSharePassword).mockReturnValue('')
  updateShare.mockResolvedValue(undefined)
})

describe('FolderShareDialog — edit a link in place (#364)', () => {
  it("offers the edit on the caller's own links only — not on a co-member's, nor on one without a recovery copy", async () => {
    listSharesFor.mockResolvedValue([
      row({ shareId: 's-own' }),
      row({ shareId: 's-other', mine: false, sealedShareName: null }),
      row({ shareId: 's-nocopy', sealedShareKey: null, sealedShareName: null }),
    ])
    const w = mountDialog()
    await flushPromises()
    const rows = w.findAll('.folder-share-row')
    expect(rows.map((r) => r.find(`button[aria-label="${t('share.edit')}"]`).exists())).toEqual([true, false, false])
  })

  it.each([
    [ShareRole.ReadWrite, [t('share.roleViewer'), t('share.folder.roleContributor')]],
    [ShareRole.Read, [t('share.roleViewer')]], // control: the same dialog on a READ link offers no contributor role
  ])('without contribute (core.public-shares.contribute off), the edit of a %s link offers %j: a switch refuses only what is new', async (role, offered) => {
    listSharesFor.mockResolvedValue([row({ role })])
    const w = mountDialog({})
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    expect(w.findAll('.folder-share-roles button').map((b) => b.text())).toEqual(offered)
  })

  it('opens the form pre-filled from the link and saves it with updateShare on the same link — no new link', async () => {
    const share = row({ expiresAt: EXPIRES })
    listSharesFor.mockResolvedValue([share])
    const w = mountDialog()
    await flushPromises()

    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    expect(w.find('.header').text()).toBe(t('share.editTitle'))
    expect((w.find('#folder-share-name').element as HTMLInputElement).value).toBe('Family')
    expect((toggle(w, t('share.noteSection')).element as HTMLInputElement).checked, 'the note is on').toBe(true)
    expect((toggle(w, t('share.expirySection')).element as HTMLInputElement).checked, 'the expiry is on').toBe(true)
    expect((toggle(w, t('share.passwordSection')).element as HTMLInputElement).checked, 'no password').toBe(false)

    await w.find('#folder-share-name').setValue('Grandparents')
    click(w.findAll('.folder-share-roles button').find((b) => b.text() === t('share.folder.roleContributor'))!.element)
    await flushPromises()
    expect(w.text()).toContain(t('share.folder.roleContributorHint'))
    click(byText(w, t('common.save'))!.element)
    await flushPromises()

    expect(updateShare).toHaveBeenCalledTimes(1)
    const [address, epochs, copy, note, name, expiresAt, role, passwordChange] = updateShare.mock.calls[0]
    expect(address, 'the link and the folder this dialog was opened for').toEqual({ scopeId: SCOPE_ID, nodeId: FOLDER_ID, shareId: 's-own' })
    expect(epochs).toEqual(EPOCHS)
    expect(copy, 'its recovery copy re-opens K_share').toEqual(share)
    expect(note, 'the note is re-sent').toEqual({ texts: { en: 'Hello' }, mustAccept: false })
    expect(name).toBe('Grandparents')
    expect(expiresAt?.getTime(), 'the expiry is re-sent, so an unrelated edit keeps it').toBe(new Date(new Date(EXPIRES).setHours(23, 59, 59, 999)).getTime())
    expect(role).toBe(ShareRole.ReadWrite)
    expect(passwordChange, 'no password was touched').toBeUndefined()
    expect(manage.mintShare).not.toHaveBeenCalled()
    expect(manage.remintShare).not.toHaveBeenCalled()
    expect(w.find('.header').text(), 'back on the list').toBe(t('share.folder.title'))
    expect(listSharesFor, 'which is re-read').toHaveBeenCalledTimes(2)
  })

  it('switching the password off removes it; switching the note off clears it', async () => {
    vi.mocked(manage.readSharePassword).mockReturnValue('correct horse battery')
    listSharesFor.mockResolvedValue([row({ passwordRequired: true, sealedPassword: 'p' })])
    const w = mountDialog()
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    expect((w.find('#folder-share-password').element as HTMLInputElement).value, 'the password is pre-filled').toBe('correct horse battery')

    click(toggle(w, t('share.passwordSection')).element)
    click(toggle(w, t('share.noteSection')).element)
    await flushPromises()
    click(byText(w, t('common.save'))!.element)
    await flushPromises()
    const [, , , note, , , , passwordChange] = updateShare.mock.calls[0]
    expect(passwordChange).toEqual({ remove: true })
    expect(note).toEqual({ texts: {}, mustAccept: false })
  })

  it('a changed password is set; a password the copy cannot open blocks the save until one is typed', async () => {
    listSharesFor.mockResolvedValue([row({ passwordRequired: true, sealedPassword: null })])
    const w = mountDialog()
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    expect((toggle(w, t('share.passwordSection')).element as HTMLInputElement).checked, 'the protection stays on').toBe(true)
    expect(byText(w, t('common.save'))!.attributes('disabled'), 'never quietly drops it').toBeDefined()

    await w.find('#folder-share-password').setValue('a brand new password')
    click(byText(w, t('common.save'))!.element)
    await flushPromises()
    expect(updateShare.mock.calls[0][7]).toEqual({ set: 'a brand new password' })
  })

  it('a refused save says so and stays on the form', async () => {
    updateShare.mockRejectedValue(new Error('404'))
    listSharesFor.mockResolvedValue([row({})])
    const w = mountDialog()
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    click(byText(w, t('common.save'))!.element)
    await flushPromises()
    expect(w.text()).toContain(t('share.editFailed'))
    expect(w.find('.header').text()).toBe(t('share.editTitle'))
  })

  it('cancel goes back to the list and saves nothing', async () => {
    listSharesFor.mockResolvedValue([row({})])
    const w = mountDialog()
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    click(byText(w, t('common.cancel'))!.element)
    await flushPromises()
    expect(updateShare).not.toHaveBeenCalled()
    expect(w.find('.header').text()).toBe(t('share.folder.title'))
  })

  // #303 (ADR 0076 point 5): an edit cannot add a write verifier, so such a link graded READ_WRITE offers the renew.
  it('a link without a write verifier, switched to contribute, warns and hands over to the renew', async () => {
    listSharesFor.mockResolvedValue([row({ writeVerifierMissing: true })])
    const w = mountDialog()
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    expect(w.text()).not.toContain(t('share.roleNeedsReissue'))
    click(w.findAll('.folder-share-roles button').find((b) => b.text() === t('share.folder.roleContributor'))!.element)
    await flushPromises()
    expect(w.text()).toContain(t('share.roleNeedsReissue'))
    click(byText(w, t('share.reissue'))!.element)
    await flushPromises()
    const asked = confirmRequest.value?.header
    settleConfirm(false)
    expect(asked).toBe(t('share.reissueTitle'))
    expect(updateShare).not.toHaveBeenCalled()
  })
})

// uc.sharing.share-note: the note's acceptance switch — a checkbox under the note editor, off by default, enabled only
// while the note has text, read back on edit from the opened note and sealed inside it.
describe("FolderShareDialog — the note's acceptance switch", () => {
  const mustAccept = (w: W) => w.find('#share-note-must-accept')

  it('is read back on edit and re-sent; unticked, the note is saved without it', async () => {
    vi.mocked(manage.readShareNote).mockReturnValue({ texts: { en: 'Hello' }, mustAccept: true })
    listSharesFor.mockResolvedValue([row({})])
    const w = mountDialog()
    await flushPromises()
    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    expect((mustAccept(w).element as HTMLInputElement).checked, 'read back from the opened note').toBe(true)
    expect(mustAccept(w).attributes('disabled'), 'the note has text').toBeUndefined()

    click(byText(w, t('common.save'))!.element)
    await flushPromises()
    expect(updateShare.mock.calls[0][3], 'an unrelated save keeps it').toEqual({ texts: { en: 'Hello' }, mustAccept: true })

    click(byLabel(w, t('share.edit'))[0].element)
    await flushPromises()
    click(mustAccept(w).element)
    await flushPromises()
    click(byText(w, t('common.save'))!.element)
    await flushPromises()
    expect(updateShare.mock.calls[1][3]).toEqual({ texts: { en: 'Hello' }, mustAccept: false })
  })

  it('a new link starts with it off, and it stays disabled until the note has text', async () => {
    listSharesFor.mockResolvedValue([])
    vi.mocked(manage.mintShare).mockResolvedValue({ shareId: 'new', kShare: new Uint8Array(32) })
    const w = mountDialog()
    await flushPromises()
    click(byText(w, t('share.createNew'))!.element)
    await flushPromises()
    click(toggle(w, t('share.noteSection')).element)
    await flushPromises()
    expect((mustAccept(w).element as HTMLInputElement).checked, 'off by default').toBe(false)
    expect(mustAccept(w).attributes('disabled'), 'no text, nothing to accept').toBeDefined()

    w.findComponent({ name: 'LocaleMarkdownEditor' }).vm.$emit('update:modelValue', { en: 'Read me first' })
    await flushPromises()
    expect(mustAccept(w).attributes('disabled')).toBeUndefined()
    click(mustAccept(w).element)
    await flushPromises()
    click(byText(w, t('share.create'))!.element)
    await flushPromises()
    expect(vi.mocked(manage.mintShare).mock.calls[0][7]).toEqual({ texts: { en: 'Read me first' }, mustAccept: true })
  })
})

// inbox #457 (owner, 2026-10-16): a link a password requirement stopped is given one from its row and stays the SAME
// link: the row's fix opens this dialog's edit with the password locked on, and the save sets it with updateShare on
// that link — never a new one.
describe('FolderShareDialog — a stopped link gets its password back on the same link (#457)', () => {
  it('"Add a password" opens the edit, which waits for a password and then sets it on that link', async () => {
    setTenantCapabilities([{ id: 'core.public-shares', linkPassword: true }])
    try {
      listSharesFor.mockResolvedValue([row({ shareId: 's-stopped', createdAt: new Date(), guestStops: [{ reason: 'linkPassword' }] })])
      const w = mountDialog()
      await flushPromises()
      const fix = w.find('.share-guest-stops-edit')
      expect(fix.text()).toBe(t('share.guestStops.linkPasswordFix'))
      click(fix.element)
      await flushPromises()
      expect(w.find('.header').text()).toBe(t('share.editTitle'))
      const pw = toggle(w, t('share.passwordSection')).element as HTMLInputElement
      expect(pw.checked, 'the password is on').toBe(true)
      expect(pw.disabled, 'and cannot be switched off').toBe(true)
      expect(byText(w, t('common.save'))!.attributes('disabled'), 'no save without a password').toBeDefined()

      await w.find('#folder-share-password').setValue('a long new password')
      click(byText(w, t('common.save'))!.element)
      await flushPromises()
      expect(updateShare).toHaveBeenCalledTimes(1)
      const [address, , copy, , , , , passwordChange] = updateShare.mock.calls[0]
      expect(address.shareId, 'the same link').toBe('s-stopped')
      expect(copy).toMatchObject({ shareId: 's-stopped' })
      expect(passwordChange).toEqual({ set: 'a long new password' })
      expect(manage.mintShare).not.toHaveBeenCalled()
      expect(manage.remintShare).not.toHaveBeenCalled()
      w.unmount()
    } finally {
      setTenantCapabilities(null)
    }
  })
})
