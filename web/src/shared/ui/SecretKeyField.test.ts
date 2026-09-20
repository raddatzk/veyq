// @vitest-environment happy-dom
// uc.account.unlock § One Secret-Key control — the shared Secret-Key field's own harness.
//
// The control exists because the import affordance had been copy-pasted onto one of five screens, so what this
// file owns is the three ways in (upload, scan, paste) and the two things that must NOT happen: a wrong-account
// kit must not block, and the paste normalisation must not rewrite a key that was never a kit token.
//
// `parseKit` and `kitPayload` are the REAL ones — the paste path is only worth asserting against the format the
// kit generator actually emits, and a hand-rolled fake token would assert the fake. Only `importKitFromFile` is
// mocked: its file/QR decoding is `emergencyKit.test.ts`'s subject, and what belongs here is what this
// component does with a kit once one is found (and with `null` when none is).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('../../features/account/emergencyKit/emergencyKit', async (orig) => ({
  ...(await orig<typeof import('../../features/account/emergencyKit/emergencyKit')>()),
  importKitFromFile: vi.fn(),
}))

import SecretKeyField from './SecretKeyField.vue'
import { importKitFromFile, kitPayload, type KitData } from '../../features/account/emergencyKit/emergencyKit'
import { i18n } from '../../i18n'

const KIT: KitData = { email: 'kevin@example.com', secretKey: 'dGhlLXNlY3JldC1rZXk=' }

const stubs = {
  // `name`, `type`, `id` and the rest fall through as attributes — which is how every host's test finds this
  // field by `input[name="secret-key"]`.
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  KitScanner: true,
}

const mounted: VueWrapper[] = []

function mountField(props: Record<string, unknown> = {}): VueWrapper {
  const w = mount(SecretKeyField, {
    props: { modelValue: '', ...props },
    global: { plugins: [i18n], stubs },
  })
  mounted.push(w)
  return w
}

const field = (w: VueWrapper) => w.find('input[name="secret-key"]')
const fileField = (w: VueWrapper) => w.find('input[type="file"]')
const iconLabelled = (w: VueWrapper, label: string) =>
  w.findAll('button').find((b) => b.attributes('aria-label') === i18n.global.t(label))
const messages = (w: VueWrapper) => w.findAll('.msg')

/** Hand the hidden picker a file the way the OS dialog would — `setValue` refuses to write a file input. */
async function pick(w: VueWrapper, file: File) {
  const input = fileField(w).element as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [file], writable: true, configurable: true })
  await fileField(w).trigger('change')
  await flushPromises()
}

const someFile = () => new File(['irrelevant — importKitFromFile is mocked'], 'kit.pdf')

