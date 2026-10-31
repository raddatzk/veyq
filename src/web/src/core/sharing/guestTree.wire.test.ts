// A guest's tree operations on the wire (uc.sharing.share-contributor:folder-guest-tree-operations, ADR 0080 decisions
// 2, 3, 6 and 10): recorded at `fetch` — the JSON and headers the real generated client sends, no mock of the API
// client. A create seals under the PARENT's key (never the root's when the parent is below it, never `K_SC`) at the
// parent's served epoch and revision 0; a placement re-wraps under the DESTINATION's key at served+1 and states no
// epoch; one `stale_placement` retry; a readable sibling's name refuses before sending; every refusal maps by its code.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { openContentKey, openName, sealName, wrapContentKey, PlacementConflictError } from '../node/tree'
import { FOLDER_KIND } from '../node/kind'
import { fileNameSlug, slugify } from '../../shared/naming'
import { registry } from '../applications/registry'
import { SHARE_KEY } from './shareTree'
import {
  createShareNodeAsGuest,
  placeShareNodeAsGuest,
  openGuestTreeParts,
  guestTreeErrorMessageKey,
  GuestTreeError,
  GUEST_TREE_CODES,
  type GuestTreeContext,
  type GuestTreeParts,
} from './guestTree'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type ShareNode } from '../../api/generated/sharing'
import { t } from '../../test/i18n'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'

const SCOPE_ID = '22222222-2222-4222-8222-222222222222'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0' // a 43-char base64url id, as `newShareId` mints
const ROOT = '6e5a0000-0000-4000-8000-0000000000a0'
const LEFT = '6e5a0000-0000-4000-8000-0000000000a1'
const RIGHT = '6e5a0000-0000-4000-8000-0000000000a2'
const ITEM = '6e5a0000-0000-4000-8000-0000000000a3'
const TOKEN = 'contributor-token-1'
const PW = 'pw-token-1'
// A kind no core rule names; the client never reads what a node is.
const ITEM_KIND = 'NOTE'

afterEach(() => vi.unstubAllGlobals())

const kSC = v1.randomBytes(32) // the scope key: a guest never holds it; here only to prove nothing is sealed under it
const kShare = v1.randomBytes(32)
const kRoot = v1.randomBytes(32)
const kLeft = v1.randomBytes(32)
const kRight = v1.randomBytes(32)
const kItem = v1.randomBytes(32)

/** A served child row: its name and key wrap sealed under its parent's key, at its epoch and placement revision. */
function row(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, epoch: number, placementRevision: number, kind = FOLDER_KIND): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch, placementRevision }
  return { blockedByOperator: false, id, parentId, kind, epoch, placementRevision, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}

// The root at epoch 5, its two subfolders at epochs 3 and 1, an item in the left one (served at revision 2).
function servedNodes(itemRevision = 2): ShareNode[] {
  return [
    { blockedByOperator: false, id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 5, placementRevision: 0 },
    row(LEFT, ROOT, kRoot, kLeft, 'Briefe', 3, 1),
    row(RIGHT, ROOT, kRoot, kRight, 'Fotos', 1, 0),
    row(ITEM, LEFT, kLeft, kItem, 'Tagebuch', 4, itemRevision, ITEM_KIND),
  ]
}

function head(nodes: ShareNode[] = servedNodes()): PublicShareHead {
  return {
    shareId: SHARE_ID, scopeId: SCOPE_ID, kShare, name: 'Familie', noteByLocale: {}, role: ShareRole.ReadWrite, rootKind: FOLDER_KIND,
    share: { role: ShareRole.ReadWrite, wrap: b64std(v1.seal(SHARE_KEY, kShare, kRoot)), nodes } as PublicShareHead['share'],
  }
}

function context(reresolve: () => Promise<GuestTreeParts> = () => Promise.reject(new Error('no re-resolve expected'))): GuestTreeContext {
  return { ...openGuestTreeParts(head()), shareId: SHARE_ID, scopeId: SCOPE_ID, pwToken: PW, contributorToken: TOKEN, reresolve: vi.fn(reresolve) }
}

type Sent = { url: string; method: string; headers: Headers; json: Record<string, unknown> }

