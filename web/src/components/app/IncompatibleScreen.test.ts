// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import IncompatibleScreen from './IncompatibleScreen.vue'
import { capabilityState, EXPECTED_MAJOR } from '../../shared/state/capabilities'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      incompatible: {
        title: 'This app and this server don’t match',
        updateApp: 'UPDATE YOUR APP',
        updateServer: 'UPDATE THE SERVER',
        hint: 'Only a version check',
      },
    },
  },
})

beforeEach(() => {
  capabilityState.ids = null
  capabilityState.major = null
  capabilityState.build = null
})

const mountScreen = () => mount(IncompatibleScreen, { global: { plugins: [i18n] } })

describe('IncompatibleScreen', () => {
  it('shows the update-app copy when the server major is newer', () => {
    capabilityState.major = EXPECTED_MAJOR + 1
    const w = mountScreen()
    expect(w.text()).toContain('UPDATE YOUR APP')
    expect(w.text()).not.toContain('UPDATE THE SERVER')
  })

  it('shows the update-server copy when the server major is older', () => {
    capabilityState.major = EXPECTED_MAJOR - 1
    const w = mountScreen()
    expect(w.text()).toContain('UPDATE THE SERVER')
    expect(w.text()).not.toContain('UPDATE YOUR APP')
  })
})
