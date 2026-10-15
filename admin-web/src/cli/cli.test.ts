// @vitest-environment node
// Deliberately the `node` environment, not happy-dom: the CLI's whole claim is that it runs where there is no
// browser, so a test that quietly hands it a DOM would prove the wrong thing.
import { describe, it, expect, vi, beforeEach } from 'vitest'

// The facades stand in for the network, exactly as the view tests mock them — the assertion is that the CLI
// reaches the SAME operation with the SAME arguments the UI does, which is the entire point of the CLI being
// a Node program (ADR 0065: the same generated client, not a second implementation).
// `parseQuotaGib` stays real: the GiB → bytes conversion is what `tenant quota` is asserted on.
vi.mock('../features/tenants/tenants', async (importOriginal) => ({
  parseQuotaGib: (await importOriginal<typeof import('../features/tenants/tenants')>()).parseQuotaGib,
  setTenantStorageQuota: vi.fn(),
  createTenant: vi.fn(),
  listTenants: vi.fn(),
  resendInvite: vi.fn(),
  reassignInvite: vi.fn(),
  changeTenantDomain: vi.fn(),
  revokeTenant: vi.fn(),
  suspendTenant: vi.fn(),
  reactivateTenant: vi.fn(),
}))
vi.mock('../features/instance/release', () => ({ getInstanceRelease: vi.fn() }))
vi.mock('../features/instance/configSecret', () => ({ getInstanceConfigSecret: vi.fn() }))
vi.mock('./adminSession', () => ({ signIn: vi.fn().mockResolvedValue(undefined) }))

import { run } from './cli'
import { signIn } from './adminSession'
import * as tenants from '../features/tenants/tenants'
import { getInstanceRelease } from '../features/instance/release'
import { getInstanceConfigSecret } from '../features/instance/configSecret'
import type { TenantCreated, TenantInviteState, TenantSummary } from '../features/tenants/tenants'

const ENV = { VEYQ_ADMIN_EMAIL: 'owner@example.com', VEYQ_ADMIN_PASSWORD: 'pw' }

function capture() {
  const out: string[] = []
  const err: string[] = []
  return { out, err, sinks: [(s: string) => out.push(s), (s: string) => err.push(s)] as const }
}

/** Run one invocation and hand back its exit code plus everything it printed. */
async function invoke(argv: string[], env: Record<string, string | undefined> = ENV) {
  const c = capture()
  const code = await run(argv, env, c.sinks[0], c.sinks[1])
  return { code, out: c.out.join('\n'), err: c.err.join('\n') }
}

const EXPIRES = new Date('2026-10-10T12:00:00.000Z')

const created: TenantCreated = {
  tenantId: 't1', status: 'PENDING', inviteExpiresAt: EXPIRES, bootstrapSecret: 'boot-1', delivery: 'FALLBACK_COPYLINK',
}
const row: TenantSummary = {
  tenantId: 't1', name: 'Fam', domain: 'f.example.com', ownerEmail: 'k@example.com', status: 'PENDING',
  storageUsedBytes: 5, storageQuotaBytes: null,
}
const invite = (ownerEmail: string): TenantInviteState => ({
  ownerEmail, inviteStatus: 'ACTIVE', inviteExpiresAt: EXPIRES, bootstrapSecret: 'boot-2', delivery: 'FALLBACK_COPYLINK',
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(tenants.setTenantStorageQuota).mockResolvedValue(undefined)
  vi.mocked(tenants.createTenant).mockResolvedValue(created)
  vi.mocked(tenants.listTenants).mockResolvedValue([row])
  vi.mocked(tenants.resendInvite).mockResolvedValue(invite('k@example.com'))
  vi.mocked(tenants.reassignInvite).mockImplementation(async (_tenantId, ownerEmail) => invite(ownerEmail))
  vi.mocked(tenants.changeTenantDomain).mockResolvedValue(undefined)
  vi.mocked(tenants.revokeTenant).mockResolvedValue(undefined)
  vi.mocked(tenants.suspendTenant).mockResolvedValue(undefined)
  vi.mocked(tenants.reactivateTenant).mockResolvedValue(undefined)
  vi.mocked(getInstanceRelease).mockResolvedValue({ release: '1.4.0' })
  vi.mocked(getInstanceConfigSecret).mockResolvedValue({ state: 'MISMATCH' })
})

