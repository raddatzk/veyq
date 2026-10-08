// @vitest-environment happy-dom
// PrimeVue's dialog close button is named in the app's language (inbox #385): the real PrimeVue and its real Dialog,
// the app's i18n singleton, and a language switch while the dialog is open.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, type App } from 'vue'
import PrimeVue from 'primevue/config'
import Dialog from 'primevue/dialog'
import { syncPrimeVueLocale } from './primeVueLocale'
import { realI18n } from '../test/i18n'
import { i18n } from '../i18n'

const Host = defineComponent(() => () => h(Dialog, { visible: true, header: 'x', modal: false }, () => 'body'))
const closeLabel = () => document.body.querySelector('.p-dialog-close-button')?.getAttribute('aria-label')

async function mountDialog(sync: boolean, locale: 'en' | 'de' = 'en') {
  const plugins = [realI18n(), PrimeVue, ...(sync ? [{ install: (app: App) => syncPrimeVueLocale(app) }] : [])]
  i18n.global.locale.value = locale
  const w = mount(Host, { attachTo: document.body, global: { plugins } })
  await flushPromises()
  return w
}

describe('syncPrimeVueLocale', () => {
  it('names a Dialog\'s close button with the app\'s word for "close", and follows a language change', async () => {
    const w = await mountDialog(true)
    expect(closeLabel()).toBe('Close')
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(closeLabel()).toBe('Schließen')
    w.unmount()
  })

  it('starts in the language the app starts in', async () => {
    const w = await mountDialog(true, 'de')
    expect(closeLabel()).toBe('Schließen')
    w.unmount()
  })

  it('without it, the close button says "Close" in a German UI (the control)', async () => {
    const w = await mountDialog(false)
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect(closeLabel()).toBe('Close')
    w.unmount()
  })
})
