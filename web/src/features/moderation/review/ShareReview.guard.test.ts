// @vitest-environment happy-dom
// The review's one guarantee about where it reads (uc.moderation.review-a-case steps 4-5; decision 6 of
// cr-moderation-review-and-measures; `core/sharing/review.ts`): a moderator's review renders the held snapshot with the
// GUEST views — core's share page, the member's level, the applications' rows — and every read those views make goes to
// the moderation reads over the case, each with a key proof over its own operation and the case; none reaches a guest
// endpoint (`public…Api`: a link's resolve, its listings, its presign) and none an authenticated member's client. Every
// client factory but the case reads is replaced by one that THROWS and is recorded, so a stray call is red even where a
// view would have swallowed the error. The reports open in the browser with the moderator's own key; the report share
// hands over the node key the snapshot opens with.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

/** The client factories a review called that it must never call, in order. */
const strayCalls: string[] = []
/** Every `…Api` factory throws and is recorded — but the case reads (`case…Api`), which the test stubs. */
function reviewClients<T extends object>(mod: T): T {
  const out: Record<string, unknown> = {}
  for (const [name, value] of Object.entries(mod)) {
    out[name] =
      typeof value === 'function' && /Api$/.test(name)
        ? name.startsWith('case')
          ? vi.fn()
          : vi.fn(() => {
              strayCalls.push(name)
              throw new Error(`the review called ${name}()`)
            })
        : value
  }
  return out as T
}

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', async (orig) => reviewClients(await orig<object>()))
vi.mock('../../memory/api', async (orig) => reviewClients(await orig<object>()))
vi.mock('../../depot/api', async (orig) => reviewClients(await orig<object>()))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: <T>(f: () => Promise<T>) => f() }))
vi.mock('../proof', async (orig) => ({
  ...(await orig<object>()),
  proofFor: vi.fn(),
}))
vi.mock('../../memory/useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))

import ReviewBody from './ReviewBody.vue'
import { caseReviewApi } from '../../../api/client'
import { caseMediaApi } from '../../memory/api'
import { caseFilesApi } from '../../depot/api'
import { proofFor } from '../proof'
import { clearModerationKeys, holdModerationKeys } from '../moderationKeys'
import { activeReview } from '../../../core/sharing/review'
import { FOLDER_KIND } from '../../../core/node/kind'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import { ShareRole, type CaseReview, type ShareNode } from '../../../api/generated/sharing'
import { MEMORY_KIND } from '../../memory/kinds'
import { FILE_KIND } from '../../depot/kinds'
import { stubApi } from '../../../test/apiMock'
import { realI18n } from '../../../test/i18n'
import { setViewMode } from '../../../shared/state/useViewMode'

const CASE_ID = '7c000000-0000-4000-8000-000000000001'
const SCOPE_ID = '88888888-8888-4888-8888-888888888888'
const ROOT = '6e5a0000-0000-4000-8000-000000000700'
const SUB = '6e5a0000-0000-4000-8000-000000000701'
const MEMORY = '6e5a0000-0000-4000-8000-000000000702'
const FILE = '6e5a0000-0000-4000-8000-000000000703'
const EPOCH = 3
const keys = { root: v1.randomBytes(32), sub: v1.randomBytes(32), memory: v1.randomBytes(32), file: v1.randomBytes(32) }

function child(id: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementRevision: 0 }
  return {
    blockedByOperator: false, id, parentId: ROOT, kind, epoch: 0, placementRevision: 0,
    encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key),
  }
}

let moderationKey: Uint8Array
/** A case over a reported folder: one report, sealed to the held epoch, whose report share hands over the folder's key. */
function review(): CaseReview {
  const reportKey = v1.randomBytes(32)
  const body = { reportKey, reason: 'Gewaltdarstellung', context: 'Im zweiten Ordner' }
  return {
    caseId: CASE_ID,
    itemKind: 'NODE',
    itemId: ROOT,
    reports: [{
      reportId: 'r-1', epoch: EPOCH, receivedAt: new Date('2026-10-01T10:00:00Z'),
      sealedReport: b64std(v1.sealReport(EPOCH, v1.moderationBundle(moderationKey).kem, body)),
      sealedContact: b64std(v1.sealReportContact(EPOCH, v1.moderationBundle(moderationKey).kem, 'melder@example.org')),
      sealedShare: b64std(v1.sealReportShare(reportKey, { kind: 'NODE', id: ROOT }, [{ role: 'NODE', ref: null, key: keys.root }])),
    }],
    share: {
      role: ShareRole.Read, passwordRequired: false, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, nodeId: ROOT,
      nodes: [
        { blockedByOperator: false, id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0, placementRevision: 0 },
        child(SUB, keys.root, keys.sub, 'Briefe', FOLDER_KIND),
        child(MEMORY, keys.root, keys.memory, 'Rom 2026', MEMORY_KIND),
        child(FILE, keys.root, keys.file, 'rezept.pdf', FILE_KIND),
      ],
    },
    heldLinks: [],
  } as CaseReview
}