/** Record every request; answer each from `answers` in turn (a function of the request). */
function recordFetch(...answers: ((s: Sent) => Response)[]) {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const s = { url: String(input), method: init?.method ?? 'GET', headers: new Headers(init?.headers), json: JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown> }
    sent.push(s)
    const answer = answers[sent.length - 1] ?? answers[answers.length - 1]
    return answer(s)
  }))
  return sent
}
const created = (s: Sent) => new Response(JSON.stringify({ nodeId: s.json.nodeId }), { status: 201, headers: { 'Content-Type': 'application/json' } })
const noContent = () => new Response(null, { status: 204 })
const refused = (status: number, code: string) => () => new Response(JSON.stringify({ code }), { status, headers: { 'Content-Type': 'application/json' } })
const bytes = (k: Uint8Array) => Array.from(k)

describe('a guest creates a node', () => {
  it('seals the new key, name and slug under the PARENT\'s key at the parent\'s served epoch and revision 0', async () => {
    const sent = recordFetch(created)
    const ctx = context()
    const { nodeId, contentKey } = await createShareNodeAsGuest(ctx, { parentId: LEFT, kind: FOLDER_KIND, name: 'Urlaub 1998' })

    expect(sent).toHaveLength(1)
    const { url, method, headers, json } = sent[0]
    expect(method).toBe('POST')
    expect(url).toContain(`/api/shares/${SHARE_ID}/nodes`)
    expect(headers.get('X-Contributor-Token')).toBe(TOKEN)
    expect(headers.get('X-Share-Password')).toBe(PW)
    expect(Object.keys(json).sort()).toEqual(['encryptedName', 'epoch', 'kind', 'nodeId', 'parentEpoch', 'parentId', 'slugToken', 'wrappedContentKey'])
    expect(json).toMatchObject({ nodeId, parentId: LEFT, kind: FOLDER_KIND, epoch: 3, parentEpoch: 3 }) // the parent's served epoch, not the root's 5
    expect(JSON.stringify(json)).not.toContain('Urlaub')

    const wrap = { id: nodeId, epoch: 3, placementRevision: 0, wrappedContentKey: json.wrappedContentKey as string }
    expect(bytes(openContentKey(SCOPE_ID, kLeft, wrap)), 'the wrap opens under the parent key to the new key').toEqual(bytes(contentKey))
    for (const other of [kRoot, kSC]) expect(() => openContentKey(SCOPE_ID, other, wrap), 'not under the root key or K_SC').toThrow(v1.OpenRefused)
    expect(() => openContentKey(SCOPE_ID, kLeft, { ...wrap, placementRevision: 1 }), 'bound to revision 0').toThrow(v1.OpenRefused)
    const address = { scopeId: SCOPE_ID, nodeId, epoch: 3, placementRevision: 0 }
    expect(openName(kLeft, address, json.encryptedName as string), 'the name opens under the parent key').toBe('Urlaub 1998')
    expect(json.slugToken, 'the slug token is keyed by the parent key in the parent\'s slot').toBe(b64std(v1.slugToken(kLeft, LEFT, slugify('Urlaub 1998'))))
  })

  it('a create in the root seals under the root key at the root\'s served epoch', async () => {
    const sent = recordFetch(created)
    const { nodeId } = await createShareNodeAsGuest(context(), { parentId: ROOT, kind: FOLDER_KIND, name: 'Neu' })
    expect(sent[0].json.epoch).toBe(5)
    expect(openName(kRoot, { scopeId: SCOPE_ID, nodeId, epoch: 5, placementRevision: 0 }, sent[0].json.encryptedName as string)).toBe('Neu')
  })

  it('refuses a parent whose key the guest does not hold, sending nothing', async () => {
    const sent = recordFetch(created)
    await expect(createShareNodeAsGuest(context(), { parentId: crypto.randomUUID(), kind: FOLDER_KIND, name: 'X' })).rejects.toThrow(v1.OpenRefused)
    expect(sent).toHaveLength(0)
  })

  it('refuses a name a readable sibling already slugs to, naming that sibling, before sending', async () => {
    const sent = recordFetch(created)
    const err = await createShareNodeAsGuest(context(), { parentId: ROOT, kind: FOLDER_KIND, name: 'FOTOS!' }).then(() => null, (e: unknown) => e)
    expect(err).toBeInstanceOf(GuestTreeError)
    expect((err as GuestTreeError).code).toBe('slug_taken')
    expect((err as GuestTreeError).siblingName).toBe('Fotos')
    expect((err as Error).message).toContain('Fotos')
    expect(t(guestTreeErrorMessageKey(err).key, guestTreeErrorMessageKey(err).params)).toContain('Fotos')
    expect(sent).toHaveLength(0)
  })

  it('maps the server\'s refusal by its code into a GuestTreeError', async () => {
    recordFetch(refused(422, 'illegal_scope'))
    const err = await createShareNodeAsGuest(context(), { parentId: LEFT, kind: FOLDER_KIND, name: 'Y' }).then(() => null, (e: unknown) => e)
    expect(err).toBeInstanceOf(GuestTreeError)
    expect((err as GuestTreeError).code).toBe('illegal_scope')
  })
})

