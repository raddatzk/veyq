// @vitest-environment happy-dom
// cr-0063, inbox #370 — the toggle offers the presentations it is handed, reflects the current view mode and switches it
// on click.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ViewModeToggle from './ViewModeToggle.vue'
import { GRID_VIEW, LIST_VIEW, type LevelViewOption } from './levelViews'
import { setViewMode } from '../../shared/state/useViewMode'
import { realI18n, t } from '../../test/i18n'

const MAP: LevelViewOption = { mode: 'map', icon: 'pi pi-map', label: 'memory.viewMap' }
const mountToggle = (views: readonly LevelViewOption[] = [GRID_VIEW, LIST_VIEW]) =>
  mount(ViewModeToggle, { props: { views }, global: { plugins: [realI18n()], directives: { tooltip: {} } } })

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

  it('offers exactly the views it is handed, each named by its own label key', () => {
    expect(mountToggle().findAll('.view-mode-button').map((b) => b.attributes('aria-label'))).toEqual([t('node.viewGrid'), t('node.viewList')])
    expect(mountToggle([GRID_VIEW, LIST_VIEW, MAP]).findAll('.view-mode-button')).toHaveLength(3)
  })

  it('a view an application brings (the map) switches to its mode', async () => {
    const w = mountToggle([GRID_VIEW, LIST_VIEW, MAP])
    const mapBtn = w.findAll('.view-mode-button')[2]

    expect(mapBtn.attributes('aria-label')).toBe(t('memory.viewMap'))
    expect(mapBtn.attributes('aria-pressed')).toBe('false')
    await mapBtn.trigger('click')
    expect(mapBtn.attributes('aria-pressed')).toBe('true')
    expect(localStorage.getItem('veyq:view-mode')).toBe('map')
  })
})
