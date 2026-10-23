// @vitest-environment happy-dom
// A guest reports from the share page (uc.moderation.report) — guest mode: every authenticated client throws
// (`test/guestMode.ts`), and the report goes through the token-less clients alone. A READ guest selects a node of the
// link and reports it with core's dialog (the member's views, rights removed — share view = member view); an upload
// link's guest reports the link itself from the page's footer. Before the instance publishes a key there is nothing to
// report to, and no action is shown.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.unmock('../core/report/moderationKey')
vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memories/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/depot/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
const route = vi.hoisted(() => ({ params: { shareId: 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_3' }, hash: '', query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn(), resolve: () => ({ href: '/s/stub' }) }) }))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})

import ShareView from './ShareView.vue'
import ReportDialog from '../core/report/ReportDialog.vue'
import { resetReporting } from '../core/report/report'
import { resolvePublicShare, fetchShareTerms, type PublicShareHead } from '../core/sharing/resolvePublicShare'
import { FOLDER_KIND } from '../core/node/kind'
import { MEMORY_KIND } from '../features/memories/kinds'
import { sealName, wrapContentKey } from '../core/node/tree'
import { publicModerationApi, publicSharesApi } from '../api/client'
import { ReportCategory, ResponseError } from '../api/generated/moderation'
import VeyqSelect from '../shared/ui/VeyqSelect.vue'
import VeyqCheckbox from '../shared/ui/VeyqCheckbox.vue'
import { ShareRole, type PublicShare } from '../api/generated/sharing'
import { fakeApi, stubApi } from '../test/apiMock'
import { realI18n, t } from '../test/i18n'
import { authenticatedCalls } from '../test/guestMode'
import { OPERATOR_SECTION } from '../test/models'

const SCOPE_ID = '66666666-6666-4666-8666-666666666666'
const ROOT = '6e5a0000-0000-4000-8000-0000000000e0'
const CHILD = '6e5a0000-0000-4000-8000-0000000000e1'
const K_SHARE = v1.randomBytes(32)
const ROOT_KEY = v1.randomBytes(32)
const CHILD_KEY = v1.randomBytes(32)
const M = v1.generateModerationKey()

function head(role: ShareRole): PublicShareHead {
  const address = { scopeId: SCOPE_ID, nodeId: CHILD, epoch: 0, placementRevision: 0 }
  const sealedName = b64std(v1.sealField({ purpose: 'share-memory-name' }, K_SHARE, v1.utf8('Familie')))
  // An upload link is a Memory's drop box (Memories claims its page); its guest holds the drop PUBLIC key only.
  const sealedDropPublicKey = b64std(v1.seal({ purpose: 'drop-public-key' }, K_SHARE, v1.serializeHybridPublic(v1.dropKeyPublic(v1.generateDropKey()))))
  const share = role === ShareRole.WriteOnly
    ? { role, scopeId: SCOPE_ID, rootKind: MEMORY_KIND, nodeId: ROOT, sealedName, sealedDropPublicKey, nodes: [] }
    : {
        role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, sealedName, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, ROOT_KEY)),
        nodes: [
          { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
          {
            id: CHILD, parentId: ROOT, kind: FOLDER_KIND, epoch: 0, placementRevision: 0,
            encryptedName: sealName(ROOT_KEY, address, 'Sommer'), wrappedContentKey: wrapContentKey(ROOT_KEY, address, CHILD_KEY),
          },
        ],
      }
  return {
    shareId: route.params.shareId, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role,
    rootKind: role === ShareRole.WriteOnly ? MEMORY_KIND : FOLDER_KIND,
    share: share as PublicShare,
  }
}

/** The share page with the app's one report dialog beside it, as App.vue mounts them. */
function mountPage(): VueWrapper {
  const Page = defineComponent({ render: () => [h(ShareView), h(ReportDialog)] })
  return mount(Page, { attachTo: document.body, global: { plugins: [realI18n()] } })
}

function publishKey() {
  const bundle = v1.moderationBundle(M)
  stubApi(publicModerationApi, { getModerationKey: { epoch: 1, kemPublicKey: b64std(bundle.kem), signPublicKey: b64std(bundle.sign) } })
}

/** Fill the open dialog — a category, a reason, the good-faith tick — and send it. The layer's select and checkbox are
 *  driven through their components (their popups and inputs are the library's), the reason through its textarea. */
async function fillAndSend(w: VueWrapper) {
  await vi.waitFor(() => expect(document.body.querySelector('.veyq-report-dialog')).not.toBeNull())
  const dialog = w.findComponent(ReportDialog)
  dialog.findComponent(VeyqSelect).vm.$emit('update:modelValue', ReportCategory.TermsBreach)
  dialog.findComponent(VeyqCheckbox).vm.$emit('update:modelValue', true)
  const reason = document.body.querySelector<HTMLTextAreaElement>('#veyq-report-reason')!
  reason.value = 'canary-guest-reason'
  reason.dispatchEvent(new Event('input'))
  await flushPromises()
  const send = document.body.querySelector<HTMLButtonElement>('.veyq-report-send')!
  await vi.waitFor(() => expect(send.disabled).toBe(false))
  send.click()
  await flushPromises()
}

beforeEach(() => {
  document.body.innerHTML = ''
  authenticatedCalls.length = 0
  resetReporting()
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  route.hash = `#k=${b64url(K_SHARE)}`
  route.query = {}
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
})

describe('ShareView: a guest reports', () => {
  it('a READ guest selects a node of the link and reports it through the link, never an authenticated client', async () => {
    vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(head(ShareRole.Read))
    publishKey()
    const reports = stubApi(publicSharesApi, { reportInShare: { reportId: '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f', receivedAt: new Date('2026-10-17T12:00:00Z') } })
    const w = mountPage()
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.veyq-select-button').exists()).toBe(true))
    await w.find('.veyq-select-button').trigger('click')
    await w.find('.veyq-level-list input[type="checkbox"]').setValue(true)
    await w.find('.veyq-select-report').trigger('click')
    await fillAndSend(w)

    await vi.waitFor(() => expect(reports.reportInShare).toHaveBeenCalledTimes(1))
    const call = reports.reportInShare.mock.calls[0][0]
    expect(call.shareId).toBe(route.params.shareId)
    expect(call.xShareWrite).toBe(b64std(v1.shareWriteToken(K_SHARE, route.params.shareId)))
    expect(call.reportSubmission.items.map((i) => ({ kind: i.kind, id: i.id }))).toEqual([{ kind: 'NODE', id: CHILD }])
    expect(JSON.stringify(call.reportSubmission)).not.toContain('canary-guest-reason')
    const opened = v1.openReport(1, v1.moderationKemSecret(M), fromB64std(call.reportSubmission.sealedReport))
    const keys = v1.openReportShare(opened.reportKey, { kind: 'NODE', id: CHILD }, fromB64std(call.reportSubmission.items[0].sealedShare))
    expect(keys.find((k) => k.role === 'NODE')!.key).toEqual(CHILD_KEY)
    expect(keys.map((k) => k.role)).toContain('NODE_NAME')
    await vi.waitFor(() => expect(document.body.querySelector('.veyq-report-receipt')?.textContent).toContain('0c1d2e3f'))
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('an upload link\'s guest reports the link from the footer: K_share and the link\'s name, nothing else', async () => {
    vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(head(ShareRole.WriteOnly))
    publishKey()
    const reports = stubApi(publicSharesApi, { reportInShare: { reportId: '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f', receivedAt: new Date() } })
    const w = mountPage()
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.share-view-report').exists()).toBe(true))
    expect(w.find('.share-view-report').text()).toBe(t('report.reportLink'))
    await w.find('.share-view-report').trigger('click')
    await fillAndSend(w)

    await vi.waitFor(() => expect(reports.reportInShare).toHaveBeenCalledTimes(1))
    const submission = reports.reportInShare.mock.calls[0][0].reportSubmission
    expect(submission.items.map((i) => i.kind)).toEqual(['LINK'])
    const opened = v1.openReport(1, v1.moderationKemSecret(M), fromB64std(submission.sealedReport))
    const keys = v1.openReportShare(opened.reportKey, { kind: 'LINK', shareId: route.params.shareId }, fromB64std(submission.items[0].sealedShare))
    expect(keys.map((k) => k.role)).toEqual(['SHARE', 'LINK_NAME'])
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('before the instance publishes a key, no report action is shown', async () => {
    vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(head(ShareRole.WriteOnly))
    fakeApi(publicModerationApi, {
      getModerationKey: () => Promise.reject(new ResponseError(new Response('{"code":"moderation_off"}', { status: 404 }))),
    })
    const w = mountPage()
    await flushPromises()
    await vi.waitFor(() => expect(w.find('.share-view-footer').exists()).toBe(true))
    await flushPromises()
    expect(w.find('.share-view-report').exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })
})
