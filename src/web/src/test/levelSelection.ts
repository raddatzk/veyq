// The level's Select mode (inbox #421, `core/node/LevelView.vue`) driven through the DOM for a suite that mounts a host
// (the member's `MemoryHomeView`, a guest's `ShareTreePage`): every step a real click on the rendered element - the
// toolbar's Select, a row's checkbox, the bar's Move to… and Delete - never a call into the component.
import { flushPromises } from '@vue/test-utils'

async function click(el: Element): Promise<void> {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
function the<T extends Element>(selector: string): T {
  const el = document.body.querySelector<T>(selector)
  if (!el) throw new Error(`nothing matches ${selector}`)
  return el
}

/** Turn the level's Select mode on (its toolbar button). */
export async function startSelecting(): Promise<void> {
  await click(the('.veyq-level-toolbar .veyq-select-button'))
}

/** Tick the row checkbox of each named node (the checkbox is named by the node). */
export async function selectRows(...names: string[]): Promise<void> {
  for (const name of names) {
    const box = [...document.body.querySelectorAll<HTMLInputElement>('.veyq-level-list input[type="checkbox"]')].find((b) => b.getAttribute('aria-label') === name)
    if (!box) throw new Error(`no checkbox for "${name}"`)
    await click(box)
  }
}

/** The bar's Move to… */
export async function moveSelection(): Promise<void> {
  await click(the('.veyq-level-selection-bar .veyq-select-move'))
}

/** The bar's Delete. */
export async function deleteSelection(): Promise<void> {
  await click(the('.veyq-level-selection-bar .veyq-select-delete'))
}

/** The bar's count, as announced. */
export const selectionCount = (): string => document.body.querySelector('.veyq-level-selection-count')?.textContent?.trim() ?? ''
