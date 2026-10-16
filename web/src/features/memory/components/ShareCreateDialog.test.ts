// @vitest-environment happy-dom
// The share CREATE/EDIT dialog is shared by both flows via the `uploader` flag. The NORMAL flow shows a
// READ/READ_WRITE role switcher (never the uploader — that is its own action/dialog); the WRITE_ONLY flow hides
// the switcher entirely (role fixed to WRITE_ONLY). cr-share-link-expiry-ui/-edit adds an optional expiry on both
// create and edit: the picked day is sent at its END (works through that day), and on edit it is a FULL REPLACE
// (pre-filled from the current share, always re-sent — an edit that drops it clears it).
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

// The dialog decrypts an editShare on mount; stub the share data layer so it mounts crypto/network-free.
// The dialog assembles the link itself now (the origin is a UI concern), so `shareLink` is its own stub.
vi.mock('../shareLink', () => ({ buildShareLink: vi.fn() }))
vi.mock('../share', () => ({
  createShare: vi.fn(),
  updateShare: vi.fn(),
  reissueShare: vi.fn(),
  isShareDeleted: vi.fn(),
  readShareName: vi.fn(),
  readShareNote: vi.fn(),
  readSharePassword: vi.fn(),
  hasLocaleText: vi.fn(),
}))

import ShareCreateDialog from './ShareCreateDialog.vue'
import { ShareRole } from '../../../api/generated/sharing'
import { realI18n, t } from '../../../test/i18n'
import { buildShareLink } from '../shareLink'
import * as share from '../share'
import { setTenantCapabilities } from '../../../shared/state/useCapabilities'

const createShare = vi.mocked(share.createShare).mockResolvedValue({ shareId: 's1', kShare: new Uint8Array(32) })
const updateShare = vi.mocked(share.updateShare).mockResolvedValue(undefined)
const reissueShare = vi.mocked(share.reissueShare)
const SHARE_DELETED = new Error('409 share_deleted')
// The real predicate is share.test.ts's; here it answers for the error the stubbed reissue throws.
vi.mocked(share.isShareDeleted).mockImplementation(async (e: unknown) => e === SHARE_DELETED)
vi.mocked(share.readShareName).mockReturnValue('')
vi.mocked(share.readShareNote).mockReturnValue({ texts: {}, mustAccept: false })
vi.mocked(share.readSharePassword).mockReturnValue('')
vi.mocked(share.hasLocaleText).mockReturnValue(false)
vi.mocked(buildShareLink).mockImplementation((shareId) => `https://x/s/${shareId}#k=abc`)

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  // Each option is a button that picks it, so a test can re-grade the role.
  SelectButton: {
    props: ['options', 'modelValue'],
    emits: ['update:modelValue'],
    template: '<div class="select-button"><button v-for="o in options" :key="o.value" class="opt" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: true,
  // Controllable toggle/date stubs so a test can enable expiry + pick a date; $attrs forwards the aria-label.
  ToggleSwitch: { props: ['modelValue'], emits: ['update:modelValue'], template: '<button v-bind="$attrs" @click="$emit(\'update:modelValue\', !modelValue)" />' },
  DatePicker: { props: ['modelValue', 'maxDate'], emits: ['update:modelValue'], template: '<input class="datepicker" :data-max="maxDate ? maxDate.toDateString() : \'\'" />' },
  LocaleMarkdownEditor: true,
}

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000cc'
const EPOCHS = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) }

function mountDialog(props: Record<string, unknown>) {
  return mount(ShareCreateDialog, {
    props: { scopeId: SCOPE_ID, nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), epochs: EPOCHS, memoryName: 'Rom', contribute: true, ...props },
    global: { plugins: [realI18n()], stubs },
  })
}

const editShareWith = (over: Record<string, unknown>) => ({
  shareId: 's1', scopeId: SCOPE_ID, nodeId: 'n1', role: ShareRole.Read, enabled: true, mine: true,
  createdAt: new Date(0), expiresAt: null, sealedNote: null, sealedShareName: null, sealedShareKey: 'k',
  sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null, ...over,
})

