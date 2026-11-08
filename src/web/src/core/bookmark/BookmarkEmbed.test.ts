// @vitest-environment happy-dom
// A bookmark's page shows its share in place (ADR 0121), mounted as the member's space page mounts it: core's node page with
// the member's doors around the bookmark's detail. What it pins:
//   - the share is read as the link's guest, from the instance that serves it (the link's origin), after that instance said
//     it may be shown (decision 4); every refusal falls back to ADR 0120's page with one line saying why;
//   - the member's doors, actions and header never reach the embedded view, and nothing of the guest reaches the member's
//     header; no authenticated client is called (decision 5);
//   - the share page's gates run inline, in its order: the bookmark's password once (a wrong one leaves the form), the
//     share's tenant's terms and the note's acceptance, kept under the share's origin (decision 6);
//   - a guest's write goes to the share's instance (decision 7), and so does a report (decision 8);
//   - with `core.share-embedding` off, the page is ADR 0120's exactly, and the row opens the link (decision 2).
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../sharing/shareInstance', async (orig) => ({ ...(await orig<object>()), readShareInstance: vi.fn() }))
vi.mock('../sharing/resolvePublicShare', async (orig) => ({
  ...(await orig<object>()),
  resolvePublicShare: vi.fn(),
  pollPublicShare: vi.fn(async () => null),
  fetchShareTerms: vi.fn(),
  derivePasswordToken: vi.fn(async (pw: string) => `token-of-${pw}`),
}))
vi.mock('../report/report', async (orig) => {
  const { ref } = await import('vue')
  return { ...(await orig<object>()), useReportOffered: vi.fn(() => ref(true)), openReport: vi.fn() }
})