beforeEach(() => {
  localStorage.clear()
  strayCalls.length = 0
  vi.mocked(proofFor).mockReset()
  vi.mocked(proofFor).mockImplementation(async (operationId: string, caseId?: string | null) => ({
    xVeyqModerationChallenge: `c:${operationId}:${caseId}`, xVeyqModerationSignature: 's',
  }))
  setViewMode('grid')
  moderationKey = v1.generateModerationKey()
  holdModerationKeys(new Map([[EPOCH, moderationKey.slice()]]), EPOCH)
  stubApi(caseReviewApi, { getCaseReview: review(), presignCaseBlobs: { blobs: [] } })
  stubApi(caseMediaApi, { listCaseMedia: { media: [], identities: [] } })
  stubApi(caseFilesApi, { listCaseFiles: { items: [] } })
})
afterEach(() => clearModerationKeys())

async function mountReview() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:shareId', component: { render: () => null } }] })
  await router.push(`/s/${CASE_ID}#moderation`)
  await router.isReady()
  const w = mount(ReviewBody, {
    props: { caseId: CASE_ID },
    global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} }, stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
  })
  await flushPromises()
  await flushPromises()
  return { w, router }
}

describe('a review reads only the moderation reads over its case', () => {
  it('renders the held folder with the guest views, and calls no guest endpoint and no member client', async () => {
    const { w } = await mountReview()

    // The report opened with the moderator's key, beside the snapshot the report share's node key opened.
    expect(w.find('.moderation-review-reason').text()).toBe('Gewaltdarstellung')
    expect(w.text()).toContain('melder@example.org')
    for (const name of ['Briefe', 'Rom 2026', 'rezept.pdf']) expect(w.text()).toContain(name)
    // The held snapshot is shown as it was: a block in force is the members' and guests' placeholder, never the review's.
    expect(w.text()).not.toContain('Blocked by the operator')

    expect(strayCalls).toEqual([])
    expect(vi.mocked(proofFor).mock.calls.map(([op, caseId]) => `${op}@${caseId}`)).toContain(`getCaseReview@${CASE_ID}`)
    w.unmount()
  })

  it("opening a held Memory reads its media through the case's listing, with a proof over that read, and keeps the mode", async () => {
    const { w, router } = await mountReview()
    const open = w.find('li.veyq-level-item[data-kind="MEMORY"] button.veyq-level-item-main')
    open.element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
    await flushPromises()
    expect(router.currentRoute.value.query.node).toBe(MEMORY)
    expect(router.currentRoute.value.hash, 'the review stays the moderation mode').toBe('#moderation')
    await vi.waitFor(() => expect(w.find('.memory-detail').exists()).toBe(true))
    await vi.waitFor(() => expect(vi.mocked(caseMediaApi().listCaseMedia)).toHaveBeenCalled())

    expect(vi.mocked(caseMediaApi().listCaseMedia)).toHaveBeenCalledWith(
      expect.objectContaining({ caseId: CASE_ID, xVeyqModerationChallenge: `c:listCaseMedia:${CASE_ID}` }),
    )
    const ops = vi.mocked(proofFor).mock.calls.map(([op, caseId]) => `${op}@${caseId}`)
    expect(ops.every((o) => o.endsWith(`@${CASE_ID}`))).toBe(true)
    expect(strayCalls).toEqual([])
    w.unmount()
  })

  it('makes the review the page data source while mounted, and ends it on leaving', async () => {
    const { w } = await mountReview()
    expect(activeReview()?.caseId).toBe(CASE_ID)
    w.unmount()
    expect(activeReview()).toBeNull()
  })

  it('a report this account holds no key for opens nothing, and the snapshot stays closed', async () => {
    clearModerationKeys()
    holdModerationKeys(new Map([[EPOCH + 1, v1.generateModerationKey()]]), EPOCH + 1)
    const { w } = await mountReview()
    expect(w.find('.moderation-review-reason').exists()).toBe(false)
    expect(w.text()).not.toContain('Briefe')
    expect(strayCalls).toEqual([])
    w.unmount()
  })
})
