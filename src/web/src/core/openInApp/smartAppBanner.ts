// Apple's Smart App Banner (owner 2026-11-19, ADR 0150 § O2): once the veyq app has an App Store id — the publisher's
// step, not an instance's — every page carries `<meta name="apple-itunes-app" content="app-id=…">` and Safari on iOS draws
// its own banner, which opens the app or its store page. Where Safari draws it, our banner stays hidden, so two never
// stack. The tag carries no `app-argument`: whatever it held would reach the app as a URL a page chose, and a page's own
// address may hold a key (`#k=`); the app learns nothing from the web but which screen to show, and that is ours to send.
//
// Off until the id exists: [APP_STORE_ID] is null, no tag is written (`vite.config.ts` writes it into `index.html` at build
// time, as Safari reads it from the document it loads) and our banner shows on iOS as everywhere.

/** The veyq app's App Store id (digits), or null while it has none. Set by the publisher once the app is listed. */
export const APP_STORE_ID: string | null = null

/** The meta tag's content for [id] — `app-id=<id>` and nothing else —, or null for no id or one that is not digits. */
export function smartAppBannerContent(id: string | null): string | null {
  return id !== null && /^\d{1,20}$/.test(id) ? `app-id=${id}` : null
}

/**
 * Whether Safari on iOS (or iPadOS) draws the Smart App Banner for this page: an id is set and [userAgent] is Safari on an
 * iPhone, an iPod or an iPad — an iPad's desktop-class Safari says "Macintosh" and has touch points. Other iOS browsers
 * (`CriOS`, `FxiOS`, `EdgiOS`, …) draw none, so ours stays.
 */
export function safariDrawsItsBanner(id: string | null, userAgent: string, maxTouchPoints: number): boolean {
  if (smartAppBannerContent(id) === null) return false
  const ios = /iPhone|iPad|iPod/.test(userAgent) || (/Macintosh/.test(userAgent) && maxTouchPoints > 1)
  const otherBrowser = /CriOS|FxiOS|EdgiOS|OPiOS|OPT\/|GSA\/|YaBrowser|DuckDuckGo|Brave/.test(userAgent)
  return ios && /Safari\//.test(userAgent) && !otherBrowser
}
