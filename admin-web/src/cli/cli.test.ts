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
vi.mock('../features/instance/releaseNotices', () => ({ getReleaseNotices: vi.fn(), setReleaseCheck: vi.fn() }))
vi.mock('../features/instance/configSecret', () => ({ getInstanceConfigSecret: vi.fn() }))
vi.mock('../features/instance/store', async (importOriginal) => ({
  // the builders stay real: what `store set` sends is what is asserted on
  ...(await importOriginal<typeof import('../features/instance/store')>()),
  getInstanceStore: vi.fn(),
  setInstanceStore: vi.fn(),
  checkInstanceStore: vi.fn(),
  discardPendingInstanceStore: vi.fn(),
}))
vi.mock('../features/instance/storeReconcile', () => ({
  getStoreReconcile: vi.fn(),
  confirmExclusiveBucket: vi.fn(),
  withdrawExclusiveBucket: vi.fn(),
  pauseReconcile: vi.fn(),
  resumeReconcile: vi.fn(),
}))
vi.mock('../features/instance/capabilities', async (importOriginal) => ({
  // the tree and the text choice stay real: what the commands print is what is asserted on
  ...(await importOriginal<typeof import('../features/instance/capabilities')>()),
  listInstanceCapabilities: vi.fn(),
  setInstanceCapability: vi.fn(),
  setInstanceCapabilityRequirement: vi.fn(),
  clearInstanceCapabilityRequirement: vi.fn(),
}))
vi.mock('../features/instance/externalServices', () => ({
  listExternalServices: vi.fn(), setExternalService: vi.fn(), clearExternalService: vi.fn(),
}))
// The re-exported enums stay real: `moderation rotate` is asserted on the reason it maps `--reason` to.
vi.mock('../features/instance/moderation', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../features/instance/moderation')>()),
  getModeration: vi.fn(),
  appointModerator: vi.fn(),
  removeModerator: vi.fn(),
  rotateModerationKey: vi.fn(),
}))
// The rules stay real (the notice's tick, the preset's changes, the merge base): what is asserted is what they send.
vi.mock('../features/instance/operator', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../features/instance/operator')>()),
  getOperatingMode: vi.fn(),
  getOperator: vi.fn(),
  setOperator: vi.fn(),
  clearOperator: vi.fn(),
}))
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  instanceOperatorApi: vi.fn(),
  instanceGuestWriteRecordApi: vi.fn(),
}))
vi.mock('./adminSession', () => ({ signIn: vi.fn().mockResolvedValue(undefined) }))

import { run } from './cli'
import { signIn } from './adminSession'
import * as tenants from '../features/tenants/tenants'
import { getInstanceRelease } from '../features/instance/release'
import { getReleaseNotices, setReleaseCheck, type InstanceReleaseNotices } from '../features/instance/releaseNotices'
import { getInstanceConfigSecret } from '../features/instance/configSecret'
import { checkInstanceStore, discardPendingInstanceStore, getInstanceStore, setInstanceStore } from '../features/instance/store'
import {
  confirmExclusiveBucket,
  getStoreReconcile,
  pauseReconcile,
  resumeReconcile,
  withdrawExclusiveBucket,
  type StoreReconcile,
} from '../features/instance/storeReconcile'
import type { InstanceStore, InstanceStoreCheck } from '../features/instance/store'
import {
  clearInstanceCapabilityRequirement, listInstanceCapabilities, setInstanceCapability, setInstanceCapabilityRequirement,
} from '../features/instance/capabilities'
import type { InstanceCapability } from '../features/instance/capabilities'
import { appointModerator, getModeration, removeModerator, rotateModerationKey } from '../features/instance/moderation'
import type { InstanceModeration } from '../features/instance/moderation'
import { clearOperator, getOperatingMode, getOperator, setOperator } from '../features/instance/operator'
import type { InstanceOperatingMode, InstanceOperator } from '../features/instance/operator'
import { OPERATING_MODE_NOTICE } from '../i18n/operatingModeNotice'
import { instanceGuestWriteRecordApi, instanceOperatorApi } from '../api/client'
import { stubApi } from '../test/apiMock'
import { httpError } from '../test/httpError'
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

const storeRead: InstanceStore = { bundledAvailable: true, blobsStored: false, setting: null, lastCheck: null, pending: null }
const failedCheck: InstanceStoreCheck = {
  passed: false,
  checkedAt: EXPIRES,
  steps: [
    { step: 'CREDENTIALS', outcome: 'PASSED', detail: null },
    { step: 'BUCKET', outcome: 'FAILED', detail: 'The bucket nope does not exist (HTTP 404).' },
  ],
}

