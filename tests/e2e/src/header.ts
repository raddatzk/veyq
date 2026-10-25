// "The node header's action X": the one way a spec reaches an action of the header every node page opens with.
//
// Core's `NodeHeader` (src/web/src/core/node/components/NodeHeader.vue, ADR 0110) shows as many actions as fit as buttons
// and the rest as entries of one ⋯ menu ("More actions for “<name>”"). On a wide header the node's name comes first, so
// whether an action is a button depends on the name's length and the width: Share, a Memory's third action, is a button
// beside "Rom 2026" at 1280 px and a menu entry beside a 30-character name. A spec that wants the action, not the
// layout, goes through here — the button where it is one, the ⋯ entry otherwise. An action that is neither fails.
//
// The choice is read from a SETTLED header: one whose layout was measured at the width it has now. Right after a resize
// (`page.setViewportSize`) the header still draws the old width's layout until its ResizeObserver's frame has measured, so
// "Add banner" can be a ⋯ entry of the 1280 px layout while the 1400 px one makes it a button — the ⋯ then opens without
// it. The header names the width its drawn layout belongs to (`data-layout-width`, NodeHeader.vue); the
// helper waits until it equals the header's `clientWidth`, never a fixed sleep.
import { expect, type Locator, type Page } from '@playwright/test'

/** The ⋯'s accessible name, as `node.rowMore` gives it (src/web/src/i18n/index.ts), up to the node's name. */
const MORE = /^More actions for /

const nodeHeader = (page: Page): Locator => page.locator('header.veyq-node-header')

/** The header's action `label` as a button, and its ⋯ — once the header has drawn either, laid out at its current width. */
async function settle(page: Page, label: string): Promise<{ button: Locator; more: Locator }> {
  const header = nodeHeader(page)
  const button = header.getByRole('button', { name: label, exact: true })
  const more = header.getByRole('button', { name: MORE })
  await expect(button.or(more).first()).toBeVisible()
  await expect
    .poll(
      () => header.evaluateAll((all) => all.length > 0 && all.every((h) => (h as HTMLElement).dataset.layoutWidth === String((h as HTMLElement).clientWidth))),
      { message: 'the node header is laid out at its current width' },
    )
    .toBe(true)
  return { button, more }
}

/** Run the header's action `label`: click its button, or open the ⋯ and pick its entry. */
export async function headerAction(page: Page, label: string, options: { timeout?: number } = {}): Promise<void> {
  const { button, more } = await settle(page, label)
  if (await button.isVisible()) return button.click(options)
  await more.click(options)
  await page.getByRole('menuitem', { name: label, exact: true }).click(options)
}

/** The header offers action `label` (a button, or an entry of its ⋯ menu, which is closed again after the look). */
export async function expectHeaderAction(page: Page, label: string): Promise<void> {
  const { button, more } = await settle(page, label)
  if (await button.isVisible()) return
  await more.click()
  await expect(page.getByRole('menuitem', { name: label, exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
}
