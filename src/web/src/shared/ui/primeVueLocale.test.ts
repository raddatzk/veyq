// @vitest-environment happy-dom
// PrimeVue's own texts are in the app's language (inbox #385, #434): the real PrimeVue and its real components, the
// app's i18n singleton, and a language switch while they are mounted.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, type App, type Component } from 'vue'
import PrimeVue, { type PrimeVueConfiguration } from 'primevue/config'
import Dialog from 'primevue/dialog'
import Password from 'primevue/password'
import { syncPrimeVueLocale } from './primeVueLocale'
import { realI18n } from '../../test/i18n'
import { i18n } from '../../i18n'

const DialogHost = defineComponent(() => () => h(Dialog, { visible: true, header: 'x', modal: false }, () => 'body'))
const PasswordHost = defineComponent(() => () => h(Password, { modelValue: '', feedback: false, toggleMask: true }))
const closeLabel = () => document.body.querySelector('.p-dialog-close-button')?.getAttribute('aria-label')
// `<Password>`'s accessible prompt: the live region PrimeVue renders beside the input, read by a screen reader.
const passwordPrompt = () => document.body.querySelector('.p-password [aria-live="polite"]')?.textContent

async function mountWith(host: Component, sync: boolean, locale: 'en' | 'de' = 'en') {
  const plugins = [realI18n(), PrimeVue, ...(sync ? [{ install: (app: App) => syncPrimeVueLocale(app) }] : [])]
  i18n.global.locale.value = locale
  const w = mount(host, { attachTo: document.body, global: { plugins } })
  await flushPromises()
  return w
}

describe('syncPrimeVueLocale', () => {
  it('names a Dialog\'s close button with the app\'s word for "close", and follows a language change', async () => {
    const w = await mountWith(DialogHost, true)
    expect(closeLabel()).toBe('Close')
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(closeLabel()).toBe('Schließen')
    w.unmount()
  })

  it('starts in the language the app starts in', async () => {
    const w = await mountWith(DialogHost, true, 'de')
    expect(closeLabel()).toBe('Schließen')
    w.unmount()
  })

  it('without it, the close button says "Close" in a German UI (the control)', async () => {
    const w = await mountWith(DialogHost, false)
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(closeLabel()).toBe('Close')
    w.unmount()
  })

  it('PrimeVue\'s config follows the language: DE, then EN, then DE again', async () => {
    const w = await mountWith(DialogHost, true, 'de')
    const config = () => (w.vm.$primevue as { config: PrimeVueConfiguration }).config.locale!
    const read = () => {
      const l = config()
      return {
        passwordPrompt: l.passwordPrompt,
        selectionMessage: l.selectionMessage,
        firstDayOfWeek: l.firstDayOfWeek,
        march: l.monthNames?.[2],
        marchShort: l.monthNamesShort?.[2],
        tuesday: l.dayNamesMin?.[2],
        next: l.aria?.next,
        prevDecade: l.prevDecade,
      }
    }
    const de = {
      passwordPrompt: 'Passwort eingeben',
      selectionMessage: '{0} ausgewählt', // PrimeVue's own placeholder survives the catalog's `{count}`
      firstDayOfWeek: 1,
      march: 'März',
      marchShort: 'Mär',
      tuesday: 'D',
      next: 'Weiter',
      prevDecade: 'Vorheriges Jahrzehnt',
    }
    expect(read()).toEqual(de)
    i18n.global.locale.value = 'en'
    await flushPromises()
    expect(read()).toEqual({
      passwordPrompt: 'Enter a password',
      selectionMessage: '{0} items selected',
      firstDayOfWeek: 0,
      march: 'March',
      marchShort: 'Mar',
      tuesday: 'T',
      next: 'Next',
      prevDecade: 'Previous decade',
    })
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(read()).toEqual(de)
    // What the app does not surface stays PrimeVue's own default, not dropped.
    expect(config().weak).toBe('Weak')
    w.unmount()
  })

  it('a <Password> in a German UI has the German accessible prompt', async () => {
    const w = await mountWith(PasswordHost, true, 'de')
    expect(passwordPrompt()).toBe('Passwort eingeben')
    w.unmount()
  })

  it('without it, a <Password> in a German UI says "Enter a password" (the control)', async () => {
    const w = await mountWith(PasswordHost, false, 'de')
    expect(passwordPrompt()).toBe('Enter a password')
    w.unmount()
  })
})
