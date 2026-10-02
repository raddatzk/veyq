// The type properties src/test/apiMock.ts exists for, each pinned by a `@ts-expect-error`: if the helper stops
// refusing one, the directive is unused and `npm run typecheck` fails (TS2578). The runtime half checks that what
// the helper installs is what the code under test gets back from the factory.
import { describe, it, expect, vi } from 'vitest'
import type { PublicShare, SharingApi } from '../api/generated/sharing'
import { fakeApi, stubApi } from './apiMock'

vi.mock('../api/client', () => ({ publicSharesApi: vi.fn() }))
import { publicSharesApi } from '../api/client'

const SHARE: PublicShare = { role: 'VIEWER', passwordRequired: false, nodes: [], media: [] }

describe('stubApi', () => {
  it('serves each payload from the factory', async () => {
    const api = stubApi(publicSharesApi, { resolveShare: SHARE })
    expect(await publicSharesApi().resolveShare({ shareId: 't' })).toBe(SHARE)
    expect(api.resolveShare).toHaveBeenCalledWith({ shareId: 't' })
  })

  it('refuses a payload the model does not match', () => {
    // @ts-expect-error -- a stale field: `sealedDisplayName` is not on PublicShare (TS2353)
    stubApi(publicSharesApi, { resolveShare: { ...SHARE, sealedDisplayName: 'x' } })
    // @ts-expect-error -- a required field missing: `passwordRequired` (TS2741)
    stubApi(publicSharesApi, { resolveShare: { role: 'VIEWER', nodes: [], media: [] } })
    // @ts-expect-error -- a method the API does not have
    stubApi(publicSharesApi, { resolveShares: SHARE })
  })

  it('types the returned mocks, so a later payload is checked too', () => {
    const api = stubApi(publicSharesApi, { resolveShare: SHARE })
    // @ts-expect-error -- a stale field through the returned Mock (TS2353)
    api.resolveShare.mockResolvedValueOnce({ ...SHARE, sealedDisplayName: 'x' })
    api.resolveShare.mockResolvedValueOnce({ ...SHARE, role: 'CONTRIBUTOR' })
  })
})

describe('fakeApi', () => {
  it('runs each implementation, and keeps a typed vi.fn the test already holds', async () => {
    const terms = vi.fn<SharingApi['getShareTerms']>().mockResolvedValue({ tenantId: 't', termsVersion: 1 })
    const api = fakeApi(publicSharesApi, {
      resolveShare: async ({ shareId }) => ({ ...SHARE, nodeId: shareId }),
      getShareTerms: terms,
    })
    expect((await publicSharesApi().resolveShare({ shareId: 'n1' })).nodeId).toBe('n1')
    expect(api.getShareTerms).toBe(terms)
  })

  it('refuses an implementation that would switch the check off', () => {
    // @ts-expect-error -- an untyped vi.fn(): its `any` return accepts any payload
    fakeApi(publicSharesApi, { resolveShare: vi.fn() })
    // @ts-expect-error -- a function of the wrong shape
    fakeApi(publicSharesApi, { resolveShare: async () => 42 })
    // @ts-expect-error -- a method the API does not have
    fakeApi(publicSharesApi, { resolveShares: async () => SHARE })
  })
})