// Invented entry texts, not the catalog's: the CLI must print what the server serves.
function capability(id: string, name: string, off: boolean, effective: boolean): InstanceCapability {
  return {
    id,
    title: { en: `${name} EN`, de: `${name} DE` },
    purpose: { en: `What ${name} is for.`, de: `Wofür ${name} da ist.` },
    whenOff: { en: `Without ${name}, new ones are refused.`, de: `Ohne ${name} wird Neues abgelehnt.` },
    off,
    effective,
    tenantSwitchable: false,
    requirements: [],
  }
}
const noticesRead: InstanceReleaseNotices = {
  releaseLine: true,
  enabled: true,
  source: 'https://mirror.example.com/veyq',
  lastSuccessAt: new Date('2026-10-16T04:00:00.000Z'),
  notices: [{
    id: 'n1', kind: 'UNSCHEDULED_MAJOR', release: '2.0', date: null, daysAhead: null,
    foundAt: new Date('2026-10-16T04:00:00.000Z'), mailedAt: null,
  }],
}
const register: InstanceCapability[] = [
  capability('core.x', 'Ex', false, true),
  capability('core.a', 'Alpha', true, false),
  capability('core.a.b', 'Bravo', true, false),
  capability('core.a.b.c', 'Charlie', false, false),
  capability('core.a.d', 'Delta', false, false),
]

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listInstanceCapabilities).mockResolvedValue(register)
  vi.mocked(setInstanceCapability).mockImplementation(async (id, off) => capability(id, 'Ex', off, !off))
  vi.mocked(getInstanceStore).mockResolvedValue(storeRead)
  vi.mocked(setInstanceStore).mockResolvedValue(storeRead)
  vi.mocked(checkInstanceStore).mockResolvedValue(failedCheck)
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
  vi.mocked(getReleaseNotices).mockResolvedValue(noticesRead)
  vi.mocked(setReleaseCheck).mockImplementation(async (enabled, source) => ({ ...noticesRead, enabled, source }))
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

  it('release prints the running instance release and what the release check found (uc.admin.identify-instance-release, uc.admin.learn-of-new-releases)', async () => {
    const r = await invoke(['release'])
    expect(getInstanceRelease).toHaveBeenCalledOnce()
    const doc = JSON.parse(r.out)
    expect(Object.keys(doc)[0]).toBe('release') // a script that read `.release` before still does
    expect(doc.release).toBe('1.4.0')
    expect(doc.enabled).toBe(true)
    expect(doc.notices).toEqual([expect.objectContaining({ kind: 'UNSCHEDULED_MAJOR', release: '2.0' })])
    expect(r.code).toBe(0)
  })

  it('release check off switches the check off and keeps its source (uc.admin.learn-of-new-releases A1)', async () => {
    const r = await invoke(['release', 'check', 'off'])
    expect(r.code).toBe(0)
    expect(setReleaseCheck).toHaveBeenCalledWith(false, 'https://mirror.example.com/veyq')
    expect(JSON.parse(r.out).enabled).toBe(false)
    expect((await invoke(['release', 'check', 'on'])).code).toBe(0)
    expect(setReleaseCheck).toHaveBeenLastCalledWith(true, 'https://mirror.example.com/veyq')
  })

  it('release check refuses anything but on or off, and switches nothing', async () => {
    const r = await invoke(['release', 'check', 'maybe'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('on or off')
    expect(setReleaseCheck).not.toHaveBeenCalled()
  })

  it('config-secret prints the config-secret state (uc.admin.back-up-and-restore-instance step 11)', async () => {
    const r = await invoke(['config-secret'])
    expect(getInstanceConfigSecret).toHaveBeenCalledOnce()
    expect(JSON.parse(r.out)).toEqual({ state: 'MISMATCH' })
    expect(r.code).toBe(0)
  })
})

