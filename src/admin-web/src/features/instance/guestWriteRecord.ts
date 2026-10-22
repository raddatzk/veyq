import { instanceGuestWriteRecordApi } from '../../api/client'
import type { GuestWriteRecordErasure, InstanceGuestWriteRecord } from '../../api/generated/instance'

export type { GuestWriteRecordErasure, InstanceGuestWriteRecord } from '../../api/generated/instance'

/** The retention's range and default (ADR 0092 § 2): one to thirty days, seven until set. */
export const MIN_DAYS = 1
export const MAX_DAYS = 30
export const DEFAULT_DAYS = 7

/**
 * How many days the guest-write record keeps a row (ADR 0093 § 10, en.moderation.guest-write-record) — Server-Owner
 * only. The record has no switch: it is kept wherever a guest may write, which `core.public-shares.contribute` and
 * `.drop-box` decide (the Capabilities panel, `capability set`); this is only N.
 */
export function getGuestWriteRecord(): Promise<InstanceGuestWriteRecord> {
  return instanceGuestWriteRecordApi().getInstanceGuestWriteRecord()
}

/** Set N. A shortening reaches every kept row at the next purge, a lengthening only new ones. Refusal: 400
 *  `invalid_request` outside the range, which [parseRetentionDays] already refuses here. */
export function setGuestWriteRecord(retentionDays: number): Promise<InstanceGuestWriteRecord> {
  return instanceGuestWriteRecordApi().setInstanceGuestWriteRecord({ instanceGuestWriteRecordUpdate: { retentionDays } })
}

/**
 * A guest's objection (GDPR Art. 21): delete every live row one share identity wrote. Rows a hold copied stay until
 * their hold ends; the answer counts both. An id that is no UUID matches no route (404), which [isIdentityId]
 * already refuses here.
 */
export function eraseGuestWriteRecordsOfIdentity(identityId: string): Promise<GuestWriteRecordErasure> {
  return instanceGuestWriteRecordApi().eraseGuestWriteRecordsOfIdentity({ identityId })
}

/** Whether what was typed is a share identity's id — a UUID in its usual text form. */
export function isIdentityId(text: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(text.trim())
}

/** A whole number of days in the range, from what was typed — or undefined. The one rule the console and the CLI share. */
export function parseRetentionDays(text: string): number | undefined {
  const trimmed = text.trim()
  if (!/^\d+$/.test(trimmed)) return undefined
  const days = Number(trimmed)
  return days >= MIN_DAYS && days <= MAX_DAYS ? days : undefined
}

/** Above seven days the Server Owner owes a reason in their record of processing (ADR 0093 § 10, the supervisory
 *  practice): the console and the CLI say so beside the value; the server does not record it. */
export function needsReason(days: number): boolean {
  return days > DEFAULT_DAYS
}
