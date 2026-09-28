// @vitest-environment happy-dom
// The share CREATE/EDIT dialog is shared by both flows via the `uploader` flag. The NORMAL flow shows a
// VIEWER/CONTRIBUTOR role switcher (never the uploader — that is its own action/dialog); the UPLOADER flow hides
// the switcher entirely (role fixed to UPLOADER). cr-share-link-expiry-ui/-edit adds an optional expiry on both
// create and edit: the picked day is sent at its END (works through that day), and on edit it is a FULL REPLACE
// (pre-filled from the current share, always re-sent — an edit that drops it clears it).
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// The dialog decrypts an editShare on mount; stub the share data layer so it mounts crypto/network-free.
const createShare = vi.fn().mockResolvedValue({ shareId: 's1', kShare: new Uint8Array(32) })
const updateShare = vi.fn().mockResolvedValue(undefined)
// The dialog assembles the link itself now (the origin is a UI concern), so `shareLink` is its own stub.
vi.mock('../shareLink', () => ({
  buildShareLink: vi.fn((shareId: string) => `https://x/s/${shareId}#k=abc`),
}))
vi.mock('../share', () => ({
  createShare: (...a: unknown[]) => createShare(...a),
  updateShare: (...a: unknown[]) => updateShare(...a),
  readShareName: vi.fn(() => ''),
  readShareNoteMap: vi.fn(() => ({})),
  readSharePassword: vi.fn(() => ''),
  hasLocaleText: vi.fn(() => false),
}))

import ShareCreateDialog from './ShareCreateDialog.vue'
import { ShareRole } from '../../../api/generated/sharing'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  SelectButton: {
    props: ['options'],
    template: '<div class="select-button"><span v-for="o in options" :key="o.value" class="opt">{{ o.label }}</span></div>',
  },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: true,
  // Controllable toggle/date stubs so a test can enable expiry + pick a date; $attrs forwards the aria-label.
  ToggleSwitch: { props: ['modelValue'], emits: ['update:modelValue'], template: '<button v-bind="$attrs" @click="$emit(\'update:modelValue\', !modelValue)" />' },
  DatePicker: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="datepicker" />' },
  LocaleMarkdownEditor: true,
}

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000cc'
const EPOCHS = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) }

function mountDialog(props: Record<string, unknown>) {
  return mount(ShareCreateDialog, {
    props: { scopeId: SCOPE_ID, nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), epochs: EPOCHS, memoryName: 'Rom', ...props },
    global: { plugins: [realI18n()], stubs },
  })
}

const editShareWith = (over: Record<string, unknown>) => ({
  shareId: 's1', scopeId: SCOPE_ID, nodeId: 'n1', role: ShareRole.Viewer, enabled: true, mine: true,
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

  it('uploader flow: no role switcher at all (role fixed to UPLOADER)', () => {
    const w = mountDialog({ uploader: true })
    expect(w.find('.select-button').exists()).toBe(false)
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