describe('veyq-admin — the object store (uc.admin.configure-object-store)', () => {
  it('store show prints the setting and the last check', async () => {
    const r = await invoke(['store', 'show'])
    expect(JSON.parse(r.out)).toEqual(storeRead)
    expect(r.code).toBe(0)
  })

  it('store discard drops the pending setting and prints the store as it now stands', async () => {
    vi.mocked(discardPendingInstanceStore).mockResolvedValue(storeRead)
    const r = await invoke(['store', 'discard'])
    expect(discardPendingInstanceStore).toHaveBeenCalledOnce()
    expect(JSON.parse(r.out)).toEqual(storeRead)
    expect(r.code).toBe(0)
  })

  it('store reconcile prints the reconcile and changes nothing', async () => {
    vi.mocked(getStoreReconcile).mockResolvedValue({ verdict: 'NOT_CONFIRMED' } as unknown as StoreReconcile)
    const r = await invoke(['store', 'reconcile'])
    expect(JSON.parse(r.out)).toEqual({ verdict: 'NOT_CONFIRMED' })
    for (const write of [confirmExclusiveBucket, withdrawExclusiveBucket, pauseReconcile, resumeReconcile]) {
      expect(write).not.toHaveBeenCalled()
    }
    expect(r.code).toBe(0)
  })

  it.each([
    ['--confirm-exclusive-bucket', confirmExclusiveBucket],
    ['--withdraw-exclusive-bucket', withdrawExclusiveBucket],
    ['--pause', pauseReconcile],
    ['--resume', resumeReconcile],
  ])('store reconcile %s reaches the facade the console calls and prints its answer', async (flag, facade) => {
    vi.mocked(facade).mockResolvedValue({ verdict: 'ARMED' } as unknown as StoreReconcile)
    const r = await invoke(['store', 'reconcile', flag])
    expect(facade).toHaveBeenCalledOnce()
    expect(getStoreReconcile).not.toHaveBeenCalled()
    expect(JSON.parse(r.out)).toEqual({ verdict: 'ARMED' })
    expect(r.code).toBe(0)
  })

  it('store reconcile refuses two switches at once and sends nothing', async () => {
    const r = await invoke(['store', 'reconcile', '--pause', '--confirm-exclusive-bucket'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('one switch at most')
    expect(pauseReconcile).not.toHaveBeenCalled()
    expect(confirmExclusiveBucket).not.toHaveBeenCalled()
  })

  it('store set --bundled chooses the bundled store and nothing else', async () => {
    const r = await invoke(['store', 'set', '--bundled'])
    expect(setInstanceStore).toHaveBeenCalledWith({ bundled: true })
    expect(r.code).toBe(0)
  })

  it('store set --bundled refuses any other flag and sends nothing', async () => {
    const r = await invoke(['store', 'set', '--bundled', '--bucket', 'b'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('--bundled takes no other flag')
    expect(setInstanceStore).not.toHaveBeenCalled()
  })

  it('store set builds an S3 store; the secret comes from the environment, never a flag', async () => {
    const r = await invoke(
      ['store', 'set', '--endpoint', 'https://s3.example.com', '--bucket', 'b', '--region', 'eu', '--access-key', 'AK'],
      { ...ENV, VEYQ_STORE_SECRET_KEY: 'sek' },
    )
    expect(r.code).toBe(0)
    expect(setInstanceStore).toHaveBeenCalledWith({
      bundled: false, type: 'S3', endpoint: 'https://s3.example.com', publicEndpoint: null,
      bucket: 'b', region: 'eu', accessKey: 'AK', secretKey: 'sek',
    })
    // The secret is not echoed: the result is the server's read, which never carries one.
    expect(r.out).not.toContain('sek')
  })

  it('store set without the secret in the environment keeps the stored one (the key is omitted, not empty)', async () => {
    await invoke(['store', 'set', '--endpoint', 'https://e', '--bucket', 'b', '--region', 'r', '--access-key', 'AK'])
    expect(vi.mocked(setInstanceStore).mock.calls[0][0]).not.toHaveProperty('secretKey')
  })

  it('store set --type GARAGE carries the admin endpoint and token', async () => {
    await invoke(
      ['store', 'set', '--type', 'garage', '--endpoint', 'http://g:3900', '--bucket', 'b', '--region', 'garage', '--access-key', 'GK',
        '--garage-admin-endpoint', 'http://g:3903'],
      { ...ENV, VEYQ_STORE_GARAGE_ADMIN_TOKEN: 'tok' },
    )
    expect(setInstanceStore).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'GARAGE', garageAdminEndpoint: 'http://g:3903', garageAdminToken: 'tok' }),
    )
  })

  it('store set refuses an incomplete own store and an unknown type before calling anything', async () => {
    const missing = await invoke(['store', 'set', '--bucket', 'b'])
    expect(missing.code).toBe(1)
    expect(missing.err).toContain('--endpoint is required unless --bundled')
    const bad = await invoke(['store', 'set', '--type', 'ceph', '--endpoint', 'e', '--bucket', 'b', '--region', 'r', '--access-key', 'a'])
    expect(bad.code).toBe(1)
    expect(bad.err).toContain('--type must be S3 or GARAGE')
    expect(setInstanceStore).not.toHaveBeenCalled()
  })

  it('store check prints every step and exits 1 when the check failed — a script cannot mistake it for success', async () => {
    const r = await invoke(['store', 'check'])
    expect(JSON.parse(r.out).steps).toHaveLength(2)
    expect(r.out).toContain('does not exist')
    expect(r.code).toBe(1)
  })

  it('store check exits 0 when it passed', async () => {
    vi.mocked(checkInstanceStore).mockResolvedValueOnce({ ...failedCheck, passed: true })
    expect((await invoke(['store', 'check'])).code).toBe(0)
  })
})

describe('veyq-admin — the capability switches (uc.admin.switch-capabilities)', () => {
  /** The line of one entry in `capabilities list`, split into its id, its state token and its title. */
  const lineOf = (out: string, id: string) => {
    const line = out.split('\n').find((l) => l.trim().split(/\s{2,}/)[0] === id)!
    return line.trim().split(/\s{2,}/)
  }

  it('capabilities list prints the tree with each entry’s state, title and purpose', async () => {
    const r = await invoke(['capabilities', 'list'])
    expect(r.code).toBe(0)
    expect(listInstanceCapabilities).toHaveBeenCalledOnce()
    expect(lineOf(r.out, 'core.x')).toEqual(['core.x', 'on', 'Ex EN'])
    expect(lineOf(r.out, 'core.a')).toEqual(['core.a', 'off', 'Alpha EN'])
    expect(lineOf(r.out, 'core.a.b')).toEqual(['core.a.b', 'off', 'Bravo EN'])
    // the NEAREST ancestor that is itself off, by the id `capabilities on` takes
    expect(lineOf(r.out, 'core.a.b.c')).toEqual(['core.a.b.c', 'off through core.a.b', 'Charlie EN'])
    expect(lineOf(r.out, 'core.a.d')).toEqual(['core.a.d', 'off through core.a', 'Delta EN'])
    // the tree: a child is indented under its parent, a grandchild one step further
    const indent = (id: string) => r.out.split('\n').find((l) => l.trim().startsWith(`${id} `))!.search(/\S/)
    expect([indent('core.a'), indent('core.a.b'), indent('core.a.b.c')]).toEqual([0, 2, 4])
    expect(r.out).toContain('What Charlie is for.')
  })

  it('takes the locale from LC_ALL, then LANG — de* is German, anything else English', async () => {
    expect((await invoke(['capabilities', 'list'], { ...ENV, LANG: 'de_DE.UTF-8' })).out).toContain('Wofür Ex da ist.')
    expect((await invoke(['capabilities', 'list'], { ...ENV, LC_ALL: 'de_AT.UTF-8', LANG: 'en_US.UTF-8' })).out).toContain('Ex DE')
    // a set LC_ALL wins even when it is not German
    expect((await invoke(['capabilities', 'list'], { ...ENV, LC_ALL: 'C', LANG: 'de_DE.UTF-8' })).out).toContain('Ex EN')
    expect((await invoke(['capabilities', 'list'], ENV)).out).toContain('Ex EN')
  })

  it('--lang overrides the locale either way', async () => {
    expect((await invoke(['capabilities', 'list', '--lang', 'en'], { ...ENV, LC_ALL: 'de_DE.UTF-8' })).out).toContain('Ex EN')
    expect((await invoke(['capabilities', 'list', '--lang', 'de'], { ...ENV, LANG: 'en_US.UTF-8' })).out).toContain('Ex DE')
  })

  it('--lang refuses a language the catalog does not write, before signing in', async () => {
    const r = await invoke(['capabilities', 'list', '--lang', 'fr'])
    expect(r.code).toBe(1)
    expect(r.err).toContain("--lang must be one of en, de (got 'fr')")
    expect(signIn).not.toHaveBeenCalled()
  })

  it('capabilities off <id> switches it off and prints the served effect-when-off in the locale', async () => {
    const r = await invoke(['capabilities', 'off', 'core.x'], { ...ENV, LANG: 'de_DE.UTF-8' })
    expect(r.code).toBe(0)
    expect(setInstanceCapability).toHaveBeenCalledWith('core.x', true)
    expect(r.out.split('\n')[0]).toBe('Ohne Ex wird Neues abgelehnt.')
    expect(r.out).toContain('core.x  off  Ex DE')
    const en = await invoke(['capabilities', 'off', '--lang', 'en', 'core.x'])
    expect(en.out.split('\n')[0]).toBe('Without Ex, new ones are refused.')
  })

  it('capabilities on <id> switches it back on and prints no effect text', async () => {
    const r = await invoke(['capabilities', 'on', 'core.x'])
    expect(r.code).toBe(0)
    expect(setInstanceCapability).toHaveBeenCalledWith('core.x', false)
    expect(r.out).toBe('core.x  on  Ex EN')
  })

  it('capabilities on under a parent that is still off says the entry stays off through it', async () => {
    vi.mocked(setInstanceCapability).mockResolvedValueOnce(capability('core.a.b.c', 'Charlie', false, false))
    const r = await invoke(['capabilities', 'on', 'core.a.b.c'])
    expect(r.out).toBe('core.a.b.c  off through a parent  Charlie EN')
  })

  it('an unknown id exits 1 with the backend’s refusal and prints no effect text', async () => {
    vi.mocked(setInstanceCapability).mockRejectedValueOnce(httpError(404, 'capability_not_found'))
    const r = await invoke(['capabilities', 'off', 'core.nope'])
    expect(r.code).toBe(1)
    expect(r.err).toBe('veyq-admin: request failed: HTTP 404 capability_not_found')
    expect(r.out).toBe('')
  })

  it('off and on need exactly one id, refused before signing in', async () => {
    const none = await invoke(['capabilities', 'off'])
    expect(none.code).toBe(1)
    expect(none.err).toContain('<id> is required')
    const two = await invoke(['capabilities', 'on', 'core.x', 'core.a'])
    expect(two.code).toBe(1)
    expect(two.err).toContain('exactly one <id>')
    expect(signIn).not.toHaveBeenCalled()
    expect(setInstanceCapability).not.toHaveBeenCalled()
  })

  it('capabilities require sets a requirement, prints what it does to what exists, and off clears it', async () => {
    const req = (value: number | null) => ({
      ...capability('core.x', 'Ex', false, true),
      requirements: [{
        name: 'linkMaxDays', kind: 'days' as const, value,
        title: { en: 'Links end EN', de: 'Links enden DE' }, whenSet: { en: 'Old links stop EN', de: 'Alte Links stoppen DE' },
      }],
    })
    vi.mocked(setInstanceCapabilityRequirement).mockResolvedValueOnce(req(30) as InstanceCapability)
    const r = await invoke(['capabilities', 'require', 'core.x', '--requirement', 'linkMaxDays', '--value', '30'])
    expect(r.code).toBe(0)
    expect(setInstanceCapabilityRequirement).toHaveBeenCalledWith('core.x', 'linkMaxDays', 30)
    expect(r.out.split('\n')[0]).toBe('Old links stop EN')
    expect(r.out).toContain('core.x  requirement linkMaxDays: 30 days  Links end EN')

    vi.mocked(clearInstanceCapabilityRequirement).mockResolvedValueOnce(req(null) as InstanceCapability)
    const off = await invoke(['capabilities', 'require', 'core.x', '--requirement', 'linkMaxDays', '--value', 'off'])
    expect(clearInstanceCapabilityRequirement).toHaveBeenCalledWith('core.x', 'linkMaxDays')
    expect(off.out).toBe('core.x  requirement linkMaxDays: not set  Links end EN')

    vi.mocked(listInstanceCapabilities).mockResolvedValueOnce([req(14) as InstanceCapability])
    expect((await invoke(['capabilities', 'list'])).out).toContain('requirement linkMaxDays: 14 days  Links end EN')
  })

  it('services list / set / unset print each service with its address or not configured', async () => {
    const svc = (url: string | null, attribution: string | null = null) => ({
      id: 'memories.map', title: { en: 'Maps', de: 'Karten' }, url, attribution, takesAttribution: true,
    })
    const { listExternalServices, setExternalService, clearExternalService } = await import('../features/instance/externalServices')
    vi.mocked(listExternalServices).mockResolvedValueOnce([svc('https://t.example/{z}/{x}/{y}.png', '© T'), { ...svc(null), id: 'memories.places', takesAttribution: false }])
    expect((await invoke(['services', 'list'])).out).toBe('memories.map  https://t.example/{z}/{x}/{y}.png  (© T)\nmemories.places  not configured')
    vi.mocked(setExternalService).mockResolvedValueOnce(svc('https://u.example/{z}/{x}/{y}.png'))
    const set = await invoke(['services', 'set', 'memories.map', '--url', 'https://u.example/{z}/{x}/{y}.png'])
    expect(setExternalService).toHaveBeenCalledWith('memories.map', 'https://u.example/{z}/{x}/{y}.png', null)
    expect(set.out).toBe('memories.map  https://u.example/{z}/{x}/{y}.png')
    vi.mocked(clearExternalService).mockResolvedValueOnce(svc(null))
    expect((await invoke(['services', 'unset', 'memories.map'])).out).toBe('memories.map  not configured')
    expect(clearExternalService).toHaveBeenCalledWith('memories.map')
  })

  it('list takes no id: a stray word is still an unknown command', async () => {
    const r = await invoke(['capabilities', 'list', 'core.x'])
    expect(r.code).toBe(1)
    expect(r.err).toContain("unknown command 'capabilities list core.x'")
  })
})

describe('veyq-admin — moderators and the moderation key (uc.moderation.appoint-moderators)', () => {
  const roster = (over: Partial<InstanceModeration> = {}): InstanceModeration => ({
    enabled: true,
    currentEpoch: 2,
    rotationDueSince: null,
    serverOwner: { accountId: 'owner', identityPublicKey: 'AAAA' },
    moderators: [
      {
        accountId: 'owner', address: 'owner@example.com', state: 'ACCEPTED', appointedAt: EXPIRES, acceptedAt: EXPIRES,
        identityPublicKey: 'AAAA', holdsCurrentEpoch: true, addressConfirmed: true,
      },
      {
        accountId: 'deputy-id', address: 'Deputy@Example.com', state: 'PENDING', appointedAt: EXPIRES, acceptedAt: null,
        identityPublicKey: null, holdsCurrentEpoch: false, addressConfirmed: false,
      },
    ],
    ...over,
  })

  it('moderator list prints the roster compactly — address, state, holds key — and no identity key', async () => {
    vi.mocked(getModeration).mockResolvedValueOnce(roster())
    const r = await invoke(['moderator', 'list'])
    expect(r.code).toBe(0)
    expect(JSON.parse(r.out)).toEqual({
      enabled: true,
      currentEpoch: 2,
      rotationDueSince: null,
      moderators: [
        { address: 'owner@example.com', state: 'ACCEPTED', holdsKey: true },
        { address: 'Deputy@Example.com', state: 'PENDING', holdsKey: false },
      ],
    })
    expect(r.out).not.toContain('AAAA')
  })

  it('moderator appoint passes the address to the facade, and a refusal exits 1 with its code', async () => {
    vi.mocked(appointModerator).mockResolvedValueOnce(roster())
    expect((await invoke(['moderator', 'appoint', '--address', 'deputy@example.com'])).code).toBe(0)
    expect(appointModerator).toHaveBeenCalledWith('deputy@example.com')
    vi.mocked(appointModerator).mockRejectedValueOnce(httpError(404, 'account_not_found'))
    const r = await invoke(['moderator', 'appoint', '--address', 'nobody@example.com'])
    expect(r.code).toBe(1)
    expect(r.err).toBe('veyq-admin: request failed: HTTP 404 account_not_found')
  })

  it('moderator remove resolves the account from the roster by address, case-insensitively, and removes it', async () => {
    vi.mocked(getModeration).mockResolvedValueOnce(roster())
    vi.mocked(removeModerator).mockResolvedValueOnce(roster({ moderators: roster().moderators.slice(0, 1) }))
    const r = await invoke(['moderator', 'remove', '--address', 'deputy@example.COM'])
    expect(r.code).toBe(0)
    expect(removeModerator).toHaveBeenCalledWith('deputy-id')
  })

  it('moderator remove refuses an address not on the roster, and removes nothing', async () => {
    vi.mocked(getModeration).mockResolvedValueOnce(roster())
    const r = await invoke(['moderator', 'remove', '--address', 'stranger@example.com'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('stranger@example.com is not on the moderator roster')
    expect(removeModerator).not.toHaveBeenCalled()
  })

  it('a removal whose rotation the server refuses exits 1 with that refusal', async () => {
    vi.mocked(getModeration).mockResolvedValueOnce(roster())
    vi.mocked(removeModerator).mockRejectedValueOnce(httpError(409, 'moderation_grants_incomplete'))
    const r = await invoke(['moderator', 'remove', '--address', 'owner@example.com'])
    expect(r.code).toBe(1)
    expect(r.err).toBe('veyq-admin: request failed: HTTP 409 moderation_grants_incomplete')
  })

  it('moderation rotate maps --reason to the logged category', async () => {
    for (const [typed, sent] of [['by-hand', 'BY_HAND'], ['compromise', 'COMPROMISE'], ['identity-rotation', 'IDENTITY_ROTATION'], ['removal', 'REMOVAL']]) {
      vi.mocked(rotateModerationKey).mockResolvedValueOnce(roster({ currentEpoch: 3 }))
      const r = await invoke(['moderation', 'rotate', '--reason', typed])
      expect(r.code).toBe(0)
      expect(rotateModerationKey).toHaveBeenLastCalledWith(sent)
      expect(JSON.parse(r.out).currentEpoch).toBe(3)
    }
  })

  it('moderation rotate refuses an unknown or missing reason before signing in, and mints nothing', async () => {
    const unknown = await invoke(['moderation', 'rotate', '--reason', 'whim'])
    expect(unknown.code).toBe(1)
    expect(unknown.err).toContain('--reason must be one of by-hand, compromise, identity-rotation, removal')
    const missing = await invoke(['moderation', 'rotate'])
    expect(missing.code).toBe(1)
    expect(missing.err).toContain('--reason is required')
    expect(signIn).not.toHaveBeenCalled()
    expect(rotateModerationKey).not.toHaveBeenCalled()
  })
})

describe('veyq-admin — the operating mode and the operator details (uc.admin.operator-details A4)', () => {
  const guestRecord = {
    id: 'core.public-shares.guest-write-record', title: { en: 'Guest-write record', de: 'Gast-Schreibprotokoll' },
    onInPrivate: false, onInOrganisation: true, off: false,
  }
  const modeRead = (over: Partial<InstanceOperatingMode> = {}): InstanceOperatingMode =>
    ({ mode: 'ORGANISATION', chosen: true, preset: [guestRecord], ...over })
  const op = (over: Partial<InstanceOperator> = {}): InstanceOperator => ({
    operatingMode: 'ORGANISATION', contactEmail: 'contact@example.org', contactLanguages: ['de'], statementEmail: null,
    notificationEmail: 'alerts@example.org', notificationConfirmation: 'CONFIRMED', notificationConfirmedAt: EXPIRES,
    instanceMailReady: true, providerName: 'Example e. V.', providerAddress: 'Musterstraße 1', providerEmail: null,
    providerRepresentative: null, supervisoryAuthority: null, ...over,
  })

  it('mode show prints the mode, the notice in the locale, and what the other mode would switch', async () => {
    vi.mocked(getOperatingMode).mockResolvedValueOnce(modeRead())
    const r = await invoke(['mode', 'show'], { ...ENV, LANG: 'de_DE.UTF-8' })
    expect(r.code).toBe(0)
    expect(r.out.split('\n')[0]).toBe('mode organisation')
    expect(r.out).toContain(OPERATING_MODE_NOTICE.de.exemption)
    expect(r.out).toContain('mode set private would switch:\n  core.public-shares.guest-write-record  off  Gast-Schreibprotokoll')
  })

  it('mode set private without --accept-notice prints the notice, refuses and sets nothing', async () => {
    const operatorApi = stubApi(instanceOperatorApi, { setInstanceOperatingMode: modeRead() })
    const r = await invoke(['mode', 'set', 'private'])
    expect(r.code).toBe(1)
    expect(r.err).toContain(OPERATING_MODE_NOTICE.en.leavesOff)
    expect(r.err).toContain('--accept-notice')
    expect(operatorApi.setInstanceOperatingMode).not.toHaveBeenCalled()
  })

  it('mode set private --accept-notice sets it, and prints the notice again with the result', async () => {
    const operatorApi = stubApi(instanceOperatorApi, { setInstanceOperatingMode: modeRead({ mode: 'PRIVATE', preset: [{ ...guestRecord, off: true }] }) })
    const r = await invoke(['mode', 'set', 'private', '--accept-notice'])
    expect(r.code).toBe(0)
    expect(operatorApi.setInstanceOperatingMode).toHaveBeenCalledWith({ instanceOperatingModeUpdate: { mode: 'PRIVATE' } })
    expect(r.out.split('\n')[0]).toBe('mode private')
    expect(r.out).toContain(OPERATING_MODE_NOTICE.en.intro)
  })

  it('mode set organisation needs no flag; anything but the two modes is refused', async () => {
    const operatorApi = stubApi(instanceOperatorApi, { setInstanceOperatingMode: modeRead() })
    expect((await invoke(['mode', 'set', 'organisation'])).code).toBe(0)
    expect(operatorApi.setInstanceOperatingMode).toHaveBeenCalledWith({ instanceOperatingModeUpdate: { mode: 'ORGANISATION' } })
    const r = await invoke(['mode', 'set', 'household'])
    expect(r.code).toBe(1)
    expect(r.err).toContain('private or organisation')
    expect(operatorApi.setInstanceOperatingMode).toHaveBeenCalledOnce()
  })

  it('operator show prints the values as JSON a script can read', async () => {
    vi.mocked(getOperator).mockResolvedValueOnce(op())
    const r = await invoke(['operator', 'show'])
    expect(r.code).toBe(0)
    expect(JSON.parse(r.out)).toMatchObject({ contactEmail: 'contact@example.org', notificationConfirmation: 'CONFIRMED' })
  })

  it('operator set changes the given values, keeps the rest, and none clears one', async () => {
    vi.mocked(getOperator).mockResolvedValueOnce(op())
    vi.mocked(setOperator).mockResolvedValueOnce(op())
    const r = await invoke(['operator', 'set', '--provider-email', 'info@example.org', '--contact-languages', 'de, en', '--contact-email', 'none'])
    expect(r.code).toBe(0)
    expect(setOperator).toHaveBeenCalledWith({
      contactEmail: null, contactLanguages: ['de', 'en'], statementEmail: null, notificationEmail: 'alerts@example.org',
      providerName: 'Example e. V.', providerAddress: 'Musterstraße 1', providerEmail: 'info@example.org',
      providerRepresentative: null, supervisoryAuthority: null,
    })
  })

  it('operator set with no value is refused and sends nothing; operator clear clears', async () => {
    const r = await invoke(['operator', 'set'])
    expect(r.code).toBe(1)
    expect(setOperator).not.toHaveBeenCalled()
    vi.mocked(clearOperator).mockResolvedValueOnce(op({ contactEmail: null }))
    expect((await invoke(['operator', 'clear'])).code).toBe(0)
    expect(clearOperator).toHaveBeenCalledOnce()
  })
})

describe('veyq-admin — usage and refusals', () => {
  it('--help lists every command and exits 0 without signing in', async () => {
    const r = await invoke(['--help'])
    expect(r.code).toBe(0)
    for (const c of ['tenant create', 'tenant change-domain', 'tenant reactivate', 'tenant quota', 'release', 'config-secret', 'store show', 'store set', 'store check', 'store discard', 'store reconcile', 'capabilities list', 'capabilities off <id>', 'capabilities on <id>', 'capabilities require <id>', 'services list', 'services set <id>', 'services unset <id>', 'moderator list', 'moderator appoint --address', 'moderator remove --address', 'moderation rotate --reason', 'mode show', 'mode set <private|organisation>', 'operator show', 'operator set', 'operator clear']) {
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

describe('veyq-admin guest-write-record (en.moderation.guest-write-record)', () => {
  it('show prints the days and whether they are the default', async () => {
    stubApi(instanceGuestWriteRecordApi, { getInstanceGuestWriteRecord: { retentionDays: 7, chosen: false } })
    const r = await invoke(['guest-write-record', 'show'])
    expect(r.code).toBe(0)
    expect(r.out).toBe('guest-write-record 7 days (the default)')
  })

  it('set sends whole days and says a reason is owed above seven', async () => {
    const api = stubApi(instanceGuestWriteRecordApi, { setInstanceGuestWriteRecord: { retentionDays: 14, chosen: true } })
    const r = await invoke(['guest-write-record', 'set', '14'])
    expect(r.code).toBe(0)
    expect(api.setInstanceGuestWriteRecord).toHaveBeenCalledWith({ instanceGuestWriteRecordUpdate: { retentionDays: 14 } })
    expect(r.out).toContain('guest-write-record 14 days')
    expect(r.out).toContain('record of processing')
  })

  it.each(['0', '31', '7.5', 'week'])('set refuses %j and sends nothing', async (days) => {
    const api = stubApi(instanceGuestWriteRecordApi, { setInstanceGuestWriteRecord: { retentionDays: 7, chosen: true } })
    const r = await invoke(['guest-write-record', 'set', days])
    expect(r.code).toBe(1)
    expect(r.err).toContain('from 1 to 30')
    expect(api.setInstanceGuestWriteRecord).not.toHaveBeenCalled()
  })
})
