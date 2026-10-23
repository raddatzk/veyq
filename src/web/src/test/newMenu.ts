// Test helpers for core's level "New" menu (`core/node/LevelView.vue`, inbox #406): a real click on its button opens the
// popup `VeyqMenu` (teleported to the body), a real click on an entry runs it. Used by every test that used to click a
// section's create button or tile.
import { flushPromises, type VueWrapper } from '@vue/test-utils'

const BUTTON = '.veyq-new-menu-button'
const ITEM = '.veyq-menu__item'

/** Whether the level draws a "New" menu button at all (a READ guest's does not). */
export const hasNewMenu = (w: VueWrapper): boolean => w.find(BUTTON).exists()

/** The entries of the menu if it is open now. */
const openItems = (): HTMLElement[] => [...document.body.querySelectorAll<HTMLElement>(ITEM)]

/** Open the menu with a real click on its button (left alone if it is already open: the button toggles) and return the
 *  entry elements in order. */
export async function openNewMenu(w: VueWrapper): Promise<HTMLElement[]> {
  if (openItems().length === 0) {
    await w.find(BUTTON).trigger('click')
    await flushPromises()
  }
  return openItems()
}

/** The entries' labels, in order (opens the menu). */
export async function newMenuLabels(w: VueWrapper): Promise<string[]> {
  return (await openNewMenu(w)).map((e) => e.textContent?.trim() ?? '')
}

/** Whether the entry is disabled in the opened menu (the menu marks the item `aria-disabled`). */
export const isDisabled = (item: HTMLElement): boolean => item.getAttribute('aria-disabled') === 'true'

/** Open the menu and click the entry with this label (a real click on its content); resolves with the entry. */
export async function chooseNew(w: VueWrapper, label: string): Promise<HTMLElement> {
  const items = await openNewMenu(w)
  const item = items.find((e) => e.textContent?.trim() === label)
  if (!item) throw new Error(`no "New" entry "${label}" among: ${items.map((e) => e.textContent?.trim()).join(', ')}`)
  item.querySelector<HTMLElement>('.veyq-menu__item-content')!.click()
  await flushPromises()
  return item
}
