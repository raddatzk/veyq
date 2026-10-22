// @vitest-environment happy-dom
// The accountless terms gate on the share page (uc.sharing.share-accept, ADR 0035 §3). Load-bearing behaviours:
// a not-yet-accepted visitor sees the gate (not the content); accepting clears it and reveals the content and
// persists per tenant (a reload does not re-prompt); an owner revision bump re-gates; a transient terms-fetch
// error FAILS OPEN (content, never a lock-out). The operator section comes after the space's text (ADR 0035 decision 6)
// and what is stored is every accepted PAIR: an operator-revision bump re-gates, a value change does not, and an earlier
// client's triple (with the retired variant) asks once more. We drive the REAL terms helpers (localStorage per tenant, the
// locale pick) + the REAL ShareView wiring (needsTerms/loadTerms/acceptTerms) — only the network resolve/terms
// reads and the heavy media composables are stubbed, so what's exercised is the gate itself, not resolve crypto.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const SHARE_ID = 's1'
const TENANT = 't-1'
const ACCEPT_KEY = `veyq:terms-accepted:${TENANT}`

// The drop page's tile composable → an inert stub (no crypto, no object URLs, no timers).
vi.mock('../features/memories/useOptimisticTiles', () => ({
  useOptimisticTiles: () => ({ optimisticTiles: { value: [] }, donorPreviewFor: () => undefined }),
}))

// The generated client is never hit (resolvePublicShare/openSharedMemory/fetchShareTerms are overridden below), but share.ts imports it
// at module load — mock it exactly as share.test.ts does so loading the real module is side-effect-free.
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))

// A resolvable, unlocked share reached via a valid fragment link.
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: SHARE_ID }, hash: '#k=AAAA' }) }))

// Keep the REAL terms helpers (acceptedTermsKeys/recordTermsAccepted/pickShareTerms) + error classes; override
// only the two network reads so no resolve crypto runs.
// The shell resolves the platform half and fetches the terms; the drop page opens the rest. Only those reads are
// stubbed — the shell, the registry, the page and the helpers it uses are real. The gates are role-independent, so they
// are proven on a WRITE_ONLY link's drop page (no tree to open); a READ/READ_WRITE link's page with real crypto is
// `ShareView.memory.test.ts`'s.
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})
vi.mock('../features/memories/share', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/memories/share')>()
  return { ...actual, openSharedMemory: vi.fn(), listShareMedia: vi.fn(), createContributorIdentity: vi.fn() }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms } from '../core/sharing/resolvePublicShare'
import { openSharedMemory, listShareMedia, createContributorIdentity, NO_SHARE_MEDIA, type ContributorSession, type ResolvedShare, type ShareAccess } from '../features/memories/share'
import { loadGuestSession, saveGuestSession } from '../core/sharing/guestIdentity'
import { mountConfirmDialog } from '../test/confirmDialog'
import { confirmRequest, settleConfirm } from '../shared/state/useConfirm'
import { headOf, openedOf } from '../test/shareHead'
import { ShareRole } from '../api/generated/sharing'
import { realI18n, t } from '../test/i18n'
import type { NodeKind } from '../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../features/memories/kinds'
import { OPERATOR_SECTION } from '../test/models'
import type { OperatorSection } from '../core/terms/operatorSection'

function mountShareView() {
  return mount(ShareView, {
    global: {
      plugins: [realI18n()],
      stubs: { MediaGrid: true, OptimisticTile: true },
    },
  })
}

/** Serve one resolved share to the shell (head) and the drop page (the rest), as production splits it. */
function serve(r: ResolvedShare, rootKind: NodeKind = MEMORY_KIND) {
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(headOf(r, 's1', rootKind))
  vi.mocked(openSharedMemory).mockReset().mockReturnValue(openedOf(r))
  vi.mocked(listShareMedia).mockReset().mockResolvedValue(NO_SHARE_MEDIA) // the items come from openSharedMemory's stub
}

