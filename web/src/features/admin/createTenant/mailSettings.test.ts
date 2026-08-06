// @vitest-environment happy-dom
// Web data-layer tests for tenant mail settings (uc.admin.tenant-mail-settings, cr-email-tenant-mail-settings-web):
// the facade request shaping over the generated tenants client. The password is write-only — the "leave blank to
// keep" pattern lives in the panel; here we assert the facade passes the update through verbatim (so an omitted
// `password` really is omitted on the wire, not sent as null). Operator-blind: mail config is operator-visible, no
// crypto here.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  tenantsApi: vi.fn(),
}))

import { getTenantMailSettings, updateTenantMailSettings } from './tenants'
import { tenantsApi } from '../../../api/client'

const TENANT = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'

const VIEW = {
  host: 'smtp.example.com', port: 587, from: 'noreply@x.z', username: 'fam',
  tls: true, enabled: true, passwordSet: true, subjectTemplate: null, bodyTemplate: null, configSecretState: 'OK',
}

function stubApi() {
  const api = {
    getTenantMailSettings: vi.fn().mockResolvedValue(VIEW),
    updateTenantMailSettings: vi.fn().mockResolvedValue(VIEW),
  }
  vi.mocked(tenantsApi).mockReturnValue(api as unknown as ReturnType<typeof tenantsApi>)
  return api
}

describe('tenant mail settings facade (cr-email-tenant-mail-settings-web)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getTenantMailSettings addresses the tenant', async () => {
    const api = stubApi()
    const s = await getTenantMailSettings(TENANT)
    expect(api.getTenantMailSettings).toHaveBeenCalledWith({ tenantId: TENANT })
    expect(s.passwordSet).toBe(true)
    // The read never carries a password — only passwordSet.
    expect((s as unknown as Record<string, unknown>).password).toBeUndefined()
  })

  it('updateTenantMailSettings wraps the update under tenantMailSettingsUpdate', async () => {
    const api = stubApi()
    await updateTenantMailSettings(TENANT, { host: 'smtp.example.com', port: 587, tls: true, enabled: true, password: 'hunter2' })
    expect(api.updateTenantMailSettings).toHaveBeenCalledWith({
      tenantId: TENANT,
      tenantMailSettingsUpdate: { host: 'smtp.example.com', port: 587, tls: true, enabled: true, password: 'hunter2' },
    })
  })

  it('passes an update with NO password through unchanged (leave-blank-keeps: the key stays absent)', async () => {
    const api = stubApi()
    await updateTenantMailSettings(TENANT, { host: 'smtp.example.com', tls: true, enabled: true })
    const sent = api.updateTenantMailSettings.mock.calls[0][0].tenantMailSettingsUpdate
    expect('password' in sent).toBe(false) // omitted, not null — the backend keeps the stored ciphertext
  })
})
