// uc.sharing.share-public-viewer (web) — the public viewer share data layer (cr-0100, ADR 0029). Operator-blind:
// a member turns ONE Memory of their scope into an anonymous, revocable link `/s/{shareId}#k=<K_share>`.
// The share adds an INDEPENDENT second wrap of that Memory's `K_mem` — sealed under `K_share` (ADR 0012) —
// whose secret `K_share` rides the URL **fragment** (ADR 0015) and never reaches the server. The Memory name is
// re-sealed under `K_share` so a viewer with only the fragment key can show the title without `K_SC`.
//
// Two sides live here:
//  - **curator** (authed): mint and re-issue a share of a Memory — composed on the platform's share management
//    (`core/sharing/manageShares.ts`), which also lists, revokes and edits it; this file adds the drop key.
//  - **viewer** (anonymous, no session): resolve a share, unwrap `K_mem` from the fragment key, derive each
//    chapter's `K_mem` down the subtree and open each chapter's title under its parent's key (ADR 0080 Decision 2 —
//    a guest of a Memory reads its chapter titles), and open blobs via the gated share-scoped presign endpoint.
//
// A WRITE_ONLY share is the exception to the second wrap (ADR 0031 point 7): its guest is the one party the link
// exists to keep out, so it gets no `K_mem` at all — only the Memory's drop PUBLIC key under `K_share`. The guest
// seals under an identity key of its own, wrapped to that drop key; `./mediaKey.ts` owns that chain.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { contentApi, publicSharesApi } from '../../api/client'
import { publicShareMediaApi } from './api'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { resolvePublicShare, ShareUnavailableError, type PublicShareHead } from '../../core/sharing/resolvePublicShare'
import {
  carriedSettings,
  mintShare,
  remintShare,
  type LocaleMap,
  type MintedShare,
  type ReissuableShare,
  type ReissueSettings,
  type WriteOnlyMint,
} from '../../core/sharing/manageShares'
import { openShareRoot, walkSubtree } from '../../core/sharing/shareTree'
import { createGuestIdentity, identityNameContext, type GuestIdentity } from '../../core/sharing/guestIdentity'
import { guestReserve, guestWindow, type GuestUploadTarget } from '../../core/sharing/guestBlob'
import { dropKeysForShare, mediaKey, mintUploaderKey, openDropPublicKey, uploaderOriginOf } from './mediaKey'
import type { EpochKeys } from '../spaces/epochs'
import {
  gridRef,
  lightboxRef,
  downloadRef,
  openBlobFromUrls,
  makeChunkProvider,
  uploadMediaVia,
  createBrowserDeriver,
  type BlobRef,
  type MediaItem,
  type MediaUploadTransport,
  type AddMediaSource,
  type AddMediaOptions,
  type MediaDeriver,
} from './media'
import type { ChunkProvider } from '../../sw/protocol'
import { ShareRole } from '../../api/generated/sharing'
import type { ShareMedia, ShareMediaListing } from '../../api/generated/share-media'
import type { MediaDerivativeKind } from '../../api/generated/media'

// The seals and the share management are the platform's (`core/sharing/manageShares.ts`, `core/sharing/shareTree.ts`;
// lifted in cr-node-key-hierarchy 5b.2) and re-exported here, so this application's dialogs, suites and their
// `vi.mock('../share')` keep the one module they import. What stays is what involves the drop key or a guest: the
// WRITE_ONLY half of a mint and a re-issue (the drop key, ADR 0031 point 7), the uploader identity (a contributor's is
// the platform's `createGuestIdentity`, `core/sharing/guestIdentity.ts`), the upload transport, and the viewer.
export {
  newShareId,
  hasLocaleText,
  recoverShareKey,
  readSharePassword,
  readShareNoteMap,
  readShareName,
  carriedSettings,
  updateShare,
  listSharesFor,
  revokeShare,
  deleteShare,
  reactivateShare,
  isShareDeleted,
  isReissueRequired,
  type ShareAddress,
  type RecoveryCopy,
  type MintedShare,
  type ReissueSettings,
  type ReissuableShare,
  type ManagedShare,
  type LocaleMap,
} from '../../core/sharing/manageShares'

// --- curator (authenticated) -----------------------------------------------