const DROP: ShareAccess = { kind: 'drop', nodeId: 'n1', dropPublicKey: new Uint8Array() }
function dropShare(): ResolvedShare {
  return { memoryName: 'My Memory', noteByLocale: {}, role: ShareRole.WriteOnly, scopeId: 'scope-1', access: DROP, items: [] }
}
/** A named uploader on this device: its page's content is the drop (`.share-view-header`), past every gate. */
function nameUploader() {
  const uploader: ContributorSession = { identityId: 'id-0', contributorToken: 'tok-0', uploaderKey: 'AAAA' }
  saveGuestSession(SHARE_ID, uploader)
}

describe('ShareView — accountless terms gate', () => {
  beforeEach(() => {
    localStorage.clear()
    nameUploader()
    serve(dropShare())
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({
      tenantId: TENANT,
      termsRevision: 1,
      terms: { de: '## Regeln', en: '## Rules' },
      operatorSection: OPERATOR_SECTION,
    })
  })

  it('shows the terms gate (not the content) for a not-yet-accepted visitor', async () => {
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true) // the gate
    expect(wrapper.find('.share-view-header').exists()).toBe(false) // content is withheld
    // the terms markdown is rendered (safe markdown) inside the gate, the operator section after it
    const body = wrapper.find('.share-terms-body').text()
    expect(body).toContain('Rules')
    expect(body.indexOf('On this instance')).toBeGreaterThan(body.indexOf('Rules'))
  })

  it('accepting clears the gate, reveals the content, and persists per tenant', async () => {
    const wrapper = mountShareView()
    await flushPromises()

    await wrapper.find('.share-terms-card .share-name-submit').trigger('click')
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false) // gate cleared
    expect(wrapper.find('.share-view-header').exists()).toBe(true) // content shown
    expect(localStorage.getItem(ACCEPT_KEY)).toBe('1:1') // client-only, keyed per tenant, holding the pair
  })

  it('does not re-prompt after a reload once accepted for the revision', async () => {
    localStorage.setItem(ACCEPT_KEY, '1:1') // accepted on a previous visit
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false)
    expect(wrapper.find('.share-view-header').exists()).toBe(true)
  })

  it('re-gates when the space owner bumps the terms revision', async () => {
    localStorage.setItem(ACCEPT_KEY, '1:1') // previously accepted v1
    vi.mocked(fetchShareTerms).mockResolvedValue({ tenantId: TENANT, termsRevision: 2, operatorSection: OPERATOR_SECTION, terms: { en: '## Rules v2' } })
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true) // stored 1 < current 2 → gate re-fires
    expect(wrapper.find('.share-view-header').exists()).toBe(false)
  })

  it('re-gates on an operator-section revision bump, not on a changed value', async () => {
    localStorage.setItem(ACCEPT_KEY, '1:1')
    const served = (section: OperatorSection) =>
      vi.mocked(fetchShareTerms).mockResolvedValue({ tenantId: TENANT, termsRevision: 1, operatorSection: section, terms: { en: '## Rules' } })
    const gated = async () => {
      const w = mountShareView()
      await flushPromises()
      return w.find('.share-terms-card').exists()
    }
    served({ ...OPERATOR_SECTION, text: { en: '## On this instance\nOperator: Neuer Verein e. V.' } })
    expect(await gated()).toBe(false) // control: a changed value reopens nothing
    served({ ...OPERATOR_SECTION, revision: 2 })
    expect(await gated()).toBe(true) // a release changed the section
  })

  it('a triple an earlier client stored (with the retired variant) asks once more', async () => {
    localStorage.setItem(ACCEPT_KEY, '1:1:FULL')
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-terms-card').exists()).toBe(true)
  })

  it('an acceptance an older client stored (a bare revision) asks once more: the section was not part of it', async () => {
    localStorage.setItem(ACCEPT_KEY, '1')
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-terms-card').exists()).toBe(true)
  })

  it('fails open (shows content, no gate) on a transient terms-fetch error', async () => {
    vi.mocked(fetchShareTerms).mockRejectedValue(new Error('network'))
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false) // a network blip never locks the visitor out
    expect(wrapper.find('.share-view-header').exists()).toBe(true)
  })
})