import { computed, defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import BookmarkDetail from './BookmarkDetail.vue'
import { keptLink } from './bookmarkLink'
import { bookmarkMetaObject } from './bookmarkMeta'
import { bookmarkRows } from './bookmarkRows'
import LevelView from '../node/LevelView.vue'
import { sealNodeMeta } from '../node/nodeMeta'
import { sealName, wrapContentKey, type TreeNode } from '../node/tree'
import { FOLDER_KIND, SHARE_BOOKMARK_KIND } from '../node/kind'
import { readShareInstance } from '../sharing/shareInstance'
import {
  fetchShareTerms,
  resolvePublicShare,
  SharePasswordIncorrectError,
  SharePasswordRequiredError,
  ShareUnavailableError,
  type PublicShareHead,
} from '../sharing/resolvePublicShare'
import { saveGuestSession } from '../sharing/guestIdentity'
import { openReport, useReportOffered, type ReportRequest } from '../report/report'
import { publicSharesApi } from '../../api/client'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import type { SetupStatus } from '../../api/generated'
import { capabilityState, EXPECTED_MAJOR } from '../../shared/state/capabilities'
import { CORE_SHARE_EMBEDDING } from '../../features/platform/capabilities'
import { authenticatedCalls } from '../../test/guestMode'
import { stubApi } from '../../test/apiMock'
import { mountNodePage } from '../../test/nodePage'
import { hostFor } from '../../test/nodeHost'
import { realI18n, t } from '../../test/i18n'
import { chooseNew } from '../../test/newMenu'
import { chooseHeaderEntry } from '../../test/nodeHeader'
import { memberAccessOf, memberTreeOf, OPERATOR_SECTION, treeNode } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { registry } from '../applications/registry'
import { testApp, testKind } from '../../test/testApplication'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { injectNodeHost } from '../node/nodeHost'
import { injectCoreNodeActions } from '../node/coreNodeActions'
import { contributeNodeHeader } from '../node/nodeHeaderContribution'
import type { GuestAccess } from '../access'
import { SHARE_KEY } from '../sharing/shareTree'
import type { LevelRowsContext, LevelRowsHost } from '../node/levelRow'

const AWAY = 'https://photos.example.org'
const SCOPE = '5c09e000-0000-4000-8000-0000000000ab'
const EPOCHS = epochsOf(new Uint8Array(32).fill(4))
const KEY = new Uint8Array(32).fill(8)
// the share on the other instance
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_7'
const SHARE_SCOPE = '77777777-7777-4777-8777-777777777777'
const ROOT = '6e5a0000-0000-4000-8000-0000000000e0'
const LETTERS = '6e5a0000-0000-4000-8000-0000000000e1'
const K_SHARE = v1.randomBytes(32)
const kRoot = v1.randomBytes(32)
const kLetters = v1.randomBytes(32)
const FRAGMENT = b64url(K_SHARE)
const LINK = `${AWAY}/s/${SHARE_ID}#k=${FRAGMENT}`

const FOLDER = treeNode({ id: 'f0000000-0000-4000-8000-000000000001', kind: FOLDER_KIND, name: 'Links', parentId: null })
const bookmarkNode = (link = LINK, password: string | null = null): TreeNode => {
  const id = 'b0000000-0000-4000-8000-000000000002'
  const wrappedMeta = sealNodeMeta(KEY, { scopeId: SCOPE, nodeId: id }, bookmarkMetaObject(keptLink(link)!, password))
  return treeNode({ id, kind: SHARE_BOOKMARK_KIND, name: 'Holiday', parentId: FOLDER.id, wrappedMeta })
}

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string): ShareNode {
  const address = { scopeId: SHARE_SCOPE, nodeId: id, scopeEpoch: 0, placementRevision: 0 }
  return { blockedByOperator: false, id, parentId, kind: FOLDER_KIND, epoch: 0, scopeEpoch: 0, placementRevision: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
/** The head the share's instance resolves to: a folder with one sub-folder, read through `apiBase`. */
function head(role: ShareRole = ShareRole.Read, o: Partial<PublicShareHead> = {}): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SHARE_SCOPE, name: 'Urlaub', noteByLocale: {}, role, rootKind: FOLDER_KIND, apiBase: AWAY,
    share: {
      role, scopeId: SHARE_SCOPE, rootKind: FOLDER_KIND, wrap: b64std(v1.seal(SHARE_KEY, K_SHARE, kRoot)),
      nodes: [{ id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0, scopeEpoch: 0, blockedByOperator: false }, child(LETTERS, ROOT, kRoot, kLetters, 'Briefe')],
    } as PublicShare,
    ...o,
  }
}
const setup = (ids = ['core.public-shares', 'core.public-shares.embeddable'], major = EXPECTED_MAJOR): SetupStatus =>
  ({ setupRequired: false, version: { major, build: 1 }, capabilities: ids.map((id) => ({ id })), suites: [], instanceSalt: 'x' }) as SetupStatus

// The member's space page: any path, the space's address in its fragment.
let router: Router
let push: ReturnType<typeof vi.spyOn>

function mountPage(node = bookmarkNode()) {
  const access = memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS })
  const tree = { ...memberTreeOf(() => [FOLDER, node], { scopeId: SCOPE, epochs: EPOCHS }), keyOf: () => KEY }
  const host = hostFor(access)
  const w = mountNodePage(BookmarkDetail, {
    props: { node, scopeId: SCOPE, epochs: EPOCHS, contentKey: KEY, children: { folders: [], byKind: new Map() }, access, tree },
    host,
    attachTo: document.body,
    global: {
      plugins: [realI18n(), router],
      stubs: { VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' } },
    },
  })
  return w
}
const memberHeader = (w: VueWrapper) => w.find('.veyq-node-header')
const memberHeaderIds = (w: VueWrapper) => memberHeader(w).findAll('.veyq-node-header-action').map((b) => b.attributes('data-action'))