/**
 * Mint a public viewer share of one Memory (curator side): the platform's [mintShare] — a fresh `K_share`, the
 * Memory's `K_mem` wrapped under it, the name re-sealed under it, the recovery copy under the scope's write key (ADR
 * 0036 point 4), one `stale_epoch` retry — plus, for a WRITE_ONLY share, this application's drop key. The caller turns
 * the returned `shareId` + `K_share` into a link with [buildShareLink].
 *
 * A WRITE_ONLY share carries no wrap (ADR 0031 point 7): it carries the Memory's drop public key under `K_share`
 * instead — the node's existing drop key, read fresh from the server because every upload so far is sealed to it,
 * or a new one whose private half the server then sets on the Memory. Two owners minting the Memory's first one at
 * once: the server keeps one and refuses the other `drop_key_exists`, which re-reads the node and retries once.
 */
export async function createShare(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  epochs: EpochKeys,
  memoryName: string,
  role: ShareRole = ShareRole.Read,
  expiresAt: Date | null = null,
  note: LocaleMap = {},
  name = '',
  password = '',
): Promise<MintedShare> {
  const writeOnly: WriteOnlyMint | undefined =
    role === ShareRole.WriteOnly
      ? {
          dropFields: async (kShare) =>
            dropKeysForShare(scopeId, nodeId, memoryKey, kShare, await currentSealedDropKey(scopeId, nodeId)),
          // Another owner set the Memory's drop key first: seal to theirs, which every upload so far is sealed to.
          retryOnce: (e) => isCodedError(e, HttpStatus.conflict, 'drop_key_exists'),
        }
      : undefined
  return mintShare(scopeId, nodeId, memoryKey, epochs, memoryName, role, expiresAt, note, name, password, writeOnly)
}

/**
 * Re-issue a share (ADR 0075): the platform's [remintShare] — a new id and `K_share`, the old link off in one
 * transaction — plus, for a WRITE_ONLY link, the Memory's **existing** drop public key: every upload so far is sealed
 * to it (ADR 0031 point 7), so a re-issue is refused here rather than minting a new one. A share is re-issued at most
 * once: `409 share_deleted` ({@link isShareDeleted}) is never retried, and the caller re-reads the list.
 */
export async function reissueShare(
  old: ReissuableShare,
  memoryKey: Uint8Array,
  epochs: EpochKeys,
  memoryName: string,
  settings: ReissueSettings = carriedSettings(epochs, old),
): Promise<MintedShare> {
  const { scopeId, nodeId } = old
  const writeOnly: WriteOnlyMint | undefined =
    old.role === ShareRole.WriteOnly
      ? {
          dropFields: async (kShare) => {
            const existing = await currentSealedDropKey(scopeId, nodeId)
            if (!existing) throw new Error('drop_key_missing') // a WRITE_ONLY share proves the Memory has one
            return { sealedDropPublicKey: dropKeysForShare(scopeId, nodeId, memoryKey, kShare, existing).sealedDropPublicKey }
          },
        }
      : undefined
  return remintShare(old, memoryKey, epochs, memoryName, settings, writeOnly)
}

/** The Memory's sealed drop private key as the server holds it now — null until its first WRITE_ONLY share. */
async function currentSealedDropKey(scopeId: string, nodeId: string): Promise<string | null> {
  const nodes = await withRefresh(() => contentApi().listNodes({ scopeId }))
  return nodes.find((n) => n.id === nodeId)?.sealedDropPrivateKey ?? null
}

// --- contributor / uploader (anonymous, no session; cr-0101, cr-0102) --------

/**
 * A named session on a READ_WRITE or WRITE_ONLY share: the anonymous identity id + its capability token and — a
 * WRITE_ONLY identity only — its own key `K_up` (standard base64), which everything it uploads is sealed under
 * (ADR 0031 point 7). Persisted on the guest's device only; it opens nothing but what this identity sealed.
 */
export type ContributorSession = GuestIdentity & { uploaderKey?: string }

/**
 * Name yourself on a READ_WRITE or WRITE_ONLY share (operator-blind) and create an anonymous share identity.
 * A READ_WRITE guest is the platform's {@link createGuestIdentity}: the display name under `K_mem`. A WRITE_ONLY guest, which never holds `K_mem`, mints its own key
 * `K_up`, seals the name under it and hands `K_up` over wrapped to the Memory's drop public key. Returns the
 * identity id + a capability token that authorizes this session's uploads (+ `K_up`). The server never sees the
 * name (only the ciphertext), the key, or the person.
 *
 * The request proves the guest holds the whole link, not only its id (ADR 0076): `X-Share-Write` carries the token
 * derived from [fragmentKey] (the link's `#k=`), whose hash the share stores.
 */