// The gate order password -> terms -> name -> content (the name pre-screen is the drop page's first screen). This is the
// only place the terms gate and the name gate meet, so it is where swapping them in the shell goes red.
describe('ShareView — terms come before the name pre-screen', () => {
  it('a not-yet-accepted uploader sees the terms first and the name screen only after accepting', async () => {
    localStorage.clear()
    serve(dropShare())
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 1, operatorSection: OPERATOR_SECTION, terms: { en: '## Rules' } })
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true)
    expect(wrapper.find('.share-name-card:not(.share-terms-card)').exists()).toBe(false) // name screen withheld

    await wrapper.find('.share-terms-card .share-name-submit').trigger('click')
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false)
    expect(wrapper.find('.share-name-card').exists()).toBe(true) // now the name screen
  })
})

// The drop page's name pre-screen tells a guest who reads the name it types (ADR 0030 point 8): the Memory's members, and
// no other link holder — which is what the resolve serves, so the page must not promise "everyone" either. (A
// contributor names itself in the shell's dialog, whose hint says the same: `ShareView.naming.test.ts`.)
describe('ShareView — who sees a guest name', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
  })

  it("the uploader's name screen says only the Memory's members see the name", async () => {
    serve(dropShare())
    const wrapper = mountShareView()
    await flushPromises()

    const card = wrapper.find('.share-name-card')
    expect(card.exists()).toBe(true) // the gate itself, so the text checks below cannot pass on an empty page
    expect(card.text()).toContain(t('share.nameVisibility'))
    // the same page serves a chapter's link: its words name no Memory (#399) — only the title (the link's name) may
    expect(card.text().replace(card.find('h1').text(), ''), 'no Memory wording on a chapter link').not.toMatch(/memory/i)
    expect(card.text()).not.toContain('everyone')
  })
})

// A chapter is a share root of its own (ADR 0080 Decision 13): over the REAL registry, a WRITE_ONLY link the server roots
// at a CHAPTER reaches Memories' drop page as a Memory's does — never the shell's unavailable state, which is what an
// unclaimed root kind renders (`ShareView.registry.test.ts`). A viewer's chapter link is `ShareView.memory.test.ts`'s.
describe('ShareView — a chapter share', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
  })

  it('an uploader link rooted at a CHAPTER reaches the drop\'s name screen', async () => {
    const access: ShareAccess = { kind: 'drop', nodeId: 'chapter-1', dropPublicKey: new Uint8Array() }
    serve({ ...dropShare(), memoryName: 'Day 1', access }, CHAPTER_KIND)
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-view-unavailable').exists()).toBe(false)
    expect(wrapper.find('.share-name-card').exists()).toBe(true)
  })
})

// A Memory link's uploader names itself with core's one form (inbox #401 S2) and has the "Not you?" Depot's drop box had —
// the drop page's own line (a contributor's is the shell's: `ShareView.memory.test.ts`). It asks first, then forgets the
// session and shows the name screen again.
describe('ShareView — a Memory drop: core\'s naming form and its "Not you?"', () => {
  beforeEach(() => {
    if (confirmRequest.value) settleConfirm(false)
    localStorage.clear()
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
    vi.mocked(createContributorIdentity).mockReset()
  })

  it('WRITE_ONLY: the drop page carries its own line (the shell shows none); "Not you?" forgets the uploader', async () => {
    const uploader: ContributorSession = { identityId: 'id-2', contributorToken: 'tok-2', uploaderKey: 'AAAA', displayName: 'Ben' }
    saveGuestSession(SHARE_ID, uploader)
    serve(dropShare())
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-view-identity').exists(), "not the shell's").toBe(false)
    const line = wrapper.find('.share-view-header .guest-naming-identity')
    expect(line.text()).toContain(t('share.guest.as', { name: 'Ben' }))
    const dialog = mountConfirmDialog()
    await line.find('button.guest-naming-not-you').trigger('click')
    await flushPromises()
    dialog.click(t('share.guest.notYouAccept'))
    await flushPromises()
    expect(loadGuestSession(SHARE_ID)).toBeNull()
    expect(wrapper.find('.share-name-card').exists()).toBe(true)
  })
})