describe('a guest places a node', () => {
  it('re-wraps under the DESTINATION key at served+1, re-seals name and slug there, and states only the destination\'s epoch', async () => {
    const sent = recordFetch(noContent)
    await placeShareNodeAsGuest(context(), { nodeId: ITEM, newParentId: RIGHT })

    expect(sent).toHaveLength(1)
    const { url, method, headers, json } = sent[0]
    expect(method).toBe('PATCH')
    expect(url).toContain(`/api/shares/${SHARE_ID}/nodes/${ITEM}`)
    expect(headers.get('X-Contributor-Token')).toBe(TOKEN)
    expect(Object.keys(json).sort(), 'no node epoch on the wire').toEqual(['encryptedName', 'parentEpoch', 'parentId', 'placementRevision', 'slugToken', 'wrappedContentKey'])
    expect(json).toMatchObject({ parentId: RIGHT, parentEpoch: 1, placementRevision: 2 }) // the revision it was built on; RIGHT's served epoch

    const wrap = { id: ITEM, epoch: 4, placementRevision: 3, wrappedContentKey: json.wrappedContentKey as string }
    expect(bytes(openContentKey(SCOPE_ID, kRight, wrap)), 'opens under the destination key at served+1').toEqual(bytes(kItem))
    expect(() => openContentKey(SCOPE_ID, kRight, { ...wrap, placementRevision: 2 }), 'not at the served revision').toThrow(v1.OpenRefused)
    expect(() => openContentKey(SCOPE_ID, kLeft, wrap), 'not under the source parent').toThrow(v1.OpenRefused)
    const address = { scopeId: SCOPE_ID, nodeId: ITEM, epoch: 4, placementRevision: 3 }
    expect(openName(kRight, address, json.encryptedName as string), 'the existing name, under the destination key').toBe('Tagebuch')
    expect(json.slugToken).toBe(b64std(v1.slugToken(kRight, RIGHT, slugify('Tagebuch'))))
  })

  it('a rename is a placement to the same parent under the new name; the root is a destination', async () => {
    const sent = recordFetch(noContent, noContent)
    await placeShareNodeAsGuest(context(), { nodeId: ITEM, newParentId: LEFT, newName: 'Tagebuch 2' })
    expect(openName(kLeft, { scopeId: SCOPE_ID, nodeId: ITEM, epoch: 4, placementRevision: 3 }, sent[0].json.encryptedName as string)).toBe('Tagebuch 2')
    await placeShareNodeAsGuest(context(), { nodeId: ITEM, newParentId: ROOT })
    expect(bytes(openContentKey(SCOPE_ID, kRoot, { id: ITEM, epoch: 4, placementRevision: 3, wrappedContentKey: sent[1].json.wrappedContentKey as string }))).toEqual(bytes(kItem))
  })

  it('never places the share root, and refuses a node served without its placement revision', async () => {
    const sent = recordFetch(noContent)
    const err = await placeShareNodeAsGuest(context(), { nodeId: ROOT, newParentId: LEFT }).then(() => null, (e: unknown) => e)
    expect((err as GuestTreeError).code).toBe('illegal_structure')
    const ctx = context()
    ctx.nodes.set(ITEM, { ...ctx.nodes.get(ITEM)!, placementRevision: undefined })
    await expect(placeShareNodeAsGuest(ctx, { nodeId: ITEM, newParentId: RIGHT })).rejects.toThrow(v1.OpenRefused)
    expect(sent).toHaveLength(0)
  })

  // Inbox #350: the pre-check compares what the server compares — each sibling's slug folded by ITS kind — and the token
  // is folded by the node's kind. LEFT holds a folder 'Day1 md' (slugify: day1-md) and a leaf 'foto.jpg' of a kind that
  // registers the file folding (fileNameSlug: foto.jpg).
  describe('folds by kind (inbox #350)', () => {
    const LEAF = 'LEAFTEST'
    registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: LEAF, icon: 'file', shareable: false, slug: fileNameSlug })
    const DIR = '6e5a0000-0000-4000-8000-0000000000b1'
    const FILE = '6e5a0000-0000-4000-8000-0000000000b2'
    const byKind = () => {
      const ctx = context()
      const extra = [row(DIR, LEFT, kLeft, v1.randomBytes(32), 'Day1 md', 3, 0), row(FILE, LEFT, kLeft, v1.randomBytes(32), 'foto.jpg', 3, 0, LEAF)]
      for (const n of extra) {
        ctx.nodes.set(n.id, n)
        ctx.names.set(n.id, n.id === DIR ? 'Day1 md' : 'foto.jpg')
        ctx.keys.set(n.id, v1.randomBytes(32))
      }
      return ctx
    }
    const failed = (p: Promise<unknown>) => p.then(() => null, (e: unknown) => e as GuestTreeError)

    it('a leaf collides case-insensitively with a leaf, and not with a folder whose slugify slug matches', async () => {
      const sent = recordFetch(created)
      expect((await failed(createShareNodeAsGuest(byKind(), { parentId: LEFT, kind: LEAF, name: 'Foto.JPG' })))?.siblingName).toBe('foto.jpg')
      expect(sent).toHaveLength(0)
      await createShareNodeAsGuest(byKind(), { parentId: LEFT, kind: LEAF, name: 'Day1.md' })
      expect(sent).toHaveLength(1)
      expect(sent[0].json.slugToken).toBe(b64std(v1.slugToken(kLeft, LEFT, 'day1.md')))
    })

    it('a folder folds with slugify: Day1.md collides with the folder Day1 md, foto.jpg not with the leaf', async () => {
      const sent = recordFetch(created)
      expect((await failed(createShareNodeAsGuest(byKind(), { parentId: LEFT, kind: FOLDER_KIND, name: 'Day1.md' })))?.siblingName).toBe('Day1 md')
      expect(sent).toHaveLength(0)
      await createShareNodeAsGuest(byKind(), { parentId: LEFT, kind: FOLDER_KIND, name: 'foto.jpg' })
      expect(sent[0].json.slugToken).toBe(b64std(v1.slugToken(kLeft, LEFT, 'foto-jpg')))
    })

    it('a placement folds the moved node by its own served kind, in the pre-check and the token', async () => {
      const sent = recordFetch(noContent)
      await placeShareNodeAsGuest(byKind(), { nodeId: FILE, newParentId: LEFT, newName: 'Day1 MD.txt' })
      expect(sent[0].json.slugToken).toBe(b64std(v1.slugToken(kLeft, LEFT, 'day1 md.txt')))
      expect((await failed(placeShareNodeAsGuest(byKind(), { nodeId: DIR, newParentId: LEFT, newName: 'FOTO JPG' }))), 'a folder renamed: slugify').toBeNull()
      expect(sent[1].json.slugToken).toBe(b64std(v1.slugToken(kLeft, LEFT, 'foto-jpg')))
    })
  })

  it('refuses a move onto a readable sibling\'s name at the destination, naming it; its own name never collides', async () => {
    const sent = recordFetch(noContent)
    const err = await placeShareNodeAsGuest(context(), { nodeId: LEFT, newParentId: ROOT, newName: 'fotos' }).then(() => null, (e: unknown) => e)
    expect((err as GuestTreeError).siblingName).toBe('Fotos')
    expect(sent).toHaveLength(0)
    await placeShareNodeAsGuest(context(), { nodeId: LEFT, newParentId: ROOT, newName: 'BRIEFE' }) // a rename onto itself
    expect(sent).toHaveLength(1)
  })

  it('on 409 stale_placement re-resolves once and rebuilds on the node as it now is', async () => {
    const sent = recordFetch(refused(409, 'stale_placement'), noContent)
    const ctx = context(async () => openGuestTreeParts(head(servedNodes(7))))
    await placeShareNodeAsGuest(ctx, { nodeId: ITEM, newParentId: RIGHT })
    expect(ctx.reresolve).toHaveBeenCalledTimes(1)
    expect(sent).toHaveLength(2)
    expect(sent[1].json.placementRevision).toBe(7)
    expect(bytes(openContentKey(SCOPE_ID, kRight, { id: ITEM, epoch: 4, placementRevision: 8, wrappedContentKey: sent[1].json.wrappedContentKey as string }))).toEqual(bytes(kItem))
  })

  it('two stale_placement answers end in PlacementConflictError', async () => {
    const sent = recordFetch(refused(409, 'stale_placement'))
    const ctx = context(async () => openGuestTreeParts(head(servedNodes(7))))
    const err = await placeShareNodeAsGuest(ctx, { nodeId: ITEM, newParentId: RIGHT }).then(() => null, (e: unknown) => e)
    expect(err).toBeInstanceOf(PlacementConflictError)
    expect(sent).toHaveLength(2)
    expect(guestTreeErrorMessageKey(err).key).toBe('share.folder.placementConflict')
  })
})

