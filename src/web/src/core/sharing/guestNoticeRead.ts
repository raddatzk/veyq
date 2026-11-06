// The one read of a guest uploader's notice (`getShareGuestNotice`, ADR 0093 §§ 7, 12): a module of its own because every
// blocked placeholder on a guest's page asks it when it mounts (`StatementNotice`), so a unit test that mounts one would
// reach the network. `src/test/setup.ts` stands in for this module in every unit test — nothing to tell — and a test of
// the notice itself takes it back (`vi.unmock`), as `features/moderation/statement/statementsRead.ts` is.
import { publicSharesApi } from '../../api/client'
import type { ShareGuestNotice } from '../../api/generated/sharing'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import { apiBaseOf, shareIdOf, type ShareAt } from './shareAt'

/** What the server tells the identity whose client holds [contributorToken] on the share [at] (asked of the instance that
 *  serves it, ADR 0121); null for its unknown answer. */
export async function readGuestNotice(at: ShareAt, contributorToken: string): Promise<ShareGuestNotice | null> {
  try {
    return await publicSharesApi(apiBaseOf(at)).getShareGuestNotice({ shareId: shareIdOf(at), xContributorToken: contributorToken })
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.notFound)) return null
    throw e
  }
}
