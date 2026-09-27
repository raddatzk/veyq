// @vitest-environment happy-dom
// cr-0063 — the toggle reflects the current view mode and switches it on click.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ViewModeToggle from './ViewModeToggle.vue'
import { setViewMode } from '../../../shared/state/useViewMode'
import { realI18n } from '../../../test/i18n'

const mountToggle = (props: Record<string, unknown> = {}) =>
  mount(ViewModeToggle, { props, global: { plugins: [realI18n()], directives: { tooltip: {} } } })

beforeEach(() => {
  localStorage.clear()
  setViewMode('grid')
})

describe('ViewModeToggle', () => {
  it('marks the active mode and switches on click', async () => {
    const w = mountToggle()
    const [gridBtn, listBtn] = w.findAll('.view-mode-button')

    expect(gridBtn.attributes('aria-pressed')).toBe('true')
    expect(listBtn.attributes('aria-pressed')).toBe('false')

    await listBtn.trigger('click')
    expect(listBtn.attributes('aria-pressed')).toBe('true')
    expect(gridBtn.attributes('aria-pressed')).toBe('false')
    expect(localStorage.getItem('veyq:view-mode')).toBe('list')
  })

  it('omits the map button by default (Chapters grid), shows it with show-map (browse)', () => {
    expect(mountToggle().findAll('.view-mode-button')).toHaveLength(2)
    expect(mountToggle({ showMap: true }).findAll('.view-mode-button')).toHaveLength(3)
  })

  it('the map button switches to map mode (browse only)', async () => {
    const w = mountToggle({ showMap: true })
    const mapBtn = w.findAll('.view-mode-button')[2]

    expect(mapBtn.attributes('aria-pressed')).toBe('false')
    await mapBtn.trigger('click')
    expect(mapBtn.attributes('aria-pressed')).toBe('true')
    expect(localStorage.getItem('veyq:view-mode')).toBe('map')
  })
})
