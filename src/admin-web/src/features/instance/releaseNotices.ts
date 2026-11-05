import { instanceApi } from '../../api/client'
import {
  InstanceMailSettingsConfirmationEnum, InstanceReleaseNoticeKindEnum,
  type InstanceMailSettings, type InstanceReleaseNotice, type InstanceReleaseNotices,
} from '../../api/generated/instance'

export type { InstanceReleaseNotice, InstanceReleaseNotices } from '../../api/generated/instance'
export { InstanceReleaseNoticeKindEnum } from '../../api/generated/instance'

/**
 * The daily release check and what it found (uc.admin.learn-of-new-releases, ADR 0097) — Server-Owner only. The
 * notices are those whose release is still newer than the running one; `releaseLine: false` is a build with no release
 * stamp (or a snapshot), which checks nothing whatever the switch says. Reading changes nothing.
 */
export function getReleaseNotices(): Promise<InstanceReleaseNotices> {
  return instanceApi().getInstanceReleaseNotices()
}

/** Switch the check on or off, and choose its source: a mirror's `http(s)` URL, or `null` for the published GitHub
 *  Releases. Applies from the next check. Refusal: 400 `invalid_request` (not an http(s) URL). */
export function setReleaseCheck(enabled: boolean, source: string | null): Promise<InstanceReleaseNotices> {
  return instanceApi().setInstanceReleaseCheck({ instanceReleaseCheckUpdate: { enabled, source } })
}

/**
 * Where a newly found notice is also mailed: the Server Owner's address once the instance mail is confirmed, else
 * `null` — then the notices are only shown here and by `veyq-admin release` (ADR 0097 decisions 4 and 5). The check
 * itself never waits on mail: it is on by default, and a fresh instance has none.
 */
export function noticesMailedTo(mail: InstanceMailSettings): string | null {
  return mail.confirmation === InstanceMailSettingsConfirmationEnum.Confirmed ? mail.address : null
}

/** The Run area's upgrade page (ADR 0087) — the same link the notice mail carries. */
export const UPGRADE_PAGE = 'https://veyq.app/run/upgrade/'

/** An unscheduled major is the one notice that means "now" (ADR 0085 decision 4): the console and the CLI mark it. */
export function isUrgent(notice: InstanceReleaseNotice): boolean {
  return notice.kind === InstanceReleaseNoticeKindEnum.UnscheduledMajor
}

/** A notice's date as the day it names, `YYYY-MM-DD` — the generated client reads a `format: date` as a `Date` at UTC
 *  midnight, so formatting it in the local zone could show the day before. */
export function noticeDay(date: Date | null): string | null {
  return date ? date.toISOString().slice(0, 10) : null
}