describe('ShareCreateDialog (cr-0116)', () => {
  it('normal flow: the switcher offers view-only + view+add, never the uploader', () => {
    const w = mountDialog({})
    expect(w.find('.select-button').exists()).toBe(true)
    expect(w.text()).toContain(t('share.roleViewer'))
    expect(w.text()).toContain(t('share.roleContributor'))
    expect(w.text()).not.toContain(t('share.roleUploader'))
  })

  it('normal flow without contribute (core.public-shares.contribute off): view only; an edit of a READ_WRITE link keeps its role on offer', () => {
    const create = mountDialog({ contribute: false })
    expect(create.text()).toContain(t('share.roleViewer'))
    expect(create.text()).not.toContain(t('share.roleContributor'))
    // a feature switch refuses only what is new: the existing contributor link still shows its own role
    const edit = mountDialog({ contribute: false, editShare: editShareWith({ role: ShareRole.ReadWrite }) })
    expect(edit.text()).toContain(t('share.roleContributor'))
    // control: an edit of a READ link offers no contributor role without the leaf
    expect(mountDialog({ contribute: false, editShare: editShareWith({}) }).text()).not.toContain(t('share.roleContributor'))
  })

  it('uploader flow: no role switcher at all (role fixed to WRITE_ONLY)', () => {
    const w = mountDialog({ uploader: true })
    expect(w.find('.select-button').exists()).toBe(false)
  })

  // inbox #363: on a chapter the hints that name the node say "chapter" (the parent passes `chapter` by `nodeKind`).
  it.each([
    [{ uploader: true }, 'share.chapterRoleUploaderHint', 'share.roleUploaderHint'],
    [{}, 'share.chapterRoleViewerHint', 'share.roleViewerHint'],
  ])('a chapter link (%o) is described as a chapter', (flow, chapterKey, memoryKey) => {
    const onChapter = mountDialog({ ...flow, chapter: true })
    expect(onChapter.text()).toContain(t(chapterKey))
    expect(onChapter.text()).not.toContain(t(memoryKey))
    const onMemory = mountDialog(flow) // the control: a Memory keeps its wording
    expect(onMemory.text()).toContain(t(memoryKey))
    expect(onMemory.text()).not.toContain(t(chapterKey))
  })

  it('edit flow (normal): shows the switcher so the role can be re-graded in place (cr-0119)', () => {
    const w = mountDialog({ editShare: editShareWith({}) })
    expect(w.find('.select-button').exists()).toBe(true)
    expect(w.text()).toContain(t('share.roleViewer'))
    expect(w.text()).toContain(t('share.roleContributor'))
  })

  it('the expiry card shows on CREATE', () => {
    expect(mountDialog({}).find(`[aria-label="${t('share.expirySection')}"]`).exists()).toBe(true)
  })

  it('create with expiry: createShare gets the picked day at its END (works through that day)', async () => {
    createShare.mockClear()
    const w = mountDialog({})
    await w.find(`[aria-label="${t('share.expirySection')}"]`).trigger('click') // enable the expiry card
    const picker = w.findComponent('.datepicker') as unknown as { vm: { $emit: (e: string, v: unknown) => void } }
    picker.vm.$emit('update:modelValue', new Date(2026, 7, 1)) // 1 Aug 2026, local midnight
    await w.vm.$nextTick()
    await w.findAll('button').find((b) => b.text() === t('share.create'))!.trigger('click')

    expect(createShare).toHaveBeenCalledTimes(1)
    // the share is minted in the Memory's scope — its recovery copy of K_share is bound to (scope, node, share)
    expect(createShare.mock.calls[0].slice(0, 2)).toEqual([SCOPE_ID, 'n1'])
    // …and sealed under the scope's epoch keys, which no share link carries (ADR 0036 point 4)
    expect(createShare.mock.calls[0][3]).toStrictEqual(EPOCHS)
    const expiry = createShare.mock.calls[0][6] as Date // the 7th positional arg is expiresAt
    expect(expiry).toBeInstanceOf(Date)
    expect([expiry.getFullYear(), expiry.getMonth(), expiry.getDate()]).toEqual([2026, 7, 1])
    expect(expiry.getHours()).toBe(23) // end of the picked day, not its midnight
  })

  it('create WITHOUT enabling expiry: createShare gets null', async () => {
    createShare.mockClear()
    const w = mountDialog({})
    await w.findAll('button').find((b) => b.text() === t('share.create'))!.trigger('click')
    expect(createShare.mock.calls[0][6]).toBeNull()
  })

  it('the expiry card shows on EDIT, pre-filled; an unchanged edit re-sends it (full replace)', async () => {
    updateShare.mockClear()
    const w = mountDialog({ editShare: editShareWith({ expiresAt: new Date(2026, 7, 1, 23, 59, 59) }) })
    await flushPromises() // let onMounted's pre-fill propagate to the stubs
    expect(w.find(`[aria-label="${t('share.expirySection')}"]`).exists()).toBe(true) // shown on edit

    await w.findAll('button').find((b) => b.text() === t('share.noteSave'))!.trigger('click') // save without touching expiry
    // K_share is recovered through the epoch keys, from the row's recovery copy and its epoch (ADR 0036 point 4)
    expect(updateShare.mock.calls[0][1]).toStrictEqual(EPOCHS)
    expect(updateShare.mock.calls[0][2]).toMatchObject({ sealedShareKey: 'k', sealedShareKeyEpoch: 0 })
    const expiry = updateShare.mock.calls[0][5] as Date // updateShare arg 6 (index 5) is expiresAt
    expect([expiry.getFullYear(), expiry.getMonth(), expiry.getDate()]).toEqual([2026, 7, 1]) // preserved
  })

  it('EDIT with the expiry toggled off clears it (updateShare gets null)', async () => {
    updateShare.mockClear()
    const w = mountDialog({ editShare: editShareWith({ expiresAt: new Date(2026, 7, 1, 23, 59, 59) }) })
    await flushPromises() // pre-fill first, so the toggle sees modelValue=true
    await w.find(`[aria-label="${t('share.expirySection')}"]`).trigger('click') // toggle the pre-filled expiry OFF
    await w.findAll('button').find((b) => b.text() === t('share.noteSave'))!.trigger('click')
    expect(updateShare.mock.calls[0][5]).toBeNull()
  })
})

