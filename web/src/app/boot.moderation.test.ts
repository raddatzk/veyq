import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setupStatus } from '../test/models'

vi.mock('../features/account/instance/instance', () => ({ getSetupStatus: vi.fn() }))
vi.mock('./sessionRefresh', () => ({ refreshSession: vi.fn(), isSessionRefusal: vi.fn() }))
vi.mock('../features/account/unlock/unlock', () => ({ restoreVaultFromCache: vi.fn() }))
vi.mock('./devAutoUnlock', () => ({ maybeDevAutoUnlock: vi.fn() }))

import { boot } from './boot'
import { getSetupStatus } from '../features/account/instance/instance'
import { refreshSession } from './sessionRefresh'
import { restoreVaultFromCache } from '../features/account/unlock/unlock'
import { maybeDevAutoUnlock } from './devAutoUnlock'
import { EXPECTED_MAJOR } from '../shared/state/capabilities'

/**
 * On the moderation surface's page boot opens nothing a device holds (ADR 0099 § 6, owner decision Q2): a revived
 * session stays, but neither the remembered vault nor the dev auto-unlock runs, so the page that will open reported
 * content never holds a vault a convenience wrap opened. The control: the same boot on the member's page restores it.
 */
describe('boot on the moderation page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getSetupStatus).mockResolvedValue(
      setupStatus({ setupRequired: false, capabilities: [], version: { major: EXPECTED_MAJOR, build: 0 } }),
    )
    vi.mocked(refreshSession).mockResolvedValue(undefined)
    vi.mocked(restoreVaultFromCache).mockResolvedValue(true)
  })

  afterEach(() => vi.unstubAllGlobals())

  it.each(['/moderation', '/moderation/'])('%s revives the session and restores no vault', async (pathname) => {
    vi.stubGlobal('location', { pathname })
    await boot()
    expect(refreshSession).toHaveBeenCalled()
    expect(restoreVaultFromCache).not.toHaveBeenCalled()
    expect(maybeDevAutoUnlock).not.toHaveBeenCalled()
  })

  it('control: the member page restores the remembered vault', async () => {
    vi.stubGlobal('location', { pathname: '/' })
    await boot()
    expect(restoreVaultFromCache).toHaveBeenCalled()
    expect(maybeDevAutoUnlock).toHaveBeenCalled()
  })
})
