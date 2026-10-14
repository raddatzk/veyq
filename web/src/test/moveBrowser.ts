// Core's move browser (`core/node/MoveBrowser.vue` inside `MoveNodeDialog`), read and driven through the DOM for a suite
// that mounts it (the dialog's own test, and each host that opens it): every step a real `click` on the rendered element -
// a crumb of the trail, a place of the list, "Move here" - never a call into the component.
import { flushPromises } from '@vue/test-utils'
import { t } from './i18n'

async function click(el: Element): Promise<void> {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}

const placeItems = () => [...document.body.querySelectorAll<HTMLLIElement>('.move-browser-places .veyq-level-item')]
const crumbButtons = () => [...document.body.querySelectorAll<HTMLButtonElement>('.move-browser-crumb')]

/** Whether the browser is on screen. */
export const browserOpen = (): boolean => document.body.querySelector('.move-browser') !== null

/** Each listed place: its name, whether it can be opened, and the reason shown where it cannot. */
export const places = (): [string, boolean, string][] =>
  placeItems().map((li) => [
    li.querySelector('.veyq-level-item-title')!.textContent ?? '',
    !li.querySelector<HTMLButtonElement>('.veyq-level-item-main')!.disabled,
    li.querySelector('[data-detail="blocked"]')?.textContent ?? '',
  ])

/** The listed places' names. */
export const placeNames = (): string[] => places().map(([name]) => name)

/** The place's own button (it opens the place). Throws when no place of that name is listed. */
export function placeButton(name: string): HTMLButtonElement {
  const li = placeItems().find((x) => x.querySelector('.veyq-level-item-title')?.textContent === name)
  if (!li) throw new Error(`no place "${name}" is listed (listed: ${placeNames().join(', ')})`)
  return li.querySelector<HTMLButtonElement>('.veyq-level-item-main')!
}

/** The trail's crumbs, top first. */
export const crumbs = (): string[] => crumbButtons().map((b) => b.textContent!.trim())

/** The crumb of the place on screen. */
export const currentCrumb = (): string | undefined =>
  crumbButtons().find((b) => b.getAttribute('aria-current') === 'location')?.textContent?.trim()

/** The dialog's "Move here" button. */
export const moveHereButton = (): HTMLButtonElement =>
  [...document.body.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === t('node.move.submit'))!

/** Why "Move here" is disabled at the place on screen ('' where it is not). */
export const whyNotHere = (): string => document.body.querySelector('.move-here-why')?.textContent?.trim() ?? ''

/** Go to a place: each step a crumb of the trail where it shows one of that name, else a listed place to open. */
export async function goTo(...path: string[]): Promise<void> {
  for (const name of path) {
    const crumb = crumbButtons().find((b) => b.textContent!.trim() === name)
    await click(crumb ?? placeButton(name))
  }
}

/** Go to a place (see {@link goTo}) and choose "Move here". */
export async function moveHere(...path: string[]): Promise<void> {
  await goTo(...path)
  await click(moveHereButton())
}