describe('SecretKeyField (uc.account.unlock § One Secret-Key control)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    // The scan icon is capability-gated on a camera; happy-dom has none, so it would not render at all.
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: vi.fn() }, configurable: true })
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
  })

  it('offers all three ways in — and the upload fills the key and hands the kit up', async () => {
    const w = mountField()

    expect(iconLabelled(w, 'unlock.upload')).toBeDefined()
    expect(iconLabelled(w, 'unlock.scan')).toBeDefined()
    expect(fileField(w).attributes('accept')).toBe('application/pdf,image/*')

    vi.mocked(importKitFromFile).mockResolvedValue(KIT)
    await pick(w, someFile())

    // Both halves: the key lands in the model, and the e-mail is handed to the host, which is what lets the
    // sign-in screen fill its address field and the invite screen check the one it already knows.
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([KIT.secretKey])
    expect(w.emitted('kit')).toEqual([[KIT]])
    expect(messages(w)).toHaveLength(0)
  })

  it('says a kit could not be read, and says nothing about why — with the readable one as its control', async () => {
    const w = mountField()

    vi.mocked(importKitFromFile).mockResolvedValue(null)
    await pick(w, someFile())

    const shown = messages(w)
    expect(shown).toHaveLength(1)
    expect(shown[0].text()).toBe(i18n.global.t('unlock.kitUnreadable'))
    expect(shown[0].attributes('data-severity')).toBe('error')
    // Nothing was guessed into the field, and nothing was handed up.
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect(w.emitted('kit')).toBeUndefined()

    // The control on the same predicate: a message that rendered unconditionally would satisfy the half above
    // perfectly, and would then be on screen for every successful import too.
    const ok = mountField()
    vi.mocked(importKitFromFile).mockResolvedValue(KIT)
    await pick(ok, someFile())
    expect(messages(ok)).toHaveLength(0)
  })

  it('accepts a pasted kit token in place of the key — and leaves a key that is not one alone', async () => {
    const w = mountField()

    await field(w).setValue(kitPayload(KIT))
    await flushPromises()

    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([KIT.secretKey])
    expect(w.emitted('kit')).toEqual([[KIT]])

    // The control the first half cannot give itself: a normaliser that rewrote whatever it was handed would
    // pass above and destroy every typed Secret Key. A bare key must come out byte-identical.
    const typed = mountField()
    await field(typed).setValue(KIT.secretKey)
    await flushPromises()
    expect(typed.emitted('update:modelValue')).toEqual([[KIT.secretKey]])
    expect(typed.emitted('kit')).toBeUndefined()
  })

  it('warns when the kit is for another account — and does not block, clear or refuse it', async () => {
    const w = mountField({ expectedEmail: 'someone.else@example.com' })

    vi.mocked(importKitFromFile).mockResolvedValue(KIT)
    await pick(w, someFile())

    const shown = messages(w)
    expect(shown).toHaveLength(1)
    expect(shown[0].attributes('data-severity')).toBe('warn')
    expect(shown[0].text()).toContain(KIT.email)
    expect(shown[0].text()).toContain('someone.else@example.com')
    // The part that makes it a warning rather than a check: the key is still applied and still handed up, so
    // whatever the host's submit was gated on is gated on no differently than before.
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([KIT.secretKey])
    expect(w.emitted('kit')).toEqual([[KIT]])

    // Two controls, because two different things would produce that warning for the wrong reason: a warning
    // that fires whenever a kit arrives, and one that fires whenever the host passes an address at all.
    const matching = mountField({ expectedEmail: 'KEVIN@Example.com  ' })
    await pick(matching, someFile())
    expect(messages(matching)).toHaveLength(0)

    const unknown = mountField()
    await pick(unknown, someFile())
    expect(messages(unknown)).toHaveLength(0)
  })

  it('hands back a scanned payload it does not recognise, and keeps the caller’s predicate', async () => {
    const accepts = (text: string) => text.startsWith('veyqkit:') || text.startsWith('veyqpair:')
    const w = mountField({ accepts })

    await iconLabelled(w, 'unlock.scan')!.trigger('click')
    const scanner = w.findComponent({ name: 'KitScanner' })
    // ADR 0059 §1 — one scan entry, and recognising a payload stays the caller's job. Asserted through the
    // predicate the scanner actually receives rather than by identity, because what must hold is that the
    // caller's answer reaches the camera: a kit-only predicate here would make it report a pairing code as a
    // failure, and a blanket `true` would make it stop on the first foreign QR in the room.
    const reaches = scanner.props('accepts') as (text: string) => boolean
    expect(reaches('veyqpair:aGFuZGxl')).toBe(true)
    expect(reaches('https://example.com/')).toBe(false)
    // The control: without a caller predicate the default is kit-only, so the widening above is the host's and
    // not something this component does for everyone.
    const bare = mountField()
    await iconLabelled(bare, 'unlock.scan')!.trigger('click')
    const byDefault = bare.findComponent({ name: 'KitScanner' }).props('accepts') as (text: string) => boolean
    expect(byDefault(kitPayload(KIT))).toBe(true)
    expect(byDefault('veyqpair:aGFuZGxl')).toBe(false)

    scanner.vm.$emit('found', 'veyqpair:aGFuZGxl')
    await flushPromises()

    expect(w.emitted('scanned')).toEqual([['veyqpair:aGFuZGxl']])
    expect(w.emitted('kit')).toBeUndefined()
    expect(w.emitted('update:modelValue')).toBeUndefined()

    // The control: the payload this control DOES own never reaches the host as a foreign string.
    const kitScan = mountField({ accepts })
    await iconLabelled(kitScan, 'unlock.scan')!.trigger('click')
    kitScan.findComponent({ name: 'KitScanner' }).vm.$emit('found', kitPayload(KIT))
    await flushPromises()
    expect(kitScan.emitted('kit')).toEqual([[KIT]])
    expect(kitScan.emitted('scanned')).toBeUndefined()
    expect(kitScan.emitted('update:modelValue')?.at(-1)).toEqual([KIT.secretKey])
  })
})