// A drop page an application claimed, standing in for one that reaches for what is around it: the doors, core's actions and
// the header of the page it sits in - what a `WRITE_ONLY` link's body would find if the bookmark's page let anything through.
const PROBE_KIND = 'EMBED_PROBE'
const seen = { doors: null as string[] | null, core: undefined as unknown, access: null as GuestAccess | null }
const ProbeBody = defineComponent({
  name: 'ProbeBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'access'],
  setup(p) {
    seen.doors = Object.keys(injectNodeHost())
    seen.core = injectCoreNodeActions()
    seen.access = p.access as GuestAccess
    contributeNodeHeader(() => ({ actions: [{ id: 'probe', label: 'probe', icon: 'flag', onClick: () => {}, shown: true }] }))
    return () => h('div', { class: 'probe-body' })
  },
})
beforeAll(() => {
  testKind(registry, { rekeyContent: nothingOfItsOwnToRekey, kind: PROBE_KIND, icon: 'file' })
  testApp(registry).shareBody(PROBE_KIND, ProbeBody)
})

beforeEach(async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] })
  await router.push('/#links/holiday')
  await router.isReady()
  push = vi.spyOn(router, 'push')
  localStorage.clear()
  authenticatedCalls.length = 0
  vi.mocked(readShareInstance).mockReset().mockResolvedValue(setup())
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(head())
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 'tenant-away', termsRevision: 0, operatorSection: OPERATOR_SECTION })
  vi.mocked(openReport).mockClear()
  vi.mocked(useReportOffered).mockClear()
  capabilityState.ids = [CORE_SHARE_EMBEDDING]
  capabilityState.tenantIds = null
})
afterEach(() => {
  capabilityState.ids = null
})

