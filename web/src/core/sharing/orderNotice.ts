// A guest uploader's notice of an authority's removal order (TCO Art. 11, ADR 0093 § 12, uc.moderation.authority-order
// step 5). A guest has no account, so a moderator's measure tells it nothing (DSA Art. 17(2)); a removal order does: the
// link answers it, for the identity whose upload the order took down, even while the link itself is blocked. Only the
// client that holds that identity's contributor token can ask (`getShareOrderNotice`), so the page asks only when this
// device holds a guest session for the link — a visitor who never named themselves sends nothing. Anything the server
// will not tell (another identity, a moderator's measure, a deferral the authority asked for) is the link's ordinary
// unknown answer, which reads here as "nothing to tell".
import { publicSharesApi } from '../../api/client'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import { loadGuestSession } from './guestIdentity'

/** What the guest is told: when each removal order was received, and where to ask for the reasons or a copy. */
export type OrderNotice = { receivedAt: Date[]; contact: string | null }

/** The notice for this device's identity on `shareId`, or null — no session here, or nothing to tell. */
export async function loadOrderNotice(shareId: string): Promise<OrderNotice | null> {
  const session = loadGuestSession(shareId)
  if (!session) return null
  try {
    const notice = await publicSharesApi().getShareOrderNotice({ shareId, xContributorToken: session.contributorToken })
    return { receivedAt: notice.removals.map((r) => r.orderReceivedAt), contact: notice.contactAddress ?? null }
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.notFound)) return null
    throw e
  }
}
