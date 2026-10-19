// @vitest-environment happy-dom
// The guest-write record's first-layer notice on the share page (ADR 0093 § 10, en.moderation.guest-write-record,
// uc.sharing.share-contributor A5): before a READ_WRITE guest's first write — in the shell's naming dialog — and beside
// the line that says whose session this device holds, the page says what is kept for the N the resolve served, with the
// right to object as a sentence of its own. A READ link is served no N and says nothing (nothing is recorded); a
// READ_WRITE link while the instance's switch is off is served none either and says nothing. Each absence beside its
// control: the same READ_WRITE link served an N. Guest mode: no authenticated client is called.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memory/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/depot/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms, type PublicShareHead } from '../core/sharing/resolvePublicShare'
import { FOLDER_KIND } from '../core/node/kind'
import { saveGuestSession } from '../core/sharing/guestIdentity'
import { ShareRole, type PublicShare } from '../api/generated/sharing'
import { publicShareMediaApi } from '../features/memory/api'
import { publicShareFilesApi } from '../features/depot/api'
import { stubApi } from '../test/apiMock'
import { realI18n, t } from '../test/i18n'
import { chooseNew } from '../test/newMenu'
import { authenticatedCalls } from '../test/guestMode'
import { OPERATOR_SECTION } from '../test/models'

const SCOPE_ID = '99999999-9999-4999-8999-999999999999'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_n'
const ROOT = '6e5a0000-0000-4000-8000-000000000700'
const K_SHARE = v1.randomBytes(32)
const ROOT_KEY = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`

function head(role: ShareRole, guestWriteRecordDays: number | null): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, ROOT_KEY)),
      nodes: [{ id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 }],
      guestWriteRecordDays,
    } as PublicShare,
  }
}

const stubs = { Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' } }

let router: Router
async function mountPage(role: ShareRole, days: number | null) {
  vi.mocked(resolvePublicShare).mockResolvedValue(head(role, days))
  const w = mount(ShareView, { global: { plugins: [router, realI18n(), PrimeVue], stubs, directives: { tooltip: {} } } })
  await flushPromises()
  await vi.waitFor(() => expect(w.find('.veyq-node-header').exists()).toBe(true))
  return w
}
type Page = Awaited<ReturnType<typeof mountPage>>
const notices = (w: Page) => w.findAll('[data-testid="guest-write-record-notice"]')
/** The first write — the folder level's create — asks the guest's name in the shell's dialog. */
async function firstWrite(w: Page) {
  await chooseNew(w, t('node.folderCreate'))
  await flushPromises()
}

beforeEach(async () => {
  localStorage.clear()
  authenticatedCalls.length = 0
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
  stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [], more: false } })
  stubApi(publicShareFilesApi, { listShareFiles: { items: [] } })
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:shareId', component: ShareView }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

describe("the guest-write record's notice on the share page", () => {
  it('a READ_WRITE guest is told before its first write, with the N served, and the objection as a sentence of its own', async () => {
    const w = await mountPage(ShareRole.ReadWrite, 9)
    expect(notices(w), 'nobody named yet: no session line, so no notice outside the dialog').toHaveLength(0)
    await firstWrite(w)
    const notice = w.find('.dialog-stub [data-testid="guest-write-record-notice"]')
    expect(notice.find('.guest-write-record-kept').text()).toBe(t('share.guest.recordKept', { days: 9 }, 9))
    expect(notice.text()).toContain('9 days')
    expect(notice.find('.guest-write-record-objection').text()).toBe(t('share.guest.recordObjection'))
    expect(authenticatedCalls).toEqual([])
  })

  it('a returning READ_WRITE guest sees it beside the line that says who it is', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0', displayName: 'Anna' })
    const w = await mountPage(ShareRole.ReadWrite, 1)
    expect(notices(w)).toHaveLength(1)
    expect(notices(w)[0].text()).toContain(t('share.guest.recordKept', { days: 1 }, 1))
    expect(notices(w)[0].text()).toContain('one day')
  })

  it('a READ_WRITE link while the switch is off is served no N and says nothing, before or at the first write', async () => {
    const w = await mountPage(ShareRole.ReadWrite, null)
    await firstWrite(w)
    expect(w.find('.dialog-stub .header').text(), 'the dialog is there: the absence is the notice, not the dialog').toBe(t('share.guest.nameTitle'))
    expect(notices(w)).toHaveLength(0)
  })

  it('a READ link says nothing: nothing is recorded', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0', displayName: 'Anna' })
    const w = await mountPage(ShareRole.Read, null)
    expect(notices(w)).toHaveLength(0)
    expect(w.find('.guest-naming-identity').exists(), 'a READ link shows no session line either').toBe(false)
  })
})
