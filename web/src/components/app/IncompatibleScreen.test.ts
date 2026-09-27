// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import IncompatibleScreen from './IncompatibleScreen.vue'
import { capabilityState, EXPECTED_MAJOR } from '../../shared/state/capabilities'
import { realI18n, t } from '../../test/i18n'

beforeEach(() => {
  capabilityState.ids = null
  capabilityState.major = null
  capabilityState.build = null
})

const mountScreen = () => mount(IncompatibleScreen, { global: { plugins: [realI18n()] } })

describe('IncompatibleScreen', () => {
  it('shows the update-app copy when the server major is newer', () => {
    capabilityState.major = EXPECTED_MAJOR + 1
    const w = mountScreen()
    expect(w.text()).toContain(t('incompatible.updateApp'))
    expect(w.text()).not.toContain(t('incompatible.updateServer'))
  })

  it('shows the update-server copy when the server major is older', () => {
    capabilityState.major = EXPECTED_MAJOR - 1
    const w = mountScreen()
    expect(w.text()).toContain(t('incompatible.updateServer'))
    expect(w.text()).not.toContain(t('incompatible.updateApp'))
  })
})