// Inbox #252 (ADR 0075 point 1): a share is re-issued at most once. A renew that lost to another renew — or to a
// delete — mints nothing; the dialog says so, has the list re-read, and offers no retry that could only fail again.
describe('ShareCreateDialog — a renew that lost (#252)', () => {
  const submit = (w: ReturnType<typeof mountDialog>) =>
    w.findAll('button').find((b) => b.text() === t('share.reissueSubmit'))!

  it('on 409 share_deleted: re-reads the list, names what happened, shows no link and blocks a retry', async () => {
    reissueShare.mockReset().mockRejectedValueOnce(SHARE_DELETED)
    const w = mountDialog({ reissueFrom: editShareWith({ enabled: false }) })
    await submit(w).trigger('click')
    await flushPromises()

    expect(w.emitted('saved')).toHaveLength(1)
    expect(w.text()).toContain(t('share.reissueAlreadyReplaced'))
    expect(w.text()).not.toContain(t('share.reissueFailed'))
    expect(w.text()).not.toContain('https://x/s/')
    await submit(w).trigger('click')
    await flushPromises()
    expect(reissueShare).toHaveBeenCalledTimes(1)
  })

  it('control: any other refusal is the plain failure, the list is not re-read, and a retry is allowed', async () => {
    reissueShare.mockReset().mockRejectedValueOnce(new Error('500')).mockResolvedValueOnce({ shareId: 's2', kShare: new Uint8Array(32) })
    const w = mountDialog({ reissueFrom: editShareWith({ enabled: false }) })
    await submit(w).trigger('click')
    await flushPromises()

    expect(w.emitted('saved')).toBeUndefined()
    expect(w.text()).toContain(t('share.reissueFailed'))
    expect(w.text()).not.toContain(t('share.reissueAlreadyReplaced'))
    await submit(w).trigger('click')
    await flushPromises()
    expect(reissueShare).toHaveBeenCalledTimes(2)
    expect(w.emitted('saved')).toHaveLength(1)
  })
})

// #303 (ADR 0076 point 5): a link minted before the write verifier takes no new guest as a READ_WRITE, and an edit
// never adds a verifier (ADR 0076 point 2) — so re-grading such a link to READ_WRITE warns, and offers the renew that
// stores one. The server's `writeVerifierMissing` is the fact; the verifier itself is never on the wire.
describe('ShareCreateDialog — a role edit onto a link that holds no verifier (#303)', () => {
  const pick = (w: ReturnType<typeof mountDialog>, label: string) =>
    w.findAll('.select-button button').find((b) => b.text() === label)!.trigger('click')
  const warning = (w: ReturnType<typeof mountDialog>) => w.find('.share-role-needs-reissue')

  it('warns once READ_WRITE is picked on a READ link without a verifier, and its renew asks for that share', async () => {
    const s = editShareWith({ writeVerifierMissing: true })
    const w = mountDialog({ editShare: s })
    await flushPromises()
    expect(warning(w).exists()).toBe(false) // as a READ link it takes no guest, so there is nothing to warn about
    await pick(w, t('share.roleContributor'))
    expect(warning(w).text()).toContain(t('share.roleNeedsReissue'))
    await warning(w).findAll('button').find((b) => b.text() === t('share.reissue'))!.trigger('click')
    expect(w.emitted('reissue')).toEqual([[s]])
    await pick(w, t('share.roleViewer'))
    expect(warning(w).exists()).toBe(false)
  })

  it('warns on a READ_WRITE link without a verifier as soon as it opens', async () => {
    const w = mountDialog({ editShare: editShareWith({ role: ShareRole.ReadWrite, writeVerifierMissing: true }) })
    await flushPromises()
    expect(warning(w).text()).toContain(t('share.roleNeedsReissue'))
  })

  it('control: a link that holds its verifier switches to READ_WRITE without a warning', async () => {
    const w = mountDialog({ editShare: editShareWith({ writeVerifierMissing: false }) })
    await flushPromises()
    await pick(w, t('share.roleContributor'))
    expect(warning(w).exists()).toBe(false)
  })
})

