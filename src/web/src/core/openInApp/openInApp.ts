// The *Open in the app* banner's rule (ADR 0150 § O2): which page shows it, and which `veyq:` link it
// opens. Pure but for the step flag below, so the component only draws what this decides.
//
// A `veyq:` link carries NOTHING (§ O1): a custom scheme is owned by no one — any app on the device may claim `veyq:` and
// receive what a page sends there, and the link lands in the browser's history and on a desktop's command line. So the two
// hrefs are these constants and nothing else, never built from `location`; the app reads only which of its two screens to
// show. On the share page the link the person received reaches the app by the clipboard instead, on their click
// ([linkToCopy]), normalized as a bookmark keeps it (no `&report`, no other fragment parameter); its password is not copied.
//
// Where it shows (owner 2026-11-19): on every page — the app is the safer client, not served by the operator (ADR 0026) —
// opening `veyq://open`; on the share page `veyq://link`; on the four pages a mailed link opens (ADR 0148 decision 6) only
// once the page's own step succeeded (before it, the step is the web's); never on a moderator's pages (the surface and a
// review's `#moderation`), which the app has no screen for.
import { onUnmounted, ref, watch, type WatchSource } from 'vue'
import { parseShareLink } from '../bookmark/bookmarkLink'

/** The app where it is (ADR 0150 § O1). */
export const APP_OPEN = 'veyq://open'
/** The app's *Open a link* (ADR 0150 § O1): the person pastes the link they copied there. */
export const APP_LINK = 'veyq://link'

/** Which of the two links a page's banner opens. */
export type AppForm = 'open' | 'link'

/** The pages a mailed link opens (ADR 0148 decision 6; `router.ts`): the banner waits there for the page's step. */
export const MAILED_PAGES: readonly string[] = ['tenant-accept', 'recover', 'confirm-email', 'confirm-email-change']

/** The banner's form on the page named [routeName] with the fragment [hash], or null where it shows none. */
export function bannerForm(routeName: unknown, hash: string, stepDone: boolean): AppForm | null {
  if (routeName === 'moderation') return null
  if (routeName === 'share') return hash === '#moderation' ? null : 'link'
  if (typeof routeName === 'string' && MAILED_PAGES.includes(routeName)) return stepDone ? 'open' : null
  return 'open'
}

/** The href of [form]: one of the two constants, never anything of the page. */
export function hrefOf(form: AppForm): string {
  return form === 'link' ? APP_LINK : APP_OPEN
}

/** What the share page's click puts on the clipboard: the page's link normalized as a bookmark's, or null without a key. */
export function linkToCopy(pageHref: string): string | null {
  const parsed = parseShareLink(pageHref)
  return parsed.ok ? parsed.value.link : null
}

// A mailed page's step, set by the page itself while it is mounted (`afterTheStep`). One page at a time is mounted.
const stepDone = ref(false)

/** Whether the mailed page on screen has done its step. */
export const mailedStepDone = (): boolean => stepDone.value

/** For a mailed page's setup: the banner appears once [done] is true, and is gone again when the page unmounts. */
export function afterTheStep(done: WatchSource<boolean>): void {
  watch(done, (d) => (stepDone.value = d), { immediate: true })
  onUnmounted(() => (stepDone.value = false))
}
