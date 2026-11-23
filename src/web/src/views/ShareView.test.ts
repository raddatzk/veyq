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
import { forgetDonationDismissalForTest } from '../core/project/donationDismissal'

function mountShareView() {
  return mount(ShareView, {
    global: {
      plugins: [realI18n()],
      // The dialog teleports; rendered in place so a test reads the shell's naming dialog.
      stubs: {
        MediaGrid: true,
        OptimisticTile: true,
        VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
      },
    },
  })
}

/** Serve one resolved share to the shell (head) and the drop page (the rest), as production splits it. */
function serve(r: ResolvedShare, rootKind: NodeKind = MEMORY_KIND) {
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(headOf(r, 's1', rootKind))
  vi.mocked(openSharedMemory).mockReset().mockReturnValue(openedOf(r))
  vi.mocked(listShareMedia).mockReset().mockResolvedValue(NO_SHARE_MEDIA) // the items come from openSharedMemory's stub
}

const DROP: ShareAccess = { kind: 'drop', nodeId: 'n1', dropPublicKey: new Uint8Array(), rootEpoch: 0 }
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
    expect(body.indexOf('On this server')).toBeGreaterThan(body.indexOf('Rules'))
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
    served({ ...OPERATOR_SECTION, text: { en: '## On this server\nOperator: Neuer Verein e. V.' } })
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

// The gate order password -> terms -> content; an unnamed uploader's name is asked at its first pick (inbox #1090), so
// no name screen sits between the terms and the drop page any more.
describe('ShareView — terms come before the drop page', () => {
  it('a not-yet-accepted, unnamed uploader sees the terms first and the drop page — no name screen — after accepting', async () => {
    localStorage.clear()
    serve(dropShare())
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 1, operatorSection: OPERATOR_SECTION, terms: { en: '## Rules' } })
    const wrapper = mountShareView()
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(true)
    expect(wrapper.find('.share-view-header').exists()).toBe(false) // the drop page withheld

    await wrapper.find('.share-terms-card .share-name-submit').trigger('click')
    await flushPromises()

    expect(wrapper.find('.share-terms-card').exists()).toBe(false)
    expect(wrapper.find('.share-view-header').exists()).toBe(true) // now the drop page
    expect(wrapper.find('.guest-naming-input').exists(), 'no name form in it').toBe(false)
  })
})

// The name an uploader is asked at its first pick tells it who reads the name it types (ADR 0030 point 8): the people the
// Memory belongs to, and no other link holder — which is what the resolve serves, so neither the dialog nor the page may
// promise "everyone". The dialog is the shell's one (inbox #1090), whose hint a contributor reads too
// (`ShareView.naming.test.ts`).
describe('ShareView — who sees a guest name', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
  })

  it("the uploader's name, asked at its first pick, says only the people this belongs to see it", async () => {
    serve(dropShare())
    const wrapper = mountShareView()
    await flushPromises()
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'a.jpg', { type: 'image/jpeg' })], configurable: true })
    await input.trigger('change')
    await flushPromises()

    const dialog = wrapper.find('.dialog-stub.guest-naming')
    expect(dialog.exists()).toBe(true) // the dialog itself, so the text checks below cannot pass on an empty page
    expect(dialog.text()).toContain(t('share.guest.nameHint'))
    expect(dialog.text()).not.toContain('everyone')
    // the same page serves a chapter's link: neither the dialog nor the page's words name a Memory (#399) — only the
    // title (the link's name) may
    expect(dialog.text(), 'no Memory wording on a chapter link').not.toMatch(/memory/i)
    const page = wrapper.find('.share-view-header').text().replace(wrapper.find('.gallery-title').text(), '')
    expect(page, 'no Memory wording on a chapter link').not.toMatch(/memory/i)
    expect(wrapper.find('.share-uploader-intro').text()).not.toMatch(/memory|everyone/i)
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

  it('an uploader link rooted at a CHAPTER reaches the drop page', async () => {
    const access: ShareAccess = { kind: 'drop', nodeId: 'chapter-1', dropPublicKey: new Uint8Array(), rootEpoch: 0 }
    serve({ ...dropShare(), memoryName: 'Day 1', access }, CHAPTER_KIND)
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-view-unavailable').exists()).toBe(false)
    expect(wrapper.find('.share-view-header .gallery-title').text()).toBe('Day 1')
  })
})

// A Memory link's uploader, once named, has core's one line with its "Not you?" (inbox #401 S2) — the drop page's own line
// (a contributor's is the shell's: `ShareView.memory.test.ts`). It asks first, then forgets the session; the next pick
// asks a name again (inbox #1090), so no name screen comes back.
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
    expect(wrapper.find('.guest-naming-identity').exists(), 'no line once forgotten').toBe(false)
    expect(wrapper.find('.guest-naming-input').exists(), 'no name form either').toBe(false)
    expect(wrapper.find('.share-add-button').exists(), 'the drop stays').toBe(true)
  })
})

// uc.platform.support-the-project on the share page: a guest's banner, once, above the gates (no account is asked — the
// guest-mode proof is `core/project/DonationBanner.guest.test.ts`), and the footer's project links in one place, the
// instance footer (`OperatorIdentification`) — the page's former security link of its own is gone, not doubled.
describe('ShareView — the donation banner and the footer links', () => {
  beforeEach(() => {
    localStorage.clear()
    forgetDonationDismissalForTest()
    nameUploader()
    serve(dropShare())
    vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: TENANT, termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
  })

  it('shows one banner of each kind at the top, and the footer links the security and donation pages once each', async () => {
    const wrapper = mountShareView()
    await flushPromises()
    expect(wrapper.find('.share-view-header').exists()).toBe(true) // the content: the positive control
    expect(wrapper.findAll('[data-testid="open-in-app"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="donation-banner"]')).toHaveLength(1)
    // the open-in-app banner first (ADR 0150 § O2), then the donation banner
    const top = wrapper.find('.share-view').element
    expect(top.firstElementChild?.matches('[data-testid="open-in-app"]')).toBe(true)
    expect(top.firstElementChild?.nextElementSibling?.matches('[data-testid="donation-banner"]')).toBe(true)
    const footerLinks = wrapper.findAll('.share-view-footer a').map((a) => a.attributes('href'))
    expect(footerLinks.filter((h) => h === 'https://veyq.app/privacy/is-my-data-safe/')).toHaveLength(1)
    expect(footerLinks.filter((h) => h === 'https://veyq.app/donate/')).toHaveLength(1)
  })

  it('a dismissed banner stays gone when the page is opened again', async () => {
    const first = mountShareView()
    await flushPromises()
    await first.find('[data-testid="donation-banner-dismiss"]').trigger('click')
    await flushPromises()
    expect(first.find('[data-testid="donation-banner"]').exists()).toBe(false)
    first.unmount()

    forgetDonationDismissalForTest()
    const again = mountShareView()
    await flushPromises()
    expect(again.find('.share-view-header').exists()).toBe(true)
    expect(again.find('[data-testid="donation-banner"]').exists()).toBe(false)
    again.unmount()
  })
})