export async function createContributorIdentity(
  shareId: string,
  scopeId: string,
  access: ShareAccess,
  displayName: string,
  fragmentKey: string,
  pwToken?: string,
): Promise<ContributorSession> {
  // A READ_WRITE guest names itself as on any link: the platform's identity, the name under the root's key (`K_mem`).
  if (access.kind === 'read') return createGuestIdentity({ shareId, scopeId }, access.memoryKey, { name: displayName, fragmentKey, pwToken })
  const identityId = crypto.randomUUID() // client-minted: the name and the key wrap are sealed bound to it (ADR 0072)
  const minted = mintUploaderKey(scopeId, access.nodeId, identityId, access.dropPublicKey)
  const sealedDisplayName = b64std(v1.seal(identityNameContext(scopeId, shareId, identityId), minted.uploaderKey, v1.utf8(displayName)))
  const { contributorToken } = await publicSharesApi().createShareIdentity({
    shareId,
    createShareIdentityRequest: { identityId, sealedDisplayName, wrappedIdentityKey: minted.wrappedIdentityKey },
    xSharePassword: pwToken, // cr-0164: the password gate fronts the write path too
    xShareWrite: b64std(v1.shareWriteToken(fromB64url(fragmentKey), shareId)),
  })
  return { identityId, contributorToken, uploaderKey: b64std(minted.uploaderKey) }
}

/**
 * The key a named session's uploads are sealed under: a WRITE_ONLY identity's own `K_up`, a READ_WRITE identity's `K_mem`.
 * Null when the session cannot seal for this share — a WRITE_ONLY session saved without its key, which must name
 * itself again rather than fall back to a key it does not hold.
 */
export function sessionSealKey(session: ContributorSession, access: ShareAccess): Uint8Array | null {
  if (access.kind === 'drop') return session.uploaderKey ? fromB64std(session.uploaderKey) : null
  return access.memoryKey
}

/** The share-scoped upload transport (no auth) — reserve and sign each next window through core's guest blob transport
 *  (`core/sharing/guestBlob.ts`: `startShareBlobUploads`, then `presignShareBlobUploadWindows`, the link's gate again, so
 *  a revoked link stops the upload there), complete via `completeShareMedia`. */
function shareUploadTransport(shareId: string, contributorToken: string, pwToken?: string, nodeId?: string): MediaUploadTransport {
  // The target (5a.5): a Memory or chapter below the share root, named on every start, window and completion — one target
  // per request. Absent for the root itself, so a root-level upload sends exactly what it always did.
  const target = nodeId === undefined ? {} : { nodeId }
  // A contributor uploads media only — never a cover: ORIGINAL/THUMBNAIL/PREVIEW is the whole role set, and the server
  // refuses any other role from a guest (`400 invalid_request`, Memories' `admitsGuestRole`).
  const to: GuestUploadTarget = { shareId, contributorToken, pwToken, ...target } // pwToken: cr-0164
  return {
    reserve: (declares) => guestReserve(to, declares),
    window: guestWindow(to),
    async complete(media) {
      const done = await publicShareMediaApi().completeShareMedia({
        shareId,
        shareMediaBatchRequest: {
          contributorToken,
          ...target,
          items: [{
            mediaId: media.mediaId,
            originalUploadId: media.originalUploadId,
            derivatives: media.derivatives.map((d) => ({ kind: d.kind, uploadId: d.uploadId })),
            wrappedMeta: media.wrappedMeta,
            wrappedExif: media.wrappedExif,
          }],
        },
        xSharePassword: pwToken, // cr-0164
      })
      const m = done.results[0]
      if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
      return { blobId: m.blobId, mediaId: m.mediaId }
    },
  }
}

/**
 * Add one photo to a READ_WRITE or WRITE_ONLY share (anonymous): seal it under the session's key ([sessionSealKey]:
 * `K_mem` for a contributor, the guest's own `K_up` for an uploader) with its derivatives + metadata and upload it
 * via the share-scoped endpoints — the exact same operator-blind pipeline as the curator's `addMedia`, only the
 * transport is share-gated. Returns the new blob + media ids.
 *
 * `nodeId` targets a Memory or chapter BELOW the share root (a folder link's Memory, a Memory link's chapter — 5a.5,
 * ADR 0080 Decision 6); `sealKey` is then that node's key from the chain, never the root's. Omitted, the upload lands on
 * the root, as every upload did before.
 */
