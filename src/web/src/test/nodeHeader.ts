// Test helpers for core's node header (`core/node/components/NodeHeader.vue`, ADR 0110): its buttons, and its ⋯ menu — a
// real click on the ⋯ opens the popup `VeyqMenu` (portalled to the body), a real click on an entry runs it. Unmeasured, as
// in every unit test, each action that may be a button is one, so the menu holds the menu-only ones: report and delete.
// And `mountWithHeader`: a kind's view mounted under core's header as core's page draws it, for a test of what the view
// contributes to that header without the page's doors and core actions (`src/test/nodePage.ts` mounts the real page).
import { defineComponent, h, type Component } from 'vue'
import { flushPromises, mount, type MountingOptions, type VueWrapper } from '@vue/test-utils'
import NodeHeader from '../core/node/components/NodeHeader.vue'
import { provideNodeHeader } from '../core/node/nodeHeaderContribution'
import { registry } from '../core/applications/registry'
import type { TreeNode } from '../core/node/tree'

/**
 * Mount `view` (its `node` prop names the node) under core's header, drawn as `NodePage` draws it: the kind's icon, cover and
 * order from the registry, and what `view` contributes (`contributeNodeHeader`) - its banner above. People link as on a
 * member's page unless `linkPeople` is false. The answer is the stand-in page's wrapper (`find` sees the header and the
 * view), with `emitted` reading the VIEW's events and `view` its own wrapper.
 */
export function mountWithHeader(
  view: Component,
  options: {
    props?: Record<string, unknown>
    global?: MountingOptions<Record<string, unknown>>['global']
    attachTo?: MountingOptions<Record<string, unknown>>['attachTo']
    linkPeople?: boolean
  } = {},
) {
  const { linkPeople = true, props, global, attachTo } = options
  const Page = defineComponent({
    name: 'NodePageStandIn',
    inheritAttrs: false,
    setup(_, { attrs }) {
      const header = provideNodeHeader()
      return () => {
        const node = attrs.node as TreeNode
        const d = registry.kind(node.kind)
        const kind = d.supported ? d : null
        const banner = header.value.banner
        return h('div', { class: 'veyq-node-page' }, [
          banner ? h(banner.component, banner.props ?? {}) : null,
          h(NodeHeader, {
            node, title: node.name, icon: kind?.icon, cover: !!kind?.cover, order: kind?.header?.order, linkPeople,
            subtitle: header.value.subtitle, actions: header.value.actions, facts: header.value.facts, rename: header.value.rename ?? null,
          }),
          h(view, attrs),
        ])
      }
    },
  })
  const w = mount(Page, { attrs: props, global, attachTo })
  const inner = w.findComponent(view) as VueWrapper
  return Object.assign(w, { view: inner, emitted: inner.emitted.bind(inner) as VueWrapper['emitted'] })
}

const MORE = '.veyq-node-header-more'
const ENTRY = '.veyq-node-header-menu-item'

/** The ids of the header's buttons, in order. */
export const headerButtonIds = (w: VueWrapper): string[] => w.findAll('.veyq-node-header-action').map((b) => b.attributes('data-action') ?? '')

/** The menu's entries if it is open now. */
const openEntries = (): HTMLElement[] => [...document.body.querySelectorAll<HTMLElement>(ENTRY)]

const idOf = (e: HTMLElement): string => [...e.classList].find((c) => c.startsWith('veyq-node-header-action-'))!.slice('veyq-node-header-action-'.length)

/** Open this header's ⋯ menu with a real click and return its entries in order (none where it draws no ⋯). The menu is
 *  left open: close it with a second click on the ⋯, or pick an entry. */
export async function openHeaderMenu(w: VueWrapper): Promise<HTMLElement[]> {
  const more = w.find(MORE)
  if (!more.exists()) return []
  await more.trigger('click')
  await flushPromises()
  return openEntries()
}

/** Read the ⋯ menu's entries and close it again, so the next read (of this header or another) starts from a closed menu. */
async function readMenu<T>(w: VueWrapper, read: (entries: HTMLElement[]) => T): Promise<T> {
  const entries = await openHeaderMenu(w)
  const out = read(entries)
  if (entries.length) {
    await w.find(MORE).trigger('click')
    await flushPromises()
  }
  return out
}

/** The ids of the menu's entries, in order. */
export const headerMenuIds = (w: VueWrapper): Promise<string[]> => readMenu(w, (es) => es.map(idOf))

/** The labels of the menu's entries, in order. */
export const headerMenuLabels = (w: VueWrapper): Promise<string[]> => readMenu(w, (es) => es.map((e) => e.textContent?.trim() ?? ''))

/** Every label the header offers, its buttons' (their accessible names) then its menu entries'. */
export async function headerActionLabels(w: VueWrapper): Promise<string[]> {
  return [...w.findAll('.veyq-node-header-action').map((b) => b.attributes('aria-label') ?? ''), ...(await headerMenuLabels(w))]
}

/** Every action the header offers, buttons then menu entries, as ids (opens the menu). */
export async function headerActionIds(w: VueWrapper): Promise<string[]> {
  return [...headerButtonIds(w), ...(await headerMenuIds(w))]
}

/** Open the menu and click the entry of this id (a real click on its content); resolves with the entry. */
export async function chooseHeaderEntry(w: VueWrapper, id: string): Promise<HTMLElement> {
  const entries = await openHeaderMenu(w)
  const entry = entries.find((e) => idOf(e) === id)
  if (!entry) throw new Error(`no header menu entry "${id}" among: ${entries.map(idOf).join(', ')}`)
  entry.querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
  await flushPromises()
  return entry
}

/** For a test that mounts a whole page into `document.body` and reads the DOM, not a wrapper: open the (first) header's ⋯
 *  with a real click and return its entries' ids in order, the menu left open (none where it draws no ⋯). */
export async function documentHeaderMenuIds(): Promise<string[]> {
  const more = document.body.querySelector<HTMLElement>(`.veyq-node-header ${MORE}`)
  if (!more) return []
  more.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
  return openEntries().map(idOf)
}

/** As {@link chooseHeaderEntry}, for a page mounted into `document.body`: open the header's ⋯ and click the entry of this id. */
export async function chooseDocumentHeaderEntry(id: string): Promise<void> {
  const ids = await documentHeaderMenuIds()
  const entry = openEntries().find((e) => idOf(e) === id)
  if (!entry) throw new Error(`no header menu entry "${id}" among: ${ids.join(', ')}`)
  entry.querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
  await flushPromises()
}

/** For a page mounted into `document.body`: rename the node on screen in place - a real click on the header's name, `name`
 *  typed into its field, Enter (ADR 0110 decision 11). Throws where the name is no button (no rename offered). */
export async function renameInHeader(name: string): Promise<void> {
  const button = document.body.querySelector<HTMLElement>('.veyq-node-header button[data-testid="node-header-rename"]')
  if (!button) throw new Error('the header offers no rename in place')
  button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
  const input = document.body.querySelector<HTMLInputElement>('.veyq-node-header input[data-testid="node-header-name-input"]')!
  input.value = name
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
  await flushPromises()
}
