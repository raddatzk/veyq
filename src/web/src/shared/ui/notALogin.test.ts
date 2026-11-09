// @vitest-environment happy-dom
// A password manager leaves a field that is no account credential alone only where the manager's own opt-out reaches the
// `<input>` (`./notALogin.ts`); `passwordFields.test.ts` holds every password field of this surface to deciding which it is.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import VeyqPassword from './VeyqPassword.vue'
import { NOT_A_LOGIN } from './notALogin'
import { realI18n } from '../../test/i18n'

describe('VeyqPassword: the password managers\' opt-out', () => {
  it('lands on the input where the field is no login, never on the wrapper', () => {
    const w = mount(VeyqPassword, { props: { notALogin: true, autocomplete: 'off' }, global: { plugins: [realI18n()] } })
    const input = w.get('input')
    for (const [name, value] of Object.entries(NOT_A_LOGIN)) {
      expect(input.attributes(name), name).toBe(value)
      expect(w.get('.veyq-password').attributes(name), `${name} not on the wrapper`).toBeUndefined()
    }
    expect(Object.keys(NOT_A_LOGIN)).toEqual(['data-1p-ignore', 'data-lpignore', 'data-bwignore', 'data-form-type'])
  })

  it('is absent from an account credential\'s field', () => {
    const w = mount(VeyqPassword, { props: { autocomplete: 'current-password' }, global: { plugins: [realI18n()] } })
    expect(w.get('input').attributes('autocomplete')).toBe('current-password')
    for (const name of Object.keys(NOT_A_LOGIN)) expect(w.get('input').attributes(name), name).toBeUndefined()
  })
})
