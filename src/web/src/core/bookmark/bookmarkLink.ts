// The link a bookmark keeps (ADR 0120 decision 4): a public link as a member pasted it, checked and normalized before it
// is sealed, never fetched — for the create dialog, the meta codec that re-checks what it opens, the open and report
// hand-off and the share page's report parameter.
//
// The rules are the platform's Kotlin (`src/mobile/core/…/sharing/ReceivedLink.kt`, ADR 0155 decision 7.2), one home for
// the web and the apps, held by contracts/platform/share-links/cases.v1.json; the web draws them through
// `@veyq/core-js/sharing`. This file holds no rule: it keeps the web's own shapes (`ShareLink`, the `{ ok }` answer) around
// the façade's plain values. A link is `<origin>/s/<shareId>#k=<base64url K_share>` (`core/sharing/shareLink.ts` builds
// it, `views/ShareView.vue` reads the fragment's `k=` among `&`-separated parameters); what is kept is exactly that.
import * as core from '@veyq/core-js/sharing'

/** Why a pasted text is no link to keep — each its own words in the create dialog (`node.bookmark.problem.*`). */
export type LinkProblem = 'empty' | 'tooLong' | 'notAUrl' | 'notHttps' | 'credentials' | 'notAShare' | 'noKey'

/** A link that may be kept: its normalized form, its origin and its share id. */
export interface ShareLink {
  link: string
  origin: string
  shareId: string
}

/** Check and normalize a pasted link. Fails closed: anything that is not exactly a share link with its key is refused. */
export function parseShareLink(input: string): { ok: true; value: ShareLink } | { ok: false; problem: LinkProblem } {
  const p = core.parseShareLink(input)
  if (p.problem != null) return { ok: false, problem: p.problem as LinkProblem }
  return { ok: true, value: { link: p.link!, origin: p.origin!, shareId: p.shareId! } }
}

/** A kept link read back: the same check, and it must be the normalized form itself (a meta written by anything else opens
 *  nothing). Null otherwise. */
export function keptLink(link: unknown): ShareLink | null {
  if (typeof link !== 'string') return null
  const kept = core.keptLink(link)
  return kept ? { link: kept.link, origin: kept.origin, shareId: kept.shareId } : null
}

/** The host a kept link names, as a reader is shown it (`photos.example.org`, a port kept). */
export function hostOf(link: ShareLink): string {
  return core.hostOf(link.origin)
}

/** The same link with the share page asked to open its own report dialog (ADR 0120 decision 6). */
export function reportLinkOf(link: ShareLink): string {
  return core.reportLinkOf(link.link)
}

/** Whether a fragment asks the share page to open its report dialog: the `report` parameter of {@link reportLinkOf}. */
export const asksForReport: (hash: string) => boolean = core.asksForReport

/** The fragment without the `report` parameter: what the share page keeps in its URL once it has read it. */
export const withoutReport: (hash: string) => string = core.withoutReport