describe('veyq-admin — command dispatch (uc.admin.create-tenant, uc.admin.suspend-tenant)', () => {
  it('tenant create passes name, domain and owner email through to the facade', async () => {
    const r = await invoke(['tenant', 'create', '--name', 'Fam', '--domain', 'f.example.com', '--owner-email', 'k@example.com'])
    expect(tenants.createTenant).toHaveBeenCalledWith('Fam', 'f.example.com', 'k@example.com')
    expect(r.code).toBe(0)
    // The whole TenantCreated reaches stdout — the bootstrap secret included, since a PENDING tenant has no mail
    // channel yet and the copy-link is the only handoff (ADR 0055 §3). Dates serialise as ISO strings.
    expect(JSON.parse(r.out)).toEqual({ ...created, inviteExpiresAt: EXPIRES.toISOString() })
  })

  it('tenant list prints the tenants as JSON a script can read', async () => {
    const r = await invoke(['tenant', 'list'])
    expect(tenants.listTenants).toHaveBeenCalledOnce()
    // both storage figures ride along, in bytes; null is "no quota" (uc.admin.tenant-storage-quota)
    expect(JSON.parse(r.out)).toEqual([row])
    expect(r.code).toBe(0)
  })

  it('tenant resend and reassign carry the invite arguments (the copy-link handoff, from a script)', async () => {
    const resent = await invoke(['tenant', 'resend', '--tenant-id', 't1'])
    expect(resent.code).toBe(0)
    expect(JSON.parse(resent.out)).toMatchObject({ ownerEmail: 'k@example.com', inviteStatus: 'ACTIVE' })
    expect(tenants.resendInvite).toHaveBeenCalledWith('t1')
    const r = await invoke(['tenant', 'reassign', '--tenant-id', 't1', '--owner-email', 'new@example.com'])
    expect(tenants.reassignInvite).toHaveBeenCalledWith('t1', 'new@example.com')
    // The re-minted invite is printed whole: a script copies the fresh bootstrap secret out of it.
    expect(JSON.parse(r.out)).toEqual({
      ownerEmail: 'new@example.com', inviteStatus: 'ACTIVE', inviteExpiresAt: EXPIRES.toISOString(),
      bootstrapSecret: 'boot-2', delivery: 'FALLBACK_COPYLINK',
    })
  })

  it('tenant change-domain moves the tenant and reports the bodiless 204 as an explicit success', async () => {
    const r = await invoke(['tenant', 'change-domain', '--tenant-id', 't1', '--domain', 'new.example.com'])
    expect(tenants.changeTenantDomain).toHaveBeenCalledWith('t1', 'new.example.com')
    // A 204 resolves to `undefined`; printing nothing would be indistinguishable from a silent failure.
    expect(JSON.parse(r.out)).toEqual({ ok: true })
    expect(r.code).toBe(0)
  })

  it('tenant revoke, suspend and reactivate each reach their own operation', async () => {
    await invoke(['tenant', 'revoke', '--tenant-id', 't1'])
    await invoke(['tenant', 'suspend', '--tenant-id', 't2'])
    await invoke(['tenant', 'reactivate', '--tenant-id', 't2'])
    expect(tenants.revokeTenant).toHaveBeenCalledWith('t1')
    expect(tenants.suspendTenant).toHaveBeenCalledWith('t2')
    expect(tenants.reactivateTenant).toHaveBeenCalledWith('t2')
  })

  it('tenant quota sets the quota typed in GiB as bytes, and none clears it (uc.admin.tenant-storage-quota)', async () => {
    const r = await invoke(['tenant', 'quota', '--tenant-id', 't1', '--gib', '2.5'])
    expect(tenants.setTenantStorageQuota).toHaveBeenCalledWith('t1', 2_684_354_560)
    expect(JSON.parse(r.out)).toEqual({ ok: true })
    expect(r.code).toBe(0)
    expect((await invoke(['tenant', 'quota', '--tenant-id', 't1', '--gib', 'none'])).code).toBe(0)
    expect(tenants.setTenantStorageQuota).toHaveBeenLastCalledWith('t1', null)
  })

  it('tenant quota refuses a value that is not a number of GiB or none, and calls nothing', async () => {
    const r = await invoke(['tenant', 'quota', '--tenant-id', 't1', '--gib', 'lots'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('--gib must be a number of GiB')
    expect(tenants.setTenantStorageQuota).not.toHaveBeenCalled()
  })

  it('release prints the running instance release (uc.admin.identify-instance-release)', async () => {
    const r = await invoke(['release'])
    expect(getInstanceRelease).toHaveBeenCalledOnce()
    expect(JSON.parse(r.out)).toEqual({ release: '1.4.0' })
    expect(r.code).toBe(0)
  })

  it('config-secret prints the config-secret state (uc.admin.back-up-and-restore-instance step 11)', async () => {
    const r = await invoke(['config-secret'])
    expect(getInstanceConfigSecret).toHaveBeenCalledOnce()
    expect(JSON.parse(r.out)).toEqual({ state: 'MISMATCH' })
    expect(r.code).toBe(0)
  })
})

describe('veyq-admin — usage and refusals', () => {
  it('--help lists every command and exits 0 without signing in', async () => {
    const r = await invoke(['--help'])
    expect(r.code).toBe(0)
    for (const c of ['tenant create', 'tenant change-domain', 'tenant reactivate', 'tenant quota', 'release', 'config-secret']) {
      expect(r.out).toContain(c)
    }
    // Help must not cost an Argon2id derivation, nor require a credential to be present at all.
    expect(signIn).not.toHaveBeenCalled()
  })

  it('no arguments prints usage and exits non-zero — nothing was asked, so nothing succeeded', async () => {
    const r = await invoke([])
    expect(r.code).toBe(1)
    expect(r.out).toContain('tenant list')
  })

  it('an unknown command names what it did not understand and exits 1', async () => {
    const r = await invoke(['tenant', 'delete'])
    expect(r.code).toBe(1)
    expect(r.err).toContain("unknown command 'tenant delete'")
    expect(signIn).not.toHaveBeenCalled()
  })

  it('a missing required flag is refused BEFORE signing in, so a typo costs no credential use', async () => {
    const r = await invoke(['tenant', 'create', '--name', 'Fam', '--domain', 'f.example.com'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('--owner-email is required')
    expect(signIn).not.toHaveBeenCalled()
    expect(tenants.createTenant).not.toHaveBeenCalled()
  })

  it('a flag the command does not take is rejected, not silently ignored', async () => {
    const r = await invoke(['release', '--tenant-id', 't1'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('--tenant-id')
    expect(getInstanceRelease).not.toHaveBeenCalled()
  })

  it('a rejected sign-in fails loudly and non-enumerably — the status and code, never a stack trace', async () => {
    vi.mocked(signIn).mockRejectedValueOnce({
      response: new Response(JSON.stringify({ code: 'authentication_failed' }), { status: 401 }),
    })
    const r = await invoke(['tenant', 'list'], { ...ENV, VEYQ_ADMIN_PASSWORD: 'wrong' })
    expect(r.code).toBe(1)
    expect(r.err).toBe('veyq-admin: request failed: HTTP 401 authentication_failed')
    expect(tenants.listTenants).not.toHaveBeenCalled()
  })

  it('an unreachable backend says so rather than reporting a bare HTTP 0', async () => {
    vi.mocked(signIn).mockRejectedValueOnce(new TypeError('fetch failed'))
    const r = await invoke(['tenant', 'list'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('could not be reached')
  })

  it('a failing operation exits 1 with the server code, after a successful sign-in', async () => {
    vi.mocked(tenants.suspendTenant).mockRejectedValueOnce({
      response: new Response(JSON.stringify({ code: 'tenant_not_active' }), { status: 409 }),
    })
    const r = await invoke(['tenant', 'suspend', '--tenant-id', 't1'])
    expect(r.code).toBe(1)
    expect(r.err).toBe('veyq-admin: request failed: HTTP 409 tenant_not_active')
  })
})