describe('a bookmark\'s page shows its share in place', () => {
  it('reads the share as the link\'s guest, from its own instance, once that instance said it may be shown', async () => {
    const w = mountPage()
    await flushPromises()
    expect(readShareInstance).toHaveBeenCalledWith(AWAY)
    expect(resolvePublicShare).toHaveBeenCalledWith({ shareId: SHARE_ID, apiBase: AWAY }, FRAGMENT, undefined)
    expect(w.find('[data-testid="bookmark-embed"]').exists()).toBe(true)
    expect(w.find('[data-testid="bookmark-detail"]').exists(), 'not ADR 0120\'s page').toBe(false)
    const level = w.findComponent(LevelView)
    expect(level.props('access')).toMatchObject({ mode: 'guest', shareId: SHARE_ID, apiBase: AWAY })
    expect(level.findAll('.veyq-level-item-title').map((x) => x.text())).toEqual(['Briefe'])
    // the bookmark's header is the page's one h1: the share's node is named under it as a section (styleguide § Heading levels)
    expect(memberHeader(w).find('h1.veyq-node-header-title').text(), 'the bookmark\'s own name').toBe('Holiday')
    expect(level.find('h2.veyq-node-header-title').text(), 'the share\'s node, nested').toBe('Urlaub')
    expect(w.findAll('h1')).toHaveLength(1)
    expect(authenticatedCalls, 'the member\'s session is never used for the share').toEqual([])
    w.unmount()
  })

  // uc.platform.support-the-project A2: the member's page already carries the banner (`AppShell.vue`), so the share in
  // place brings no second one — it draws the share page's gates and views, not the share page.
  it('brings no donation banner of its own: one banner per page', async () => {
    const w = mountPage()
    await flushPromises()
    expect(w.findComponent(LevelView).findAll('.veyq-level-item-title').map((x) => x.text())).toEqual(['Briefe']) // the share is drawn
    expect(w.find('[data-testid="donation-banner"]').exists()).toBe(false)
    w.unmount()
  })

  it('the member\'s doors never reach it: a row opens inside the share, never the member\'s page', async () => {
    const w = mountPage()
    await flushPromises()
    await w.findComponent(LevelView).findAll('li.veyq-level-item').find((c) => c.text().includes('Briefe'))!.find('button.veyq-level-item-main').trigger('click')
    await flushPromises()
    expect(w.calls, 'the member page was asked nothing').toEqual({})
    expect(push, 'the space\'s address does not move').not.toHaveBeenCalled()
    expect(w.findComponent(LevelView).find('.veyq-node-header-title').text(), 'the share\'s own navigation').toBe('Briefe')
    // its breadcrumb, clicked as a user does: a real link to the bookmark's own page, its default prevented, the space's
    // address untouched - no node id of the other instance ever enters it
    const crumbs = w.findAll('a.veyq-breadcrumb__link')
    expect(crumbs.map((a) => a.text())).toEqual(['Urlaub', 'Briefe'])
    expect(crumbs.every((a) => a.attributes('href') === '/#links/holiday')).toBe(true)
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })
    crumbs[0].element.dispatchEvent(click)
    await flushPromises()
    expect(click.defaultPrevented).toBe(true)
    expect(w.findComponent(LevelView).find('.veyq-node-header-title').text()).toBe('Urlaub')
    expect(router.currentRoute.value.fullPath).toBe('/#links/holiday')
    expect(push).not.toHaveBeenCalled()
    w.unmount()
  })

  it('nothing of the guest reaches the member\'s header: the bookmark\'s actions as without the share in place', async () => {
    const on = mountPage()
    await flushPromises()
    const shown = memberHeaderIds(on)
    on.unmount()
    capabilityState.ids = [] // control: the page as ADR 0120 draws it
    const off = mountPage()
    await flushPromises()
    expect(off.find('[data-testid="bookmark-embed"]').exists()).toBe(false)
    expect(shown).toEqual(memberHeaderIds(off))
    expect(shown).toContain('open-own-page')
    off.unmount()
  })

  it('a drop page below it finds no door, no core action and no header of the member\'s page', async () => {
    seen.doors = null
    vi.mocked(resolvePublicShare).mockResolvedValue(head(ShareRole.WriteOnly, { rootKind: PROBE_KIND }))
    const w = mountPage()
    await flushPromises()
    expect(w.find('.probe-body').exists(), 'control: the drop page is drawn').toBe(true)
    expect(seen.doors, 'no door of the member\'s page').toEqual([])
    expect(seen.core, 'no core action of the member\'s page').toBeNull()
    expect(memberHeaderIds(w), 'nothing contributed to the member\'s header').not.toContain('probe')
    expect(seen.access).toMatchObject({ mode: 'guest', role: ShareRole.WriteOnly, apiBase: AWAY })
    // its report of the link itself goes to the share's instance
    await w.find('[data-testid="bookmark-embed-report"]').trigger('click')
    expect((vi.mocked(openReport).mock.calls[0][0] as ReportRequest).access).toMatchObject({ apiBase: AWAY, shareId: SHARE_ID })
    w.unmount()
  })

  it('"Open on its own page" opens the link where it lives, as ADR 0120 opens it', async () => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    const w = mountPage()
    await flushPromises()
    await memberHeader(w).find('[data-action="open-own-page"]').trigger('click')
    expect(open).toHaveBeenCalledWith(LINK, '_blank', 'noopener,noreferrer')
    vi.unstubAllGlobals()
    w.unmount()
  })
})

