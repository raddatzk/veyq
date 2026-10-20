// The app's real confirmation dialog (`shared/ui/ConfirmDialog.vue`, mounted once in App.vue), for a suite that must
// prove a question is ASKED and ANSWERED through the DOM — not only that `ask()` was called: the real PrimeVue Dialog
// teleported to `document.body`, its buttons clicked with a real `click` event. Unmounted by `setup.ts`'s
// `enableAutoUnmount`; a request still open after a test is the suite's to settle (`settleConfirm(false)`).
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import ConfirmDialog from '../shared/ui/ConfirmDialog.vue'
import { realI18n } from './i18n'

export function mountConfirmDialog() {
  mount(ConfirmDialog, { attachTo: document.body, global: { plugins: [realI18n(), PrimeVue] } })
  const dialog = () => document.body.querySelector<HTMLElement>('.veyq-confirm-dialog')
  return {
    /** Whether the dialog is on screen. */
    isOpen: () => dialog() !== null,
    /** The dialog's whole text: header, question and buttons. */
    text: () => dialog()?.textContent ?? '',
    /** Click the dialog's button labelled `label` with a real DOM click. Throws when the dialog or the button is missing. */
    click(label: string) {
      const d = dialog()
      if (!d) throw new Error('no confirmation dialog is open')
      const button = [...d.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)
      if (!button) throw new Error(`no button "${label}" in the confirmation dialog`)
      button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
    },
  }
}