export async function addSharePhoto(
  session: ContributorSession & { shareId: string; scopeId: string; sealKey: Uint8Array; pwToken?: string; nodeId?: string },
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  return uploadMediaVia(
    session.scopeId, session.sealKey, source, deriver,
    shareUploadTransport(session.shareId, session.contributorToken, session.pwToken, session.nodeId), opts,
  )
}

/**
 * Delete one of your own contributions on a READ_WRITE/WRITE_ONLY share (anonymous, token-authorized). The server
 * authorizes it by a single-row ownership check (the media's `share_identity_id` equals the token's identity) and
 * reclaims its blobs — a foreign/unknown media is a 404. Used by the uploader drop-box's session-only delete-own.
 */
export async function deleteSharePhoto(shareId: string, contributorToken: string, mediaId: string, pwToken?: string): Promise<void> {
  await publicShareMediaApi().deleteShareMedia({ shareId, mediaId, xContributorToken: contributorToken, xSharePassword: pwToken }) // cr-0164
}

// --- viewer (anonymous, no session) ----------------------------------------

/** One shared media resolved for the read-only viewer: the media + the `K_mem` of the node it sits on + the shared
 *  Memory's scope, which every one of its seals is bound to (ADR 0072). A media a WRITE_ONLY guest added carries its
 *  origin, so [mediaKey] opens it through that node's drop key instead (ADR 0031 point 7). `chapterName` is the title
 *  of the chapter it sits in (ADR 0080 Decision 2), null for a media on the shared root itself; `nodeId` is the node it
 *  sits on, which the share page groups the chapters' media by (inbox #356: two chapters may share a title). */
export type ShareItem = { item: MediaItem; memoryKey: Uint8Array; scopeId: string; nodeId: string; chapterName: string | null }

/**
 * What a resolved link lets its guest do with keys (ADR 0031 point 7):
 *  - `read` (READ, READ_WRITE): the shared Memory's root `K_mem` — to open its media, and for a contributor to
 *    seal their name + uploads under;
 *  - `drop` (WRITE_ONLY): the Memory's drop PUBLIC key and the node it belongs to — enough to wrap an identity key
 *    of its own to, nothing to open anything with.
 */
export type ShareAccess =
  | { kind: 'read'; memoryKey: Uint8Array }
  | { kind: 'drop'; nodeId: string; dropPublicKey: v1.HybridPublic }

/** The decrypted shared Memory, ready to render: its name, the grant `role`, what its [access] holds, and every
 *  media (each with its own node's `K_mem` — none for a WRITE_ONLY link, which is served no media). */
export type ResolvedShare = {
  memoryName: string
  noteByLocale: LocaleMap
  role: ShareRole
  /** The shared Memory's scope — a contributor seals their name and uploads bound to it (ADR 0072). */
  scopeId: string
  access: ShareAccess
  items: ShareItem[]
}

function shareMediaToItem(m: ShareMedia, uploader: MediaItem['uploader']): MediaItem {
  // Each ref is owned by THIS row's media, in the role the row lists it in (ADR 0072), exactly as the authed list —
  // with its blob's sealed meta as served (inbox #397).
  return {
    mediaId: m.mediaId,
    original: {
      blobId: m.blobId, chunkCount: m.chunkCount, byteSize: m.byteSize, wrappedBlobKey: m.wrappedBlobKey,
      wrappedBlobMeta: m.wrappedBlobMeta ?? undefined, ownerId: m.mediaId, role: 'ORIGINAL',
    },
    derivatives: (m.derivatives ?? []).map((d) => ({
      kind: d.kind as MediaDerivativeKind,
      ref: {
        blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey,
        wrappedBlobMeta: d.wrappedBlobMeta ?? undefined, ownerId: m.mediaId, role: d.kind as MediaDerivativeKind,
      },
    })),
    wrappedMeta: m.wrappedMeta ?? undefined,
    uploader,
  }
}