// uc.sharing.share-note: the note's acceptance switch is core's field (`ShareNoteAcceptField`) under the note editor,
// read back on edit and sealed inside the note — the Memory dialog hands it on exactly as core's folder dialog does.
describe("ShareCreateDialog — the note's acceptance switch", () => {
  // The real Checkbox needs PrimeVue's config, which the stubbed suite above does not install.
  const mountWithPrime = (props: Record<string, unknown>) =>
    mount(ShareCreateDialog, {
      props: { scopeId: SCOPE_ID, nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), epochs: EPOCHS, memoryName: 'Rom', contribute: true, ...props },
      global: { plugins: [realI18n(), PrimeVue], stubs },
    })
  const mustAccept = (w: ReturnType<typeof mountWithPrime>) => w.find('#share-note-must-accept')

  it('is read back on edit and re-sent with the note', async () => {
    updateShare.mockClear()
    vi.mocked(share.readShareNote).mockReturnValueOnce({ texts: { en: 'Read me' }, mustAccept: true })
    vi.mocked(share.hasLocaleText).mockReturnValueOnce(true)
    const w = mountWithPrime({ editShare: editShareWith({ sealedNote: 'n' }) })
    await flushPromises()
    expect((mustAccept(w).element as HTMLInputElement).checked).toBe(true)
    await w.findAll('button').find((b) => b.text() === t('share.noteSave'))!.trigger('click')
    await flushPromises()
    expect(updateShare.mock.calls[0][3]).toEqual({ texts: { en: 'Read me' }, mustAccept: true })
  })

  it('a new link with a note and the switch ticked asks to be accepted', async () => {
    createShare.mockClear()
    const w = mountWithPrime({})
    await w.find(`[aria-label="${t('share.noteSection')}"]`).trigger('click')
    w.findComponent({ name: 'LocaleMarkdownEditor' }).vm.$emit('update:modelValue', { en: 'Read me first' })
    await flushPromises()
    await mustAccept(w).setValue(true)
    await w.findAll('button').find((b) => b.text() === t('share.create'))!.trigger('click')
    await flushPromises()
    expect(createShare.mock.calls[0][7]).toEqual({ texts: { en: 'Read me first' }, mustAccept: true })
  })

  it('a space\'s link requirements turn the password and end-date cards on, lock them and limit the picker (ADR 0088 decision 8)', async () => {
    setTenantCapabilities([{ id: 'core.public-shares', linkPassword: true, linkMaxDays: 7 }])
    try {
      createShare.mockClear()
      const w = mountDialog({})
      await flushPromises()
      expect(w.text()).toContain(t('share.passwordRequiredByRule'))
      expect(w.text()).toContain(t('share.expiryRequiredByRule', { days: 7 }))
      expect(w.find(`[aria-label="${t('share.passwordSection')}"]`).attributes('disabled')).toBeDefined()
      expect(w.find(`[aria-label="${t('share.expirySection')}"]`).attributes('disabled')).toBeDefined()
      // The picker ends on the last day a link made now may run, and starts filled with it.
      const last = new Date(Date.now() + 7 * 24 * 3600 * 1000)
      last.setDate(last.getDate() - 1)
      expect(w.find('.datepicker').attributes('data-max')).toBe(last.toDateString())
      // Without the required password the create does nothing.
      await w.findAll('button').find((b) => b.text() === t('share.create'))!.trigger('click')
      await flushPromises()
      expect(createShare).not.toHaveBeenCalled()
    } finally {
      setTenantCapabilities(null)
    }
  })

  it('control: without requirements both cards start off and unlocked', async () => {
    const w = mountDialog({})
    await flushPromises()
    expect(w.text()).not.toContain(t('share.passwordRequiredByRule'))
    expect(w.find(`[aria-label="${t('share.passwordSection')}"]`).attributes('disabled')).toBeUndefined()
  })
})
