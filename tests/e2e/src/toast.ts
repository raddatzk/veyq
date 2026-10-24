// "A toast says X": the one way a spec reads a toast.
//
// Both apps draw their toasts with Reka's Toast (src/web/src/shared/ui/VeyqToaster.vue, src/admin-web's twin). That
// puts the text on the page TWICE: the toast's own title inside its viewport (`role="region"`, named by
// `ui.notifications`), and, for about a second, Reka's live-region copy portaled to the body (`role="alert"`, the
// region's name in front of the text). An unscoped `page.getByText(x)` therefore resolves to two elements while the
// copy is there and fails in strict mode, or to one once it is gone: a spec that passes or fails on timing. The
// toast is read inside its viewport, by its whole text, and the copy is never in reach.
//
// The default region and the undo region carry the same name, so the locator reads either; the text decides.
import type { Locator, Page } from '@playwright/test'

/** The viewports' name, as `ui.notifications` gives it (src/web/src/i18n/index.ts, src/admin-web/src/i18n/index.ts). */
const NOTIFICATIONS = { en: 'Notifications', de: 'Benachrichtigungen' } as const

/** The toast whose whole text is `text`, in the page's toast viewports. */
export const toast = (page: Page, text: string | RegExp, lang: keyof typeof NOTIFICATIONS = 'en'): Locator =>
  page.getByRole('region', { name: NOTIFICATIONS[lang], exact: true }).getByText(text, { exact: true })
