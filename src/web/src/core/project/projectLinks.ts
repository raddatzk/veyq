// The veyq project's own pages, on its website (https://veyq.app/, ADR 0087), which the client links: the footer's
// "Why is veyq secure?" and "Donate", and the donation banner's link (uc.platform.support-the-project, ADR 0124). The
// one home of these addresses. Locale-aware: a German page sits under /de/ at the English page's path, the site's own
// convention. Every such link opens in a tab of its own with `PROJECT_LINK_REL`, so a share page keeps its `#k=` key
// and the website learns nothing of the page that linked it.
const SITE = 'https://veyq.app/'

const PATHS = {
  // "Is my data safe?", the Use area's plain-language page on what the server can and cannot see.
  security: 'privacy/is-my-data-safe/',
  donate: 'donate/',
} as const

export type ProjectPage = keyof typeof PATHS

export const PROJECT_LINK_REL = 'noopener noreferrer'

/** The website's `page` in the reader's language: German under `/de/`, every other locale the English root. */
export function projectPageUrl(page: ProjectPage, locale: string): string {
  return `${SITE}${locale === 'de' ? 'de/' : ''}${PATHS[page]}`
}
