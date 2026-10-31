// What a guest uploader is told of a measure on its upload, on the link it came through (ADR 0093 §§ 7, 12;
// uc.moderation.authority-order step 5, uc.moderation.object-to-a-measure step 5): an authority's removal order (TCO
// Art. 11) — when it was received, and where to ask for the reasons or a copy — and a moderator's statement of reasons
// (DSA Art. 17), its clear fields without the facts. A guest has no account, so this is the only way it is told. Only the
// client that holds that identity's contributor token can ask, so the page asks only when this device holds a guest
// session for the link — a visitor who never named themselves sends nothing — and once per link for the whole page:
// the § 12 notice (`OrderNotice`) and every blocked placeholder (`StatementNotice`) read the same answer. Anything the
// server will not tell (another identity, a deferral an authority asked for, a lifted measure) is the link's ordinary
// unknown answer, which reads here as "nothing to tell".
import { reactive } from 'vue'
import type { ShareGuestStatement } from '../../api/generated/sharing'
import { loadGuestSession } from './guestIdentity'
import { readGuestNotice } from './guestNoticeRead'

/** What the guest is told: the removal orders by when each was received, the moderators' statements, and the contact. */
export type GuestNotice = { receivedAt: Date[]; statements: ShareGuestStatement[]; contact: string | null }

/**
 * The guest page open now (`ShareView` sets it) and what its link told this device's identity — the placeholders of the
 * page read their statement from here, as a member's read theirs from `statements`.
 */
export const guestPage = reactive({ shareId: null as string | null, notice: null as GuestNotice | null })

// The read is the page's and the session's: a guest who forgot itself ("Not you?") and the one who names itself next on
// this device each get their own identity's answer, never the other's.
let reading: { shareId: string; token: string | null; read: Promise<GuestNotice | null> } | null = null

/** The page at [shareId] is open: its placeholders and notice read that link's answer. */
export function openGuestPage(shareId: string): void {
  if (guestPage.shareId === shareId) return
  closeGuestPage()
  guestPage.shareId = shareId
}

/** The page closed: nothing of its answer stays for the next one. */
export function closeGuestPage(): void {
  guestPage.shareId = null
  guestPage.notice = null
  reading = null
}

/**
 * The notice for this device's identity on [shareId], read once per page (or [fresh]: the § 12 notice reads afresh where
 * it mounts, the link having gone away under the page), or null — no session here, or nothing to tell. A failed read
 * throws, and is asked again by the next one.
 */
export function loadGuestNotice(shareId: string, fresh = false): Promise<GuestNotice | null> {
  const token = loadGuestSession(shareId)?.contributorToken ?? null
  if (!fresh && reading?.shareId === shareId && reading.token === token) return reading.read
  if (reading && reading.token !== token && guestPage.shareId === shareId) guestPage.notice = null
  const read = (async () => {
    if (!token) return null
    const notice = await readGuestNotice(shareId, token)
    if (!notice) return null
    return {
      receivedAt: notice.removals.map((r) => r.orderReceivedAt),
      statements: notice.statements,
      contact: notice.contactAddress ?? null,
    }
  })()
  const entry = { shareId, token, read }
  reading = entry
  read.then(
    (n) => {
      if (reading === entry && guestPage.shareId === shareId) guestPage.notice = n
    },
    () => {
      if (reading === entry) reading = null
    },
  )
  return read
}
