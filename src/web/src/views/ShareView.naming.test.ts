// @vitest-environment happy-dom
// The share page's ONE naming flow (inbox #401 S2): the shell builds the guest's access and owns `requestGuest` — a
// READ_WRITE guest names itself in core's `GuestNaming` dialog, whichever view first writes (here the folder level's
// create card), under the SHARE ROOT's key; the shell then says whose session this device holds, and its "Not you?"
// asks first and forgets it. The app's real registry, the UI layer and vue-router; the resolve and the identity call are
// stubbed, the crypto is real.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../api/client', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/memories/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../features/depot/api', async (orig) => (await import('../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})

import ShareView from './ShareView.vue'
import { resolvePublicShare, fetchShareTerms, type PublicShareHead } from '../core/sharing/resolvePublicShare'
import { FOLDER_KIND } from '../core/node/kind'
import { sealName, wrapContentKey } from '../core/node/tree'
import { identityNameContext, loadGuestSession, saveGuestSession } from '../core/sharing/guestIdentity'
import { ShareRole, type PublicShare } from '../api/generated/sharing'
import { publicSharesApi } from '../api/client'
import { publicShareMediaApi } from '../features/memories/api'
import { publicShareFilesApi } from '../features/depot/api'
import { stubApi } from '../test/apiMock'
import { realI18n, t } from '../test/i18n'
import { chooseNew, hasNewMenu } from '../test/newMenu'
import { mountConfirmDialog } from '../test/confirmDialog'
import { authenticatedCalls } from '../test/guestMode'
import { confirmRequest, settleConfirm } from '../shared/state/useConfirm'
import { OPERATOR_SECTION } from '../test/models'

const SCOPE_ID = '99999999-9999-4999-8999-999999999999'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_n'
const ROOT = '6e5a0000-0000-4000-8000-000000000700'
const SUB = '6e5a0000-0000-4000-8000-000000000701'
const K_SHARE = v1.randomBytes(32)
const ROOT_KEY = v1.randomBytes(32)
const HASH = `#k=${b64url(K_SHARE)}`

function folderHead(role: ShareRole): PublicShareHead {
  const address = { scopeId: SCOPE_ID, nodeId: SUB, epoch: 0, placementRevision: 0 }
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, ROOT_KEY)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        { id: SUB, parentId: ROOT, kind: FOLDER_KIND, epoch: 0, placementRevision: 0, encryptedName: sealName(ROOT_KEY, address, 'Briefe'), wrappedContentKey: wrapContentKey(ROOT_KEY, address, v1.randomBytes(32)) },
      ],
    } as PublicShare,
  }
}

// The dialog teleports; rendered in place so the test reads it (the confirmation is the app's real one).
const stubs = { VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' } }

let router: Router
async function mountPage(role: ShareRole) {
  vi.mocked(resolvePublicShare).mockResolvedValue(folderHead(role))
  const w = mount(ShareView, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  await vi.waitFor(() => expect(w.find('.veyq-node-header').exists()).toBe(true)) // the folder body loads lazily
  return w
}
type Page = Awaited<ReturnType<typeof mountPage>>
const createFolder = (w: Page) => chooseNew(w, t('node.folderCreate'))
const identityLine = (w: Page) => w.find('.guest-naming-identity')

beforeEach(async () => {
  if (confirmRequest.value) settleConfirm(false)
  localStorage.clear()
  authenticatedCalls.length = 0
  vi.mocked(fetchShareTerms).mockReset().mockResolvedValue({ tenantId: 't-1', termsRevision: 0, operatorSection: OPERATOR_SECTION, terms: {} })
  stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [], more: false } })
  stubApi(publicShareFilesApi, { listShareFiles: { items: [] } })
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:shareId', component: ShareView }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

describe("the share page's one naming flow", () => {
  it("a READ_WRITE guest's first write asks its name in the shell's dialog, under the share ROOT's key; the line then says who", async () => {
    const api = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'x', contributorToken: 'tok-1' } })
    api.createShareIdentity.mockImplementation(async (r) => ({ identityId: r.createShareIdentityRequest.identityId, contributorToken: 'tok-1' }))
    const w = await mountPage(ShareRole.ReadWrite)
    expect(identityLine(w).exists(), 'nobody named yet').toBe(false)

    await createFolder(w)
    await flushPromises()
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.guest.nameTitle'))
    expect(w.find('.dialog-stub').text()).toContain(t('share.guest.nameHint'))
    await w.find('.dialog-stub .guest-naming-input').setValue('  Oma ')
    await w.find('.dialog-stub form').trigger('submit')
    await flushPromises()

    const req = api.createShareIdentity.mock.calls[0][0].createShareIdentityRequest
    const ctx = identityNameContext(SCOPE_ID, SHARE_ID, req.identityId)
    expect(new TextDecoder().decode(v1.openField(ctx, ROOT_KEY, fromB64std(req.sealedDisplayName))), "under the share root's key").toBe('Oma')
    expect(loadGuestSession(SHARE_ID)).toEqual({ identityId: req.identityId, contributorToken: 'tok-1', displayName: 'Oma' })
    expect(w.find('.dialog-stub .header').text(), 'named, the view goes on: the new folder').toBe(t('share.folder.newFolderTitle'))
    expect(identityLine(w).text()).toContain(t('share.guest.as', { name: 'Oma' }))
    expect(authenticatedCalls).toEqual([])
  })

  it('a returning guest is not asked; the line says who it is', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0', displayName: 'Anna' })
    const w = await mountPage(ShareRole.ReadWrite)
    expect(identityLine(w).text()).toContain(t('share.guest.as', { name: 'Anna' }))
    await createFolder(w)
    await flushPromises()
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.folder.newFolderTitle'))
  })

  it('a visitor who closes the name dialog is not named, and the view opens nothing', async () => {
    const w = await mountPage(ShareRole.ReadWrite)
    await createFolder(w)
    await flushPromises()
    await w.findAll('.dialog-stub button').find((b) => b.text() === t('common.cancel'))!.trigger('click')
    await flushPromises()
    expect(w.find('.dialog-stub').exists()).toBe(false)
    expect(loadGuestSession(SHARE_ID)).toBeNull()
  })

  it('"Not you?" asks first; declined nothing changes, accepted the device forgets the session and the next write asks again', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0', displayName: 'Anna' })
    const w = await mountPage(ShareRole.ReadWrite)
    const dialog = mountConfirmDialog()
    await identityLine(w).find('button.guest-naming-not-you').trigger('click')
    await flushPromises()
    expect(dialog.text()).toContain(t('share.guest.notYouConfirm', { name: 'Anna' }))
    dialog.click(t('common.cancel'))
    await flushPromises()
    expect(loadGuestSession(SHARE_ID)).not.toBeNull()

    await identityLine(w).find('button.guest-naming-not-you').trigger('click')
    await flushPromises()
    dialog.click(t('share.guest.notYouAccept'))
    await flushPromises()
    expect(loadGuestSession(SHARE_ID)).toBeNull()
    expect(identityLine(w).exists()).toBe(false)
    expect(document.body.textContent).not.toContain(t('share.guest.nameTitle'))
    await createFolder(w)
    await flushPromises()
    // (the real confirmation dialog is mounted beside the page, so the layer's dialog renders for real here: teleported)
    expect(document.body.textContent, 'asked afresh').toContain(t('share.guest.nameTitle'))
  })

  it('a READ link has no line and no write to name anyone for', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0', displayName: 'Anna' })
    const w = await mountPage(ShareRole.Read)
    expect(identityLine(w).exists()).toBe(false)
    expect(hasNewMenu(w)).toBe(false)
  })
})
