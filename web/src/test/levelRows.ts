// Test helpers for the unified level list (inbox #407): mount ONE application's row factory beside core's real item and
// notice components (the application's rows without the rest of the level), and drive a row's actions the way a person
// does - a real click on its button, or on its entry in the real "more" menu.
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, defineComponent, h, reactive } from 'vue'
import LevelRowItem from '../core/node/components/LevelRowItem.vue'
import LevelNotices from '../core/node/components/LevelNotices.vue'
import { orderLevel } from '../core/node/levelList'
import type { LevelNotice, LevelRowsContext, LevelRowsFactory, LevelRowSource } from '../core/node/levelRow'
import type { LevelActionHandler } from '../core/node/levelActions'
import type { TreeNode } from '../core/node/tree'
import { realI18n } from './i18n'

export interface RowsHarness {
  w: VueWrapper
  /** What the factory asked of the host. */
  events: { open: TreeNode[]; rename: TreeNode[]; move: TreeNode[] }
  /** The level actions the factory registered. */
  actions: Map<string, LevelActionHandler>
  /** The source the factory returned. */
  source: () => LevelRowSource
  /** Change the level's context (reactive, as the level view's). */
  setCtx(over: Partial<LevelRowsContext>): Promise<void>
}

/** Mount `factory` with the level `ctx`: the nodes drawn in core's order as core's real items, the notices as core's real
 *  notice component. `mode` is the layout. */
export function mountRows(factory: LevelRowsFactory, ctx: LevelRowsContext, mode: 'grid' | 'list' = 'list', plugins: unknown[] = []): RowsHarness {
  const events = { open: [] as TreeNode[], rename: [] as TreeNode[], move: [] as TreeNode[] }
  const actions = new Map<string, LevelActionHandler>()
  const state = reactive({ ...ctx }) as LevelRowsContext
  let source!: LevelRowSource
  const Host = defineComponent({
    setup() {
      source = factory(computed(() => ({ ...state })), {
        open: (n) => events.open.push(n),
        rename: (n) => events.rename.push(n),
        move: (n) => events.move.push(n),
        registerAction: (id, handler) => actions.set(id, handler),
      })
      return () => {
        const nodes = orderLevel([], state.nodes, 'en')
        return h('div', { class: 'rows-harness' }, [
          h(LevelNotices, { notices: (source.notices?.value ?? []) as readonly LevelNotice[] }),
          h('ul', { class: 'veyq-level-list' }, nodes.map((node) => {
            const row = source.row(node)
            return row ? h(LevelRowItem, { key: node.id, row, mode, kind: node.kind }) : null
          })),
        ])
      }
    },
  })
  const w = mount(Host, { global: { plugins: [realI18n(), ...plugins] as never[], directives: { tooltip: {} } } })
  return { w, events, actions, source: () => source, setCtx: async (over) => { Object.assign(state, over); await flushPromises() } }
}

const rowOf = (w: VueWrapper, title: string) => {
  const row = w.findAll('li.veyq-level-item').find((li) => li.find('.veyq-level-item-title').text() === title)
  if (!row) throw new Error(`no row "${title}" among: ${w.findAll('.veyq-level-item-title').map((t) => t.text()).join(', ')}`)
  return row
}

/** The titles of the drawn rows, in order. */
export const titlesOf = (w: VueWrapper): string[] => w.findAll('.veyq-level-item-title').map((t) => t.text())

const menuItems = (): HTMLElement[] => [...document.body.querySelectorAll<HTMLElement>('li.p-menu-item')]

/** The ids of the row's actions as a person finds them: the first-class buttons, then the entries of its "more" menu
 *  (opened with a real click and closed again). */
export async function actionIdsOf(w: VueWrapper, title: string): Promise<string[]> {
  const row = rowOf(w, title)
  const buttons = row.findAll('.veyq-level-item-actions [data-action]').map((b) => b.attributes('data-action')!)
  const more = row.find('.veyq-level-item-more')
  if (!more.exists()) return buttons
  await more.trigger('click')
  await flushPromises()
  const inMenu = menuItems().map((i) => [...i.classList].find((c) => c.startsWith('veyq-row-action-'))!.replace('veyq-row-action-', ''))
  await more.trigger('click') // closes the popup again
  await flushPromises()
  return [...buttons, ...inMenu]
}

/** Run a row's action by a REAL click: its button when it is first-class, else its entry in the opened "more" menu. */
export async function clickAction(w: VueWrapper, title: string, id: string): Promise<void> {
  const row = rowOf(w, title)
  const button = row.find(`.veyq-level-item-actions [data-action="${id}"]`)
  if (button.exists()) {
    await button.trigger('click')
    await flushPromises()
    return
  }
  const more = row.find('.veyq-level-item-more')
  if (!more.exists()) throw new Error(`row "${title}" has no action "${id}" (and no menu)`)
  await more.trigger('click')
  await flushPromises()
  const item = menuItems().find((i) => i.classList.contains(`veyq-row-action-${id}`))
  if (!item) throw new Error(`row "${title}" has no action "${id}" in its menu`)
  item.querySelector<HTMLElement>('.p-menu-item-content')!.click()
  await flushPromises()
}

/** The accessible label of an action, wherever it sits. */
export async function actionLabel(w: VueWrapper, title: string, id: string): Promise<string | null> {
  const row = rowOf(w, title)
  const button = row.find(`.veyq-level-item-actions [data-action="${id}"]`)
  if (button.exists()) return button.attributes('aria-label') ?? null
  const more = row.find('.veyq-level-item-more')
  if (!more.exists()) return null
  await more.trigger('click')
  await flushPromises()
  const item = menuItems().find((i) => i.classList.contains(`veyq-row-action-${id}`))
  const label = item?.textContent?.trim() ?? null
  await more.trigger('click')
  await flushPromises()
  return label
}

/** Whether the row's action is disabled, wherever it sits. */
export async function actionDisabled(w: VueWrapper, title: string, id: string): Promise<boolean> {
  const row = rowOf(w, title)
  const button = row.find(`.veyq-level-item-actions [data-action="${id}"]`)
  if (button.exists()) return button.attributes('disabled') !== undefined
  const more = row.find('.veyq-level-item-more')
  await more.trigger('click')
  await flushPromises()
  const item = menuItems().find((i) => i.classList.contains(`veyq-row-action-${id}`))
  const disabled = item?.getAttribute('data-p-disabled') === 'true'
  await more.trigger('click')
  await flushPromises()
  return disabled
}

export { rowOf }