/** The Memories half of a resolved share: what its link lets the guest do with keys, every media (each with its
 *  own node's `K_mem` — none for a WRITE_ONLY link, which is served no media), and the title of every chapter below
 *  the shared root it could open (ADR 0080 Decision 2; empty for a WRITE_ONLY link, which holds no key to open one). */
export type OpenedMemory = { access: ShareAccess; items: ShareItem[]; chapterNames: Map<string, string> }

/** The node a container's body embeds this one for (`ShareBodyProps.subtreeRoot`): a Memory or chapter below the share
 *  root, and its key as the chain opened it. */
export type SubtreeRoot = { nodeId: string; key: Uint8Array }

/** No media and no uploader key: what a WRITE_ONLY link is served, and the listing before it is read. */
export const NO_SHARE_MEDIA: ShareMediaListing = { media: [], identities: [] }

/**
 * Read what a share serves of the Memory's media — Memories' own guest read (`listShareMedia`, no bearer; the guest's
 * password token as for the resolve): the sealed rows under the shared subtree and the wrapped keys of the WRITE_ONLY
 * identities whose rows those are. A WRITE_ONLY link is served nothing (ADR 0031), so it is not asked. Throws
 * [ShareUnavailableError] when the read is refused (a link revoked since its resolve answers 404 here too).
 */
export async function listShareMedia(shareId: string, role: ShareRole, pwToken?: string): Promise<ShareMediaListing> {
  if (role === ShareRole.WriteOnly) return NO_SHARE_MEDIA
  try {
    return await publicShareMediaApi().listShareMedia({ shareId, xSharePassword: pwToken })
  } catch {
    throw new ShareUnavailableError('not_found')
  }
}

/**
 * Open the Memories half of a public share: unwrap the Memory's `K_mem` with the fragment key, derive every chapter's
 * `K_mem`, and return a flat, read-only list of the [listing]'s media (each tagged with the exact `K_mem` its blobs open
 * under). The platform half (fetch, password, name, note, root kind) is `resolvePublicShare` (core/sharing); the shell
 * calls it and hands this its head, and the media are Memories' own read ([listShareMedia]). Without a listing it opens
 * the keys alone — what the body needs before the listing lands (a contributor's name gate reads the access). Throws
 * [ShareUnavailableError] when the response cannot be opened (a server that served a share without the wrap or drop key
 * the role needs, or one that does not open under the fragment key).
 *
 * With a [root] (a container's body embeds this one below its share root — a folder link's Memory, cr-node-key-hierarchy
 * 5b.4b), the chain is still walked from the share root, but only that node and its descendants are listed: its media
 * open under the keys of the chain, its own carry no chapter title, and `access.memoryKey` is the root's key.
 */
export function openSharedMemory(head: PublicShareHead, listing: ShareMediaListing = NO_SHARE_MEDIA, root?: SubtreeRoot): OpenedMemory {
  const { share, kShare, scopeId } = head
  // ADR 0031 point 7: a WRITE_ONLY link opens the drop public key and nothing else. It never unwraps `K_mem`, even
  // from a row that still carries a wrap, and it is served no media to key.
  if (share.role === ShareRole.WriteOnly) {
    if (!share.sealedDropPublicKey || !share.nodeId) throw new ShareUnavailableError('not_found')
    let dropPublicKey: v1.HybridPublic
    try {
      dropPublicKey = openDropPublicKey(kShare, share.sealedDropPublicKey)
    } catch {
      throw new ShareUnavailableError('bad_fragment_key')
    }
    return { access: { kind: 'drop', nodeId: share.nodeId, dropPublicKey }, items: [], chapterNames: new Map() }
  }

  const shareRootKey = openShareRoot(head).key // READ / READ_WRITE only (guarded above)
  const { keys, names, nodes } = walkSubtree(scopeId, share.nodes, shareRootKey)
  // Embedded below a container's share (a folder link's Memory): only that node and what hangs below it, keyed by the
  // chain the walk opened. A root the walk did not reach is a share this body cannot open.
  const within = root ? subtreeOf(nodes, root.nodeId) : null
  if (root && !keys.has(root.nodeId)) throw new ShareUnavailableError('not_found')
  const rootId = root?.nodeId
  const rootKmem = root?.key ?? shareRootKey
  // A media a WRITE_ONLY guest added opens through its node's drop key and its identity's wrapped key: the resolve's
  // node carries the one, the listing's identities the other (ADR 0031 point 7). That list is all a guest is told
  // about any identity — never a name, which is for the Memory's members (ADR 0030 point 8).
  const dropKeyOf = new Map(share.nodes.map((n) => [n.id, n.sealedDropPrivateKey]))
  const wrapped = new Map(listing.identities.map((i) => [i.identityId, i.wrappedIdentityKey]))
  const items: ShareItem[] = []
  for (const m of listing.media) {
    if (within && !within.has(m.nodeId)) continue // a media of another node of the container's share
    const memoryKey = keys.get(m.nodeId)
    if (!memoryKey) continue // a media under a chapter whose K_mem didn't open — skip
    const owner = m.identityId ? `share:${m.identityId}` : undefined
    const uploader = uploaderOriginOf(owner, m.nodeId, dropKeyOf.get(m.nodeId), wrapped)
    // The embedded root's own media carry no chapter title: its name is the body's heading, not a chapter's.
    const chapterName = m.nodeId === rootId ? null : (names.get(m.nodeId) ?? null)
    items.push({ item: shareMediaToItem(m, uploader), memoryKey, scopeId, nodeId: m.nodeId, chapterName })
  }
  const chapterNames = within ? new Map([...names].filter(([id]) => within.has(id) && id !== rootId)) : names
  return { access: { kind: 'read', memoryKey: rootKmem }, items, chapterNames }
}