describe('where it may not be shown, ADR 0120\'s page says why', () => {
  it.each<[string, () => void, string]>([
    ['the share\'s instance does not answer', () => vi.mocked(readShareInstance).mockResolvedValue(null), 'unreachable'],
    ['it runs another major', () => vi.mocked(readShareInstance).mockResolvedValue(setup(undefined, EXPECTED_MAJOR + 1)), 'otherVersion'],
    ['it serves no links', () => vi.mocked(readShareInstance).mockResolvedValue(setup(['core.public-shares.embeddable'])), 'noLinks'],
    ['it keeps its links to itself', () => vi.mocked(readShareInstance).mockResolvedValue(setup(['core.public-shares'])), 'notEmbeddable'],
    ['the link gets no answer (its tenant keeps it to itself)', () => vi.mocked(resolvePublicShare).mockRejectedValue(new ShareUnavailableError('unreachable')), 'unreachable'],
  ])('%s', async (_name, arrange, reason) => {
    arrange()
    const w = mountPage()
    await flushPromises()
    expect(w.find('[data-testid="bookmark-embed"]').exists()).toBe(false)
    expect(w.find('[data-testid="bookmark-open"]').exists(), 'ADR 0120\'s open').toBe(true)
    expect(w.find('[data-testid="bookmark-fallback"]').text()).toBe(t(`node.bookmark.fallback.${reason}`, { host: 'photos.example.org' }))
    w.unmount()
  })

  it('a link closed since it was kept is the share page\'s unavailable state, in place (control: not a fallback)', async () => {
    vi.mocked(resolvePublicShare).mockRejectedValue(new ShareUnavailableError('not_found'))
    const w = mountPage()
    await flushPromises()
    expect(w.find('[data-testid="bookmark-embed"]').text()).toContain(t('share.unavailable'))
    expect(w.find('[data-testid="bookmark-fallback"]').exists()).toBe(false)
    w.unmount()
  })

  it('with core.share-embedding off: no read of any instance, ADR 0120\'s page without a line, and the row opens the link', async () => {
    capabilityState.ids = []
    const w = mountPage()
    await flushPromises()
    expect(readShareInstance).not.toHaveBeenCalled()
    expect(resolvePublicShare).not.toHaveBeenCalled()
    expect(w.find('[data-testid="bookmark-detail"]').exists()).toBe(true)
    expect(w.find('[data-testid="bookmark-fallback"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('the row of a bookmark', () => {
  function primaryOf(node: TreeNode) {
    const opened: TreeNode[] = []
    let source: ReturnType<typeof bookmarkRows> | null = null
    const tree = { ...memberTreeOf(() => [FOLDER, node], { scopeId: SCOPE, epochs: EPOCHS }), keyOf: () => KEY }
    const ctx = computed(() => ({ folder: FOLDER, nodes: [node], epochs: EPOCHS, access: memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS }), tree, viewMode: 'list', liveTick: 0 }) as unknown as LevelRowsContext)
    const Probe = defineComponent({ setup() { source = bookmarkRows(ctx, { open: (n: TreeNode) => opened.push(n), registerAction: () => {} } as unknown as LevelRowsHost); return () => h('div') } })
    mount(Probe, { global: { plugins: [realI18n(), router] } })
    return { click: () => source!.row(node)!.primary.onClick(), opened }
  }

  it('opens the bookmark\'s page with a plain click while the share is shown in place', async () => {
    const node = bookmarkNode()
    const row = primaryOf(node)
    await flushPromises()
    row.click()
    expect(row.opened).toEqual([node])
  })

  it('opens the link where it lives with core.share-embedding off (ADR 0120)', async () => {
    capabilityState.ids = []
    const open = vi.fn()
    vi.stubGlobal('open', open)
    const node = bookmarkNode()
    const row = primaryOf(node)
    await flushPromises()
    row.click()
    expect(row.opened).toEqual([])
    expect(open).toHaveBeenCalledWith(LINK, '_blank', 'noopener,noreferrer')
    vi.unstubAllGlobals()
  })
})

describe('the share page\'s gates, in place', () => {
  it('enters the bookmark\'s password once', async () => {
    vi.mocked(resolvePublicShare).mockReset()
      .mockRejectedValueOnce(new SharePasswordRequiredError('c2FsdA=='))
      .mockResolvedValueOnce(head())
    const w = mountPage(bookmarkNode(LINK, 'geheim'))
    await flushPromises()
    expect(vi.mocked(resolvePublicShare).mock.calls[1]).toEqual([{ shareId: SHARE_ID, apiBase: AWAY }, FRAGMENT, 'token-of-geheim'])
    expect(w.findComponent(LevelView).exists()).toBe(true)
    w.unmount()
  })

  it('a wrong one leaves the share page\'s password form, entered no second time', async () => {
    vi.mocked(resolvePublicShare).mockReset()
      .mockRejectedValueOnce(new SharePasswordRequiredError('c2FsdA=='))
      .mockRejectedValueOnce(new SharePasswordIncorrectError())
    const w = mountPage(bookmarkNode(LINK, 'falsch'))
    await flushPromises()
    expect(resolvePublicShare).toHaveBeenCalledTimes(2)
    expect(w.find('#share-password-input').exists()).toBe(true)
    expect(w.text()).toContain(t('share.passwordWrong'))
    w.unmount()
  })

  it('asks the share\'s tenant\'s terms, then the note, and keeps each acceptance under the share\'s origin', async () => {
    vi.mocked(fetchShareTerms).mockResolvedValue({ tenantId: 'tenant-away', termsRevision: 2, operatorSection: OPERATOR_SECTION, terms: { en: 'Be kind.' } })
    vi.mocked(resolvePublicShare).mockResolvedValue(head(ShareRole.Read, { noteByLocale: { en: 'Read me' }, noteAcceptance: { digest: 'dg' } }))
    localStorage.setItem('veyq:terms-accepted:tenant-away', `2:${OPERATOR_SECTION.revision}`) // this origin's tenant of that id
    const w = mountPage()
    await flushPromises()
    expect(w.text(), 'another instance\'s tenant: this origin\'s acceptance does not count').toContain('Be kind.')
    await w.findAll('button.share-name-submit').find((b) => b.text() === t('share.termsAccept'))!.trigger('click')
    expect(localStorage.getItem(`veyq:terms-accepted@${AWAY}:tenant-away`)).toBe(`2:${OPERATOR_SECTION.revision}`)
    expect(w.find('[data-testid="share-note-gate"]').text()).toContain('Read me')
    await w.find('[data-testid="share-note-gate"] button').trigger('click')
    expect(localStorage.getItem(`veyq:share-note-accepted@${AWAY}:${SHARE_ID}:dg`)).toBe('1')
    expect(w.findComponent(LevelView).exists()).toBe(true)
    w.unmount()
  })
})

describe('writes and reports go to the share\'s instance', () => {
  it('a READ_WRITE guest\'s new folder is created there, as the guest this device holds for that link', async () => {
    vi.mocked(resolvePublicShare).mockResolvedValue(head(ShareRole.ReadWrite))
    saveGuestSession(SHARE_ID, { identityId: 'here', contributorToken: 'tok-here' }) // this origin's link of the same id
    saveGuestSession({ shareId: SHARE_ID, apiBase: AWAY }, { identityId: 'away', contributorToken: 'tok-away' })
    const api = stubApi(publicSharesApi, { createShareNode: { nodeId: 'x' } })
    api.createShareNode.mockImplementation(async ({ createShareNodeRequest }) => ({ nodeId: createShareNodeRequest.nodeId }))
    const w = mountPage()
    await flushPromises()
    await chooseNew(w.findComponent(LevelView), t('node.folderCreate'))
    await w.find('.dialog-stub input').setValue('Neu')
    await w.find('.dialog-stub form').trigger('submit')
    await flushPromises()
    expect(api.createShareNode).toHaveBeenCalledOnce()
    expect(vi.mocked(publicSharesApi).mock.calls.every(([base]) => base === AWAY), 'every guest call at the share\'s origin').toBe(true)
    expect(api.createShareNode.mock.calls[0][0].xContributorToken).toBe('tok-away')
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('a report from the embedded view is a guest\'s report to the share\'s instance', async () => {
    const w = mountPage()
    await flushPromises()
    expect(vi.mocked(useReportOffered).mock.calls.map(([base]) => base)).toContain(AWAY)
    await chooseHeaderEntry(w.findComponent(LevelView), 'report')
    const request = vi.mocked(openReport).mock.calls[0][0] as ReportRequest
    expect(request.access).toMatchObject({ mode: 'guest', shareId: SHARE_ID, apiBase: AWAY })
    w.unmount()
  })
})
