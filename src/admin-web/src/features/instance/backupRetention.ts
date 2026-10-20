import { instanceBackupRetentionApi } from '../../api/client'
import type { InstanceBackupRetention } from '../../api/generated/instance'

export type { InstanceBackupRetention } from '../../api/generated/instance'

/** B's range and default (ADR 0056 decision 8, the owner's Q21): seven to 365 days, thirty until set. */
export const MIN_DAYS = 7
export const MAX_DAYS = 365
export const DEFAULT_DAYS = 30

/**
 * How many days every backup veyq's tooling wrote is kept (ADR 0056 decision 8) — Server-Owner only. The capture tooling
 * reads it from the database: `veyqctl` prunes at every backup, upgrade and start, the cluster's gate at every start and
 * its daily job, and the erasure ledger drops its lines at the same age.
 */
export function getBackupRetention(): Promise<InstanceBackupRetention> {
  return instanceBackupRetentionApi().getInstanceBackupRetention()
}

/** Set B. The tooling applies it at its next run, to every capture as it then stands, the newest included. Refusal:
 *  400 `invalid_request` outside the range, which [parseRetentionDays] already refuses here. */
export function setBackupRetention(retentionDays: number): Promise<InstanceBackupRetention> {
  return instanceBackupRetentionApi().setInstanceBackupRetention({ instanceBackupRetentionUpdate: { retentionDays } })
}

/** A whole number of days in the range, from what was typed — or undefined. The one rule the console and the CLI share. */
export function parseRetentionDays(text: string): number | undefined {
  const trimmed = text.trim()
  if (!/^\d+$/.test(trimmed)) return undefined
  const days = Number(trimmed)
  return days >= MIN_DAYS && days <= MAX_DAYS ? days : undefined
}

/** Above thirty days the Server Owner owes a reason in their record of processing (ADR 0056 decision 8, ADR 0093 § Legal
 *  basis item 4): the console and the CLI say so beside the value; the server does not record it. */
export function needsReason(days: number): boolean {
  return days > DEFAULT_DAYS
}
