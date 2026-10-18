// Why a link in a member's share list serves no guest (inbox #457): the server's own answer, `ShareSummary.guestStops`,
// computed by the predicate every guest path passes (`ShareGuestGate.guestStops`), so the row cannot say "on" where a
// guest gets the unknown link's 404. This file only words it and picks the fix a dialog already has; it derives nothing
// from the capabilities it holds, because the server's answer is the gate's and a client-side copy could drift from it.
import { ShareGuestStopReasonEnum as Reason, type ShareGuestStop } from '../../api/generated/sharing'
import {
  CORE_PUBLIC_SHARES,
  CORE_PUBLIC_SHARES_CONTRIBUTE,
  CORE_PUBLIC_SHARES_DROP_BOX,
} from '../../features/platform/capabilities'
import { lastExpiryDay } from './linkRules'

/** What a row's owner can do about a stop with the dialog's own actions: edit the link, or renew it. */
export type GuestStopFix = 'edit' | 'renew'

/** One stop as a row says it: an i18n key of `share.guestStops.*` with its values, and the fix it offers with the
 *  button's own words ([fixKey]). */
export interface GuestStopLine {
  key: string
  /** For a switch: the i18n key naming the switched-off entry (`share.guestStops.entry.*`). */
  entryKey?: string
  /** For a lifetime: the days in effect. */
  days?: number
  fix: GuestStopFix | null
  fixKey?: string
}

const ENTRY_KEYS: Record<string, string> = {
  [CORE_PUBLIC_SHARES]: 'share.guestStops.entry.publicShares',
  [CORE_PUBLIC_SHARES_CONTRIBUTE]: 'share.guestStops.entry.contribute',
  [CORE_PUBLIC_SHARES_DROP_BOX]: 'share.guestStops.entry.dropBox',
}

/**
 * The lines a row shows for [stops], in the server's order. `revoked` is left out: the row already says "Switched off"
 * and offers its own way back, which no requirement refuses. A requirement an edit meets is fixed on the SAME link, its
 * address and key kept (owner, 2026-10-16), and the line says so: a password, or an end date while a day is left within
 * the lifetime (the edit dialog's date picker counts from when the link was made, `lastExpiryDay`). Past that, only a
 * renew helps, which makes a new link with a new address (ADR 0088 decision 8), and the line says that instead. A switch
 * is no member's to fix: the line names who can switch it back on.
 */
export function guestStopLines(stops: ShareGuestStop[], createdAt: Date, now: Date = new Date()): GuestStopLine[] {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return stops.flatMap((stop): GuestStopLine[] => {
    switch (stop.reason) {
      case Reason.Revoked:
        return []
      case Reason.Expired:
        return [{ key: 'share.guestStops.expired', fix: 'edit', fixKey: 'share.guestStops.expiredFix' }]
      case Reason.SpaceUnavailable:
        return [{ key: 'share.guestStops.spaceUnavailable', fix: null }]
      case Reason.OperatorBlocked:
        // A moderator's measure (uc.moderation.take-a-measure): no member's fix — a renew is refused while it stands.
        return [{ key: 'share.guestStops.operatorBlocked', fix: null }]
      case Reason.InstanceSwitch:
      case Reason.TenantSwitch:
        return [{
          key: stop.reason === Reason.InstanceSwitch ? 'share.guestStops.instanceSwitch' : 'share.guestStops.tenantSwitch',
          entryKey: ENTRY_KEYS[stop.capabilityId ?? ''] ?? 'share.guestStops.entry.other',
          fix: null,
        }]
      case Reason.LinkPassword:
        return [{ key: 'share.guestStops.linkPassword', fix: 'edit', fixKey: 'share.guestStops.linkPasswordFix' }]
      case Reason.LinkMaxDays: {
        const days = stop.maxDays ?? 0
        const last = lastExpiryDay(days, createdAt)
        const editable = last !== null && last.getTime() >= today.getTime()
        return editable
          ? [{ key: 'share.guestStops.linkMaxDays', days, fix: 'edit', fixKey: 'share.guestStops.linkMaxDaysFix' }]
          : [{ key: 'share.guestStops.linkMaxDaysOld', days, fix: 'renew', fixKey: 'share.reissue' }]
      }
      default:
        // A reason this client does not know (a newer server): still say that guests cannot open the link.
        return [{ key: 'share.guestStops.unknown', fix: null }]
    }
  })
}
