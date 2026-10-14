// @vitest-environment happy-dom
// PrimeVue's own texts are in the console's language (inbox #434): the real PrimeVue and its real Dialog and
// Password, this package's i18n singleton, and a language change while they are mounted.
import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { defineComponent, h, type App, type Component } from 'vue'
import PrimeVue from 'primevue/config'
import Dialog from 'primevue/dialog'
import Password from 'primevue/password'
import { syncPrimeVueLocale } from './primeVueLocale'
import { i18n } from '../i18n'

const DialogHost = defineComponent(() => () => h(Dialog, { visible: true, header: 'x', modal: false }, () => 'body'))
const PasswordHost = defineComponent(() => () => h(Password, { modelValue: '', feedback: false, toggleMask: true }))
const closeLabel = () => document.body.querySelector('.p-dialog-close-button')?.getAttribute('aria-label')
// `<Password>`'s accessible prompt: the live region PrimeVue renders beside the input, read by a screen reader.
const passwordPrompt = () => document.body.querySelector('.p-password [aria-live="polite"]')?.textContent

async function mountWith(host: Component, sync: boolean, locale: 'en' | 'de') {
  const plugins = [i18n, PrimeVue, ...(sync ? [{ install: (app: App) => syncPrimeVueLocale(app) }] : [])]
  i18n.global.locale.value = locale
  const w = mount(host, { attachTo: document.body, global: { plugins } })
  await flushPromises()
  return w
}

// A test that fails before its own unmount must not leave its dialog in the body for the next one to read.
enableAutoUnmount(afterEach)
afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('syncPrimeVueLocale (admin-web)', () => {
  it('names a Dialog\'s close button in German, and follows a change to English and back', async () => {
    const w = await mountWith(DialogHost, true, 'de')
    expect(closeLabel()).toBe('Schließen')
    i18n.global.locale.value = 'en'
    await flushPromises()
    expect(closeLabel()).toBe('Close')
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(closeLabel()).toBe('Schließen')
    w.unmount()
  })

  it('a <Password> in a German console has the German accessible prompt', async () => {
    const w = await mountWith(PasswordHost, true, 'de')
    expect(passwordPrompt()).toBe('Passwort eingeben')
    w.unmount()
  })

  it('without it, both stay English in a German console (the control)', async () => {
    const d = await mountWith(DialogHost, false, 'de')
    expect(closeLabel()).toBe('Close')
    d.unmount()
    const p = await mountWith(PasswordHost, false, 'de')
    expect(passwordPrompt()).toBe('Enter a password')
    p.unmount()
  })
})
