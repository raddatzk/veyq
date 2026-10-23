// Test helpers for core's node header (`core/node/components/NodeHeader.vue`, ADR 0110): its buttons, and its ⋯ menu — a
// real click on the ⋯ opens the popup `VeyqMenu` (portalled to the body), a real click on an entry runs it. Unmeasured, as
// in every unit test, each action that may be a button is one, so the menu holds the menu-only ones: report and delete.
import { flushPromises, type VueWrapper } from '@vue/test-utils'

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
