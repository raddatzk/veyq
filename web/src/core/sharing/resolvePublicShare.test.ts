// @vitest-environment happy-dom
// The kind-independent half of resolving a public share. What matters here is what it does NOT need: it reads the root
// node's kind and nothing else about the shared object, and it opens no seal the application owns. The Memories half
// (K_mem, chapters, media, drop key) is `openSharedMemory`, covered with the full flow in features/memory/share.test.ts.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../../api/client', () => ({ publicSharesApi: vi.fn() }))

import {
  resolvePublicShare,
  pickShareTerms,
  fetchShareTerms,
  derivePasswordToken,
  acceptedTermsVersion,
  recordTermsAccepted,
  pickLocaleText,
  openNoteMap,
  SHARE_MEMORY_NAME,
  SHARE_NOTE,
  ShareUnavailableError,
  SharePasswordRequiredError,
  SharePasswordIncorrectError,
} from './resolvePublicShare'
import { publicSharesApi } from '../../api/client'
import { fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import { ShareRole, type PublicShare, type SharingApi } from '../../api/generated/sharing'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memory/kinds'

const K_SHARE = v1.randomBytes(32)
const FRAGMENT = b64url(K_SHARE)
const seal = (purpose: typeof SHARE_MEMORY_NAME | typeof SHARE_NOTE, text: string) => b64std(v1.seal(purpose, K_SHARE, v1.utf8(text)))

function serve(over: Partial<PublicShare>) {
  const share = {
    role: ShareRole.Read, passwordRequired: false, scopeId: 'scope-1', sealedName: seal(SHARE_MEMORY_NAME, 'Rom 2026'),
    nodes: [], media: [], ...over,
  } as PublicShare
  fakeApi(publicSharesApi, { resolveShare: async () => share })
  return share
}

beforeEach(() => localStorage.clear())

describe('resolvePublicShare: the head', () => {
  it('opens the name and the per-locale note under the fragment key and reports the root node\'s kind', async () => {
    const share = serve({
      sealedNote: seal(SHARE_NOTE, JSON.stringify({ de: 'Hallo', en: 'Hello' })),
      rootKind: MEMORY_KIND,
      nodes: [
        { id: 'chapter', parentId: 'root', kind: CHAPTER_KIND, epoch: 0 },
        { id: 'root', parentId: null, kind: MEMORY_KIND, epoch: 0 },
      ] as PublicShare['nodes'],
    })

    const head = await resolvePublicShare('s1', FRAGMENT)

    expect(head).toMatchObject({ shareId: 's1', scopeId: 'scope-1', name: 'Rom 2026', role: ShareRole.Read, rootKind: MEMORY_KIND })
    expect(pickLocaleText(head.noteByLocale, 'de')).toBe('Hallo')
    expect(head.share).toBe(share) // the application opens its own seals from the unlocked response
    expect(head.kShare).toEqual(K_SHARE)
  })

  it('reports whatever kind the server names — it assumes none (live control for the Memory case above)', async () => {
    serve({ rootKind: 'EXPENSE', nodes: [{ id: 'r', parentId: null, kind: 'EXPENSE', epoch: 0 }] as PublicShare['nodes'] })
    expect((await resolvePublicShare('s1', FRAGMENT)).rootKind).toBe('EXPENSE')
  })

  it('takes the kind from rootKind, never from the served nodes', async () => {
    // The two disagree on purpose: the head reads the server's named fact, not a derivation over `nodes`.
    serve({ rootKind: MEMORY_KIND, nodes: [{ id: 'r', parentId: null, kind: CHAPTER_KIND, epoch: 0 }] as PublicShare['nodes'] })
    expect((await resolvePublicShare('s1', FRAGMENT)).rootKind).toBe(MEMORY_KIND)
    // …and a response that names no kind has none, whatever root it serves (a trashed root serves neither).
    serve({ nodes: [{ id: 'r', parentId: null, kind: MEMORY_KIND, epoch: 0 }] as PublicShare['nodes'] })
    expect((await resolvePublicShare('s1', FRAGMENT)).rootKind).toBeNull()
  })

  it('needs no wrap and no key material of the application: a response without them still yields a head', async () => {
    serve({ wrap: undefined, rootKind: MEMORY_KIND, nodes: [{ id: 'r', parentId: null, kind: MEMORY_KIND, epoch: 0, wrappedContentKey: 'not even base64!' }] as PublicShare['nodes'] })
    await expect(resolvePublicShare('s1', FRAGMENT)).resolves.toMatchObject({ rootKind: MEMORY_KIND })
  })

  it('reports the root kind of a WRITE_ONLY share, though it is served no node', async () => {
    serve({ role: ShareRole.WriteOnly, nodeId: 'drop-node', rootKind: MEMORY_KIND, nodes: [] })
    expect((await resolvePublicShare('s1', FRAGMENT)).rootKind).toBe(MEMORY_KIND)
  })

  it('refuses a malformed or short fragment key before asking the server anything', async () => {
    const resolve = vi.fn<SharingApi['resolveShare']>()
    fakeApi(publicSharesApi, { resolveShare: resolve })
    await expect(resolvePublicShare('s1', '')).rejects.toBeInstanceOf(ShareUnavailableError)
    await expect(resolvePublicShare('s1', b64url(v1.randomBytes(16)))).rejects.toBeInstanceOf(ShareUnavailableError)
    expect(resolve).not.toHaveBeenCalled()
  })

  it('a fragment key that does not open the name is unavailable, never a wrong name', async () => {
    serve({})
    await expect(resolvePublicShare('s1', b64url(v1.randomBytes(32)))).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('maps 404 to unavailable, 403 to a wrong password, and the locked shell to the password challenge', async () => {
    fakeApi(publicSharesApi, { resolveShare: async () => { throw httpError(404) } })
    await expect(resolvePublicShare('s1', FRAGMENT)).rejects.toBeInstanceOf(ShareUnavailableError)
    fakeApi(publicSharesApi, { resolveShare: async () => { throw httpError(403) } })
    await expect(resolvePublicShare('s1', FRAGMENT)).rejects.toBeInstanceOf(SharePasswordIncorrectError)
    serve({ passwordRequired: true, sealedName: undefined, scopeId: undefined, pwSalt: 'salt' })
    await expect(resolvePublicShare('s1', FRAGMENT)).rejects.toMatchObject({ pwSalt: 'salt' })
    await expect(resolvePublicShare('s1', FRAGMENT)).rejects.toBeInstanceOf(SharePasswordRequiredError)
  })

  it('an unlocked response without a scope is unavailable (every seal it serves is bound to one)', async () => {
    serve({ scopeId: undefined })
    await expect(resolvePublicShare('s1', FRAGMENT)).rejects.toBeInstanceOf(ShareUnavailableError)
  })
})

describe('openNoteMap', () => {
  it('is {} for a seal that does not open or is not a JSON object', () => {
    expect(openNoteMap(K_SHARE, 'AAAA')).toEqual({})
    expect(openNoteMap(K_SHARE, seal(SHARE_NOTE, '["a"]'))).toEqual({})
  })
})

describe('terms helpers', () => {
  it('records and reads the accepted version per tenant, and 0 for a tenant never seen', () => {
    expect(acceptedTermsVersion('t-1')).toBe(0)
    recordTermsAccepted('t-1', 3)
    expect(acceptedTermsVersion('t-1')).toBe(3)
    expect(acceptedTermsVersion('t-2')).toBe(0)
  })

  it('fetches the terms of the share by its id, with no credential (terms are public)', async () => {
    const getShareTerms = vi.fn<SharingApi['getShareTerms']>().mockResolvedValue({ tenantId: 't', termsVersion: 2, terms: { en: 'x' } })
    fakeApi(publicSharesApi, { getShareTerms })
    await expect(fetchShareTerms('s1')).resolves.toMatchObject({ termsVersion: 2 })
    expect(getShareTerms).toHaveBeenCalledWith({ shareId: 's1' })
  })

  it('derives the guest password token deterministically from password and salt', async () => {
    const salt = b64std(v1.randomBytes(16))
    expect(await derivePasswordToken('pw', salt)).toBe(await derivePasswordToken('pw', salt))
    expect(await derivePasswordToken('pw', salt)).not.toBe(await derivePasswordToken('other', salt))
  }, 60_000)

  it('picks the viewer\'s locale, falling back to en', () => {
    const terms = { tenantId: 't', termsVersion: 1, terms: { de: 'Regeln', en: 'Rules' } }
    expect(pickShareTerms(terms, 'de')).toBe('Regeln')
    expect(pickShareTerms(terms, 'fr')).toBe('Rules')
    expect(pickShareTerms({ tenantId: 't', termsVersion: 0, terms: undefined }, 'de')).toBe('')
  })
})