/** `rootId` and every node the walk opened below it, through the served `parentId`s. */
function subtreeOf(nodes: ReadonlyMap<string, { parentId?: string | null }>, rootId: string): Set<string> {
  const within = new Set([rootId])
  for (let grew = true; grew; ) {
    grew = false
    for (const [id, n] of nodes) {
      if (!within.has(id) && n.parentId != null && within.has(n.parentId)) {
        within.add(id)
        grew = true
      }
    }
  }
  return within
}

/**
 * Resolve + decrypt a public share in one call: the platform half (`resolvePublicShare`), then the Memories half — its
 * media read ([listShareMedia]) and `openSharedMemory`. The share page does NOT use this — its shell resolves the head,
 * gates on password and terms, and only then lets the Memories body open the rest. It is the headless entry the unit
 * and e2e suites drive. The media read follows the resolve rather than racing it: it needs the role (a WRITE_ONLY link
 * is not asked) and a locked link would refuse it.
 */
export async function resolveShare(shareId: string, fragmentKey: string, pwToken?: string): Promise<ResolvedShare> {
  const head = await resolvePublicShare(shareId, fragmentKey, pwToken)
  const { access, items } = openSharedMemory(head, await listShareMedia(shareId, head.role, pwToken))
  return { memoryName: head.name, noteByLocale: head.noteByLocale, role: head.role, scopeId: head.scopeId, access, items }
}

/** Open a shared blob via the gated, share-scoped presign endpoint (no auth), then decrypt it under its `K_mem`.
 *  A password-protected share needs the same guest token (cr-0118) as the resolve. */
async function openShareBlob(shareId: string, si: ShareItem, ref: BlobRef, pwToken?: string): Promise<Uint8Array> {
  const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId, xSharePassword: pwToken })
  const getUrls = [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  return openBlobFromUrls(si.scopeId, mediaKey(si.scopeId, si.memoryKey, si.item), ref, getUrls)
}

/**
 * A {@link ChunkProvider} for the anonymous share viewer — the Slice-2 SW range seam over the gated share-scoped
 * presign (`resolveShareBlob`: `shareId` + guest `pwToken`, whole-blob only). Same `openFrame` core as the authed
 * `chunkProvider`; only the presign differs, so the SW never learns which route served the bytes (ADR 0047).
 */
export function shareChunkProvider(shareId: string, si: ShareItem, ref: BlobRef, pwToken?: string): ChunkProvider {
  return makeChunkProvider(si.scopeId, mediaKey(si.scopeId, si.memoryKey, si.item), ref, async () => {
    const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId, xSharePassword: pwToken })
    return [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  })
}

/** Open a shared media's grid (thumbnail) tier. */
export const openShareGrid = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si, gridRef(si.item), pwToken)
/** Open a shared media's lightbox (preview) tier. */
export const openShareLightbox = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si, lightboxRef(si.item), pwToken)
/** Open a shared media's pristine original (download). */
export const openShareOriginal = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si, downloadRef(si.item), pwToken)
