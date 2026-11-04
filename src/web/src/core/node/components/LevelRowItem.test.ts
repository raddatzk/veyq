// @vitest-environment happy-dom
// Core's one list item (inbox #407) with a STUB row: what it draws, and that every action is reachable by a REAL click -
// a first-class one on its button, the rest through the real "more" menu (`VeyqMenu`, teleported to the body).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import LevelRowItem from './LevelRowItem.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { realI18n, t } from '../../../test/i18n'
import type { LevelRow, LevelRowAction } from '../levelRow'

const calls: string[] = []
const mounted: VueWrapper[] = []
// Unmount what the test mounted: an open menu's popup lives in the body until its owner goes.
afterEach(() => {
  calls.length = 0
  for (const w of mounted.splice(0)) w.unmount()
})

const act = (id: string, over: Partial<LevelRowAction> = {}): LevelRowAction => ({
  id, label: `${id} thing`, icon: 'times', shown: true, onClick: () => calls.push(id), ...over,
})
function row(over: Partial<LevelRow> = {}): LevelRow {
  return {
    icon: 'file', title: 'report.pdf', meta: 'File · 157 B',
    primary: { label: 'Open report.pdf', onClick: () => calls.push('primary') },
    actions: [act('download', { first: true }), act('rename'), act('delete', { severity: 'danger' })],
    ...over,
  }
}
function mountItem(r: LevelRow, mode: 'grid' | 'list' = 'list', extra: Record<string, unknown> = {}) {
  const w = mount(LevelRowItem, { props: { row: r, mode, ...extra }, global: { plugins: [realI18n()] } })
  mounted.push(w)
  return w
}

/** The overflow menu's entries after a real click on the item's "more" button. */
async function openMore(w: VueWrapper): Promise<HTMLElement[]> {
  await w.find('.veyq-level-item-more').trigger('click')
  await flushPromises()
  return [...document.body.querySelectorAll<HTMLElement>('.veyq-menu__item')]
}

