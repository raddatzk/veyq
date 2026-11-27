// The *Open in the app* banner's rule (ADR 0150 § O2): which page shows it, and which `veyq:` link it
// opens. Pure but for the two flags below, so the component only draws what this decides.
//
// A custom scheme is owned by no one (§ O1): any app on the device may claim `veyq:` and receive what a page sends there, and
// the link lands in the browser's history and on a desktop's command line. So a `veyq:` link carries **no name and no key**:
// `veyq://link` nothing at all, `veyq://open` at most the page's origin and the opaque ids of the place on screen
// ([placeHref]: a scope id and a node id, or a Settings section) — never the fragment's slugs, which are names folded. The
// app opens that place only for a tenant it has open, through its own checked open (inbox #1415). On the share page the
// link the person received reaches the app by the clipboard instead, on their click ([linkToCopy]), normalized as a bookmark
// keeps it (no `&report`, no other fragment parameter); its password is not copied.
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

/** The place on screen as a `veyq:` link may carry it: opaque ids, or a Settings section's id. */
export type AppPlace = { scopeId: string; nodeId: string | null } | { section: string }

/** An id as the server mints it (a UUID): what [placeHref] carries, and nothing else. */
const ID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
/** A Settings section's id (`features/settings/sections.ts`). */
const SECTION_SHAPE = /^[a-z][a-z0-9-]*$/

/**
 * `veyq://open` carrying [origin] (the page's own, `scheme://host[:port]`) and [place]: `?i=<origin>&s=<scope>[&n=<node>]`
 * or `?i=<origin>&settings=<section>`. Plain `veyq://open` where there is no place, or where a value is not of its shape —
 * so a name can never ride it.
 */
export function placeHref(origin: string, place: AppPlace | null): string {
  if (!place || !/^https?:\/\/[^/?#@]+$/i.test(origin)) return APP_OPEN
  const i = `?i=${encodeURIComponent(origin)}`
  if ('section' in place) return SECTION_SHAPE.test(place.section) ? `${APP_OPEN}${i}&settings=${place.section}` : APP_OPEN
  if (!ID_SHAPE.test(place.scopeId)) return APP_OPEN
  if (place.nodeId === null) return `${APP_OPEN}${i}&s=${place.scopeId}`
  return ID_SHAPE.test(place.nodeId) ? `${APP_OPEN}${i}&s=${place.scopeId}&n=${place.nodeId}` : APP_OPEN
}

/** The href of [form]: `veyq://link` alone, or `veyq://open` with the place on screen ([placeHref]). */
export function hrefOf(form: AppForm, origin = '', place: AppPlace | null = null): string {
  return form === 'link' ? APP_LINK : placeHref(origin, place)
}

/** What the share page's click puts on the clipboard: the page's link normalized as a bookmark's, or null without a key. */
export function linkToCopy(pageHref: string): string | null {
  const parsed = parseShareLink(pageHref)
  return parsed.ok ? parsed.value.link : null
}

// The place on screen, set by the page that shows it (`SpaceView`: the open scope and node; the settings page: its section);
// null elsewhere and while it resolves. One page at a time is mounted.
const shown = ref<AppPlace | null>(null)

/** The place on screen ([placeHref]); null where none is. */
export const shownPlace = (): AppPlace | null => shown.value

/** For a page's setup: [place] is the place on screen while it is mounted. */
export function showsPlace(place: WatchSource<AppPlace | null>): void {
  watch(place, (p) => (shown.value = p), { immediate: true })
  onUnmounted(() => (shown.value = null))
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
