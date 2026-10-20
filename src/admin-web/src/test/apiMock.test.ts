// The type properties this package's copy of apiMock.ts exists for, each pinned by a `@ts-expect-error`: if the
// helper stops refusing one, the directive is unused and `npm run typecheck` fails (TS2578).
import { describe, it, expect, vi } from 'vitest'
import type { TenantInviteState, TenantsApi } from '../api/generated/tenants'
import { fakeApi, stubApi } from './apiMock'

vi.mock('../api/client', () => ({ tenantsApi: vi.fn() }))
import { tenantsApi } from '../api/client'

const STATE: TenantInviteState = {
  ownerEmail: 'o@example.com',
  inviteStatus: 'ACTIVE',
  inviteExpiresAt: new Date('2026-07-03T12:00:00Z'),
  bootstrapSecret: 's',
  delivery: 'SENT',
}

describe('stubApi', () => {
  it('serves each payload from the factory', async () => {
    const api = stubApi(tenantsApi, { resendTenantInvite: STATE })
    expect(await tenantsApi().resendTenantInvite({ tenantId: 't1' })).toBe(STATE)
    expect(api.resendTenantInvite).toHaveBeenCalledWith({ tenantId: 't1' })
  })

  it('refuses a payload the model does not match', () => {
    // @ts-expect-error -- a stale field: `state` is not on TenantInviteState (TS2353)
    stubApi(tenantsApi, { resendTenantInvite: { ...STATE, state: 'ACTIVE' } })
    // @ts-expect-error -- a required field missing: `bootstrapSecret` (TS2741)
    stubApi(tenantsApi, { resendTenantInvite: { ownerEmail: 'o@example.com', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), delivery: 'SENT' } })
    // @ts-expect-error -- a method the API does not have
    stubApi(tenantsApi, { resendInvites: STATE })
  })

  it('types the returned mocks, so a later payload is checked too', () => {
    const api = stubApi(tenantsApi, { resendTenantInvite: STATE })
    // @ts-expect-error -- a stale field through the returned Mock (TS2353)
    api.resendTenantInvite.mockResolvedValueOnce({ ...STATE, state: 'ACTIVE' })
    api.resendTenantInvite.mockResolvedValueOnce({ ...STATE, inviteStatus: 'SUPERSEDED' })
  })
})

describe('fakeApi', () => {
  it('runs each implementation, and keeps a typed vi.fn the test already holds', async () => {
    const revoke = vi.fn<TenantsApi['revokeTenant']>().mockResolvedValue(undefined)
    const api = fakeApi(tenantsApi, {
      resendTenantInvite: async ({ tenantId }) => ({ ...STATE, bootstrapSecret: tenantId }),
      revokeTenant: revoke,
    })
    expect((await tenantsApi().resendTenantInvite({ tenantId: 't9' })).bootstrapSecret).toBe('t9')
    expect(api.revokeTenant).toBe(revoke)
  })

  it('refuses an implementation that would switch the check off', () => {
    // @ts-expect-error -- an untyped vi.fn(): its `any` return accepts any payload
    fakeApi(tenantsApi, { resendTenantInvite: vi.fn() })
    // @ts-expect-error -- a function of the wrong shape
    fakeApi(tenantsApi, { resendTenantInvite: async () => 42 })
    // @ts-expect-error -- a method the API does not have
    fakeApi(tenantsApi, { resendInvites: async () => STATE })
  })
})