describe('LevelRowItem', () => {
  it('draws the visual, the name and the meta line; the visual is hidden from assistive technology', () => {
    const w = mountItem(row())
    expect(w.find('.veyq-level-item-title').text()).toBe('report.pdf')
    expect(w.find('.veyq-level-item-meta').text()).toBe('File · 157 B')
    expect(w.find('.veyq-level-item-visual').attributes('aria-hidden')).toBe('true')
    expect(w.find('.veyq-level-item-visual').findComponent(VeyqIcon).props('name')).toBe('file')
  })

  it('is a row in list mode and a tile in grid mode, from the same data', () => {
    expect(mountItem(row(), 'list').classes()).toContain('is-list')
    expect(mountItem(row(), 'grid').classes()).toContain('is-grid')
  })

  it('a folder\'s tile is compact (the grid\'s folder group); a folder\'s row and every other tile are not', () => {
    expect(mountItem(row(), 'grid', { folder: true }).classes()).toEqual(expect.arrayContaining(['is-grid', 'is-folder', 'is-compact']))
    expect(mountItem(row(), 'list', { folder: true }).classes()).not.toContain('is-compact')
    expect(mountItem(row(), 'grid').classes()).not.toContain('is-compact')
  })

  it('in both layouts the heart and "more" are quiet icon buttons (no frame), each named - in the tile beside its cover', () => {
    for (const mode of ['list', 'grid'] as const) {
      const w = mountItem(row({ actions: [act('heart', { first: true, pressed: true }), act('rename')] }), mode)
      const buttons = w.findAll('.veyq-level-item-actions button')
      expect(buttons.map((b) => b.attributes('aria-label')), mode).toEqual(['heart thing', t('node.rowMore', { name: 'report.pdf' })])
      for (const b of buttons) {
        expect(b.classes(), mode).toContain('veyq-button--text')
        expect(b.classes(), mode).not.toContain('veyq-button--outlined')
        expect(b.classes(), `${mode}: the layer's icon-button size`).toContain('veyq-icon-button')
        expect(b.text(), mode).toBe('')
      }
      expect(buttons[0].attributes('aria-pressed')).toBe('true')
      expect(buttons[0].classes()).toContain('is-pressed')
      // the actions are the item's own, after its primary button: the Tab order is the name, then its actions
      const kids = [...w.element.children].map((el) => el.className.split(' ')[0])
      expect(kids.indexOf('veyq-level-item-main'), mode).toBeLessThan(kids.indexOf('veyq-level-item-actions'))
    }
  })

  it('draws the application\'s cover component instead of the icon, and marks the item as having one', () => {
    const Cover = defineComponent({ props: { src: String }, setup: (p) => () => h('img', { class: 'cover-probe', src: p.src }) })
    const w = mountItem(row({ leading: { component: Cover, props: { src: 'blob:x' } } }), 'grid')
    expect(w.find('.cover-probe').attributes('src')).toBe('blob:x')
    expect(w.find('.veyq-level-item-icon').exists()).toBe(false)
    expect(w.classes()).toContain('has-cover')
  })

  it('names the primary button by the row\'s label and runs the primary on a real click', async () => {
    const w = mountItem(row())
    const main = w.find('button.veyq-level-item-main')
    expect(main.attributes('aria-label')).toBe('Open report.pdf')
    await main.trigger('click')
    expect(calls).toEqual(['primary'])
  })

  it('a first-class action is a labelled button on the item and a real click runs it - not the primary', async () => {
    const w = mountItem(row())
    const b = w.find('button[data-action="download"]')
    expect(b.attributes('aria-label')).toBe('download thing')
    await b.trigger('click')
    expect(calls).toEqual(['download'])
  })

  it('puts every other action into ONE menu, each reachable by a real click on its entry, none on the item itself', async () => {
    const w = mountItem(row())
    expect(w.findAll('.veyq-level-item-actions button').map((b) => b.attributes('data-action') ?? 'more')).toEqual(['download', 'more'])
    expect(w.find('.veyq-level-item-more').attributes('aria-label')).toBe(t('node.rowMore', { name: 'report.pdf' }))
    expect(w.find('.veyq-level-item-more').attributes('aria-haspopup')).toBe('menu')
    const items = await openMore(w)
    expect(items.map((i) => i.textContent?.trim())).toEqual(['rename thing', 'delete thing'])
    expect(items.every((i) => i.getAttribute('role') === 'menuitem')).toBe(true)
    expect(items[1].classList.contains('is-danger')).toBe(true)
    items[0].querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
    await flushPromises()
    expect(calls).toEqual(['rename'])
    const again = await openMore(w)
    again.find((i) => i.classList.contains('veyq-row-action-delete'))!.querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
    await flushPromises()
    expect(calls).toEqual(['rename', 'delete'])
  })

  it('draws no menu button when there is nothing for it, and no actions row at all when there is no action', () => {
    const only = mountItem(row({ actions: [act('download', { first: true })] }))
    expect(only.find('.veyq-level-item-more').exists()).toBe(false)
    const none = mountItem(row({ actions: [] }))
    expect(none.find('.veyq-level-item-actions').exists()).toBe(false)
  })

  it('fails closed: an action that is not shown is neither a button nor a menu entry', async () => {
    const w = mountItem(row({ actions: [act('download', { first: true, shown: false }), act('rename', { shown: false }), act('move')] }))
    expect(w.find('[data-action="download"]').exists()).toBe(false)
    const items = await openMore(w)
    expect(items.map((i) => i.textContent?.trim())).toEqual(['move thing'])
  })

  it('renders a disabled action but does not run it (button and menu entry alike)', async () => {
    const w = mountItem(row({ actions: [act('download', { first: true, disabled: true }), act('rename', { disabled: true })] }))
    const b = w.find('button[data-action="download"]')
    expect(b.attributes('disabled')).toBeDefined()
    await b.trigger('click')
    const items = await openMore(w)
    expect(items[0].getAttribute('aria-disabled')).toBe('true')
    items[0].querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
    await flushPromises()
    expect(calls).toEqual([])
  })

  it('a disabled primary is a disabled button', () => {
    expect(mountItem(row({ primary: { label: 'x', onClick: vi.fn(), disabled: true } })).find('button.veyq-level-item-main').attributes('disabled')).toBeDefined()
  })

  it('a toggle action carries its state (aria-pressed)', () => {
    const w = mountItem(row({ actions: [act('favorite', { first: true, pressed: true })] }))
    expect(w.find('[data-action="favorite"]').attributes('aria-pressed')).toBe('true')
  })

  it('merges core\'s own actions around the application\'s, without the application knowing', () => {
    const w = mountItem(row({ actions: [act('download', { first: true })] }), 'list', {
      coreActions: [{ ...act('heart', { first: true }), placement: 'before' }],
    })
    expect(w.findAll('.veyq-level-item-actions [data-action]').map((b) => b.attributes('data-action'))).toEqual(['heart', 'download'])
  })

  it('an unsupported item is not a button: it says so and cannot be clicked', () => {
    const w = mountItem(row({ icon: 'question-circle', meta: 'unsupported', actions: [] }), 'list', { unsupported: true })
    expect(w.find('button').exists()).toBe(false)
    expect(w.classes()).toContain('is-unsupported')
    expect(w.text()).toContain('unsupported')
  })

  describe('details (the application-supplied meta line)', () => {
    const meta = (w: VueWrapper) => w.find('.veyq-level-item-meta')

    it('draws the kind, then each detail as real text in reading order, each tagged for tests and styling', () => {
      const w = mountItem(row({ meta: 'Memory', details: [{ id: 'date', text: '04/02/2024' }, { id: 'place', text: 'Rom' }] }), 'grid')
      expect(meta(w).text()).toBe('Memory · 04/02/2024 · Rom')
      expect(meta(w).findAll('[data-detail]').map((d) => [d.attributes('data-detail'), d.text()])).toEqual([['date', '04/02/2024'], ['place', 'Rom']])
      // text, not a pseudo-element or an aria-hidden decoration: a reader gets it in this order
      expect(meta(w).attributes('aria-hidden')).toBeUndefined()
      expect(w.find('button.veyq-level-item-main').attributes('aria-label')).toBe('Open report.pdf')
    })

    it('leaves out an absent detail: no placeholder, no dangling separator', () => {
      const w = mountItem(row({ meta: 'Memory', details: [{ id: 'date', text: '' }, { id: 'place', text: 'Rom' }] }))
      expect(meta(w).text()).toBe('Memory · Rom')
      expect(mountItem(row({ meta: 'Memory', details: [{ id: 'date', text: '' }] })).find('.veyq-level-item-meta').text()).toBe('Memory')
      expect(mountItem(row({ meta: 'Memory', details: undefined })).find('.veyq-level-item-meta').text()).toBe('Memory')
      expect(mountItem(row({ meta: 'Memory', details: [] })).find('[data-detail]').exists()).toBe(false)
    })

    it('starts with the first detail, without a leading separator, when the row has no kind line', () => {
      expect(meta(mountItem(row({ meta: '', details: [{ id: 'size', text: '3 kB' }, { id: 'modified', text: 'Modified x' }] }))).text()).toBe('3 kB · Modified x')
    })

    it('draws no meta line at all when there is neither a kind nor a detail', () => {
      expect(mountItem(row({ meta: '', details: [] })).find('.veyq-level-item-meta').exists()).toBe(false)
    })

    it('draws the same details as a row and as a tile', () => {
      const r = row({ meta: 'File', details: [{ id: 'size', text: '3 kB' }] })
      expect(meta(mountItem(r, 'list')).text()).toBe(meta(mountItem(r, 'grid')).text())
    })
  })

  // The level's Select mode (inbox #421): a real checkbox named by the node, beside - never instead of - the primary click.
  describe('in the Select mode', () => {
    it('no checkbox outside the mode', () => {
      expect(mountItem(row()).find('input[type="checkbox"]').exists()).toBe(false)
    })

    it('a real checkbox named by the node\'s title, checked as told; a change asks the level to toggle it', async () => {
      const w = mountItem(row(), 'grid', { selected: false })
      const box = w.find('input[type="checkbox"]')
      expect(box.attributes('aria-label')).toBe('report.pdf')
      expect((box.element as HTMLInputElement).checked).toBe(false)
      await box.setValue(true)
      expect(w.emitted('toggleSelect')).toHaveLength(1)
      expect(calls, 'the primary did not run').toEqual([])
      const on = mountItem(row(), 'list', { selected: true })
      expect((on.find('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true)
      expect(on.classes()).toContain('is-selected')
    })

    it('the primary click still opens the node and toggles nothing', async () => {
      const w = mountItem(row(), 'list', { selected: false })
      await w.find('button.veyq-level-item-main').trigger('click')
      expect(calls).toEqual(['primary'])
      expect(w.emitted('toggleSelect')).toBeUndefined()
    })
  })
})
