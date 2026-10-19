// The instance privacy notice (ADR 0035 decision 8, GDPR Art. 13; uc.admin.privacy-notice): veyq's fixed text,
// rendered by the server with the values the Server Owner set and the switches active for the space this host serves,
// served to anyone at `GET /api/operator/privacy-notice` and shown at `/privacy` (router name `privacy-notice`). Art. 13
// applies at collection, so every place that collects links here, at the passage that concerns it: the invite
// acceptance (`account`), the report dialog's contact (`reporter-contact`), a guest's first-write notice
// (`guest-write-record`) — and the footer of every page (`OperatorIdentification`) to the top. Information, not consent:
// nothing here is accepted, and reading it records nothing. The client renders what it is served and never edits it.
import { publicOperatorApi } from '../../api/client'
import type { PrivacyNotice } from '../../api/generated/operator'

// The notice as the endpoint serves it: each passage's `id` is its anchor, a `separate` one a right to object (GDPR
// Art. 21(4)); `draftBanner` is there while the text awaits a qualified legal review.
export type { PrivacyNotice, PrivacyNoticePassage } from '../../api/generated/operator'

/** The public page of the notice. */
export const PRIVACY_PATH = '/privacy'

/**
 * The anchors the collecting surfaces link — each a passage id of `contracts/terms/privacy-notice.json`, held there by
 * `PrivacyNoticeRendererTest.theAnchorsTheClientsLinkExistAndAreStable`.
 */
export const PRIVACY_ANCHOR = {
  account: 'account',
  reporterContact: 'reporter-contact',
  guestWriteRecord: 'guest-write-record',
} as const

/** The notice's address, at [anchor] when one is named. */
export function privacyLink(anchor?: (typeof PRIVACY_ANCHOR)[keyof typeof PRIVACY_ANCHOR]): string {
  return anchor ? `${PRIVACY_PATH}#${anchor}` : PRIVACY_PATH
}

/** The notice as this host serves it, or null when the read failed (fail-open: nothing waits on it). */
export async function loadPrivacyNotice(): Promise<PrivacyNotice | null> {
  try {
    return await publicOperatorApi().getPrivacyNotice()
  } catch {
    return null
  }
}