describe('a refusal maps by its code, whatever the status', () => {
  const expected: Record<(typeof GUEST_TREE_CODES)[number], string> = {
    slug_taken: 'share.folder.nameTakenHere',
    stale_epoch: 'share.folder.placementConflict',
    node_rekey_pending: 'share.folder.rekeyPending',
    id_taken: 'share.folder.createFailed',
    illegal_structure: 'share.folder.notAllowed',
    illegal_scope: 'share.folder.notAllowed',
    share_not_contributable: 'share.folder.notContributable',
    share_password_incorrect: 'share.folder.gone',
    share_not_found: 'share.folder.gone',
    node_not_found: 'share.folder.gone',
    stale_placement: 'share.folder.placementConflict',
    rate_limited: 'share.folder.rateLimited',
    not_a_placeholder: 'share.folder.createFailed', // an abandon's, which no guest is shown
  }
  // Each code on a status that does NOT carry it: a mapping by status would answer otherwise.
  it.each(Object.entries(expected))('%s → %s', async (code, key) => {
    recordFetch(refused(code === 'illegal_scope' ? 409 : 422, code))
    const err = await createShareNodeAsGuest(context(), { parentId: LEFT, kind: FOLDER_KIND, name: 'Z' }).then(() => null, (e: unknown) => e)
    expect((err as GuestTreeError).code).toBe(code)
    expect(guestTreeErrorMessageKey(err).key).toBe(key)
    expect(t(key, { name: 'x' })).not.toBe(key) // the key is in the real catalog
  })

  it('a failure with no known code stays itself and shows the generic message', async () => {
    recordFetch(refused(409, 'something_else'))
    const err = await createShareNodeAsGuest(context(), { parentId: LEFT, kind: FOLDER_KIND, name: 'Z' }).then(() => null, (e: unknown) => e)
    expect(err).not.toBeInstanceOf(GuestTreeError)
    expect(guestTreeErrorMessageKey(err).key).toBe('share.folder.createFailed')
  })
})

describe('the opened subtree', () => {
  it('holds the root key from the wrap and each descendant\'s under its parent\'s, the root named nowhere', () => {
    const parts = openGuestTreeParts(head())
    expect(parts.rootId).toBe(ROOT)
    expect(bytes(parts.keys.get(ROOT)!)).toEqual(bytes(kRoot))
    expect(bytes(parts.keys.get(ITEM)!)).toEqual(bytes(kItem))
    expect(parts.names.get(ITEM)).toBe('Tagebuch')
    expect(parts.names.has(ROOT)).toBe(false)
  })
})

it('wire fields are standard base64', async () => {
  const sent = recordFetch(created)
  await createShareNodeAsGuest(context(), { parentId: LEFT, kind: FOLDER_KIND, name: 'B64' })
  for (const f of ['encryptedName', 'slugToken', 'wrappedContentKey']) {
    expect(sent[0].json[f]).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(fromB64std(sent[0].json[f] as string).length).toBeGreaterThan(0)
  }
})
