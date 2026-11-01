// The shared per-language Markdown editor (`src/web/src/shared/ui/LocaleMarkdownEditor.vue`): the share note and a
// space's terms. One "Add language" button opens the editor, where the language is picked from those not added yet.
import { expect, type Locator, type Page } from '@playwright/test'

/** Add one language with its text through the editor inside `scope`, and wait for its dialog to close. */
export async function addLanguage(page: Page, scope: Locator | Page, language: string, markdown: string): Promise<void> {
  await scope.getByRole('button', { name: 'Add language', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Add a language', exact: true })
  // A Reka select: its options are portaled, so they are found on the page, not in the dialog.
  await dialog.getByRole('combobox', { name: 'Language', exact: true }).click()
  await page.getByRole('option', { name: language, exact: true }).click()
  await dialog.getByRole('textbox', { name: 'Markdown' }).fill(markdown)
  await dialog.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(dialog).toBeHidden()
}
