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
import { kitPayload, type KitData } from '@veyq/crypto/emergencyKit'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { importKitFromFile } from '../../features/account/emergencyKit/emergencyKit'
import { realI18n, t } from '../../test/i18n'

// A real Secret Key in its printed form, as a real kit carries — the parser rejects anything that does not read as one.
const KIT: KitData = { email: 'kevin@example.com', secretKey: formatSecretKey(new Uint8Array(32).fill(0x11)) }
/** KIT's key with its last check symbol changed: 54 symbols, and a typo. */
const TYPO = KIT.secretKey.slice(0, -1) + (KIT.secretKey.endsWith('A') ? 'B' : 'A')

const stubs = {
  // `name`, `type`, `id` and the rest fall through as attributes — which is how every host's test finds this
  // field by `input[name="secret-key"]`.
  VeyqInput: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-tone="tone"><slot /></div>' },
  KitScanner: true,
}

const mounted: VueWrapper[] = []

function mountField(props: Record<string, unknown> = {}): VueWrapper {
  const w = mount(SecretKeyField, {
    props: { modelValue: '', ...props },
    global: { plugins: [realI18n()], stubs },
  })
  mounted.push(w)
  return w
}

const field = (w: VueWrapper) => w.find('input[name="secret-key"]')
const fileField = (w: VueWrapper) => w.find('input[type="file"]')
const iconLabelled = (w: VueWrapper, label: string) =>
  w.findAll('button').find((b) => b.attributes('aria-label') === t(label))
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
    expect(shown[0].text()).toBe(t('unlock.kitUnreadable'))
    expect(shown[0].attributes('data-tone')).toBe('error')
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

  it('says a full-length key with a typo has one — and says nothing while a key is still being typed', async () => {
    const w = mountField()
    await field(w).setValue(TYPO)
    await w.setProps({ modelValue: TYPO })
    const shown = messages(w)
    expect(shown).toHaveLength(1)
    expect(shown[0].text()).toBe(t('unlock.secretKeyTypo'))
    expect(shown[0].attributes('data-tone')).toBe('error')
    // The key stays as typed: the message points at it, it does not rewrite it.
    expect(w.emitted('update:modelValue')).toEqual([[TYPO]])

    // Controls on the same predicate: the right key in any spelling, and a key not yet complete, show nothing.
    for (const quiet of [KIT.secretKey, KIT.secretKey.toLowerCase().replace(/-/g, ' '), KIT.secretKey.slice(0, 20), '']) {
      await w.setProps({ modelValue: quiet })
      expect(messages(w), quiet).toHaveLength(0)
    }
  })

  it('warns when the kit is for another account — and does not block, clear or refuse it', async () => {
    const w = mountField({ expectedEmail: 'someone.else@example.com' })

    vi.mocked(importKitFromFile).mockResolvedValue(KIT)
    await pick(w, someFile())

    const shown = messages(w)
    expect(shown).toHaveLength(1)
    expect(shown[0].attributes('data-tone')).toBe('warn')
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

  it('says where a kit made at another address was made — and does not block, clear or refuse it', async () => {
    const elsewhere = { ...KIT, server: 'https://familie.example.org:8443' }
    const w = mountField()
    vi.mocked(importKitFromFile).mockResolvedValue(elsewhere)
    await pick(w, someFile())

    const shown = messages(w)
    expect(shown).toHaveLength(1)
    expect(shown[0].attributes('data-tone')).toBe('info')
    expect(shown[0].text()).toContain('familie.example.org:8443')
    expect(shown[0].text()).toContain(location.host)
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([KIT.secretKey])
    expect(w.emitted('kit')).toEqual([[elsewhere]])

    // Two controls: a kit made at this very page's origin, and a kit with no server, say nothing.
    for (const kit of [{ ...KIT, server: location.origin }, KIT]) {
      const quiet = mountField()
      vi.mocked(importKitFromFile).mockResolvedValue(kit)
      await pick(quiet, someFile())
      expect(messages(quiet), JSON.stringify(kit)).toHaveLength(0)
    }
  })

  // Owner 2026-11-12: this field's camera reads an Emergency Kit and nothing else. A pairing code used to come
  // through here too (the sign-in screen widened the predicate); it has its own button on that screen now.
  it('scans for a kit and nothing else, and says so over any other code', async () => {
    const w = mountField()

    await iconLabelled(w, 'unlock.scan')!.trigger('click')
    const scanner = w.findComponent({ name: 'KitScanner' })
    // Asserted through what the scanner actually receives: a wider predicate would stop the camera on a code
    // this field cannot use, a missing refusal text would leave the person wondering why a code in view is ignored.
    const accepts = scanner.props('accepts') as (text: string) => boolean
    expect(accepts(kitPayload(KIT))).toBe(true) // control: the kit is taken
    expect(accepts('veyqpair:aGFuZGxl')).toBe(false)
    expect(accepts('veyqsignin:aGFuZGxl')).toBe(false)
    expect(accepts('https://example.com/')).toBe(false)
    expect(scanner.props('refusedText')).toBe(t('unlock.notAKit'))

    scanner.vm.$emit('found', kitPayload(KIT))
    await flushPromises()
    expect(w.emitted('kit')).toEqual([[KIT]])
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([KIT.secretKey])
    expect(Object.keys(w.emitted())).not.toContain('scanned') // there is no pass-through any more
  })
})
