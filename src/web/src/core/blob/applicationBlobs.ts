// Core's blob service on the wire (ADR 0112): the blobs an application stores — any number per node, each in a role its
// application declares and held by an owner, an application record's id the server stores and never resolves (Expenses:
// an entry's receipts). What an application does with them is its own; this is the transport, once, for every
// application.
//
// - **Upload** through core's transport, member and guest alike: `planBlob` under the node's key with owner = the node
//   and the application's role (the wrap is bound to blob, node and role, ADR 0072: no blob purpose of its own), the
//   file's meta sealed under its `K_blob` (`wrappedBlobMeta`, the server never learns what a blob is), the pump through
//   core's authed or guest reserve and window, then the service's completion against the owner.
// - **Read**: a node's listing, paged by id, its first page tagged (`ETag`, `If-None-Match` → `304`). Every change of a
//   node's blobs moves its item revision, so an application re-reads this when its own revisioned list moved.
// - **Bytes**: core's presign by blob id — a member's scope READ gate (`presignBlobDownloads`, a block is the item's
//   `blocked_by_operator`), a guest's link (`resolveShareBlob`, a block is `451`; in a moderator's review the case's hold,
//   `core/sharing/guestBlob.ts`) — each frame opened under the `K_blob` the node's key unwraps.
// - **A moderator's case read**: every application blob the case's hold copied (`listCaseApplicationBlobs`, key-proved).
import { readBlobMeta, makeChunkProvider, planBlob, presignAllChunks, reserveAndPump, authedReserve, authedWindow, throwIfAborted, unwrapBlobKey, type BlobChunkProvider } from './transport'
import { guestReserve, guestWindow, shareChunkUrls } from '../sharing/guestBlob'
import { apiBaseOf, shareIdOf, type ShareAt } from '../sharing/shareAt'
import { streamBlob, type ByteSink } from './stream'
import type { BlobMeta, OpenedBlobMeta } from './meta'
import type { BlobRef, BlobSource } from './types'
import { expectMintedId } from '../node/mintedId'
import { plaintextTotal } from '../../sw/range'
import { openSaveTarget } from '../../shared/saveStream'
import { withRefresh } from '../../app/sessionRefresh'
import { HttpStatus, isHttpStatus } from '../../shared/httpError'
import { applicationBlobsApi, caseApplicationBlobsApi, publicShareApplicationBlobsApi } from '../../api/client'
import type { ApplicationBlob, CaseApplicationBlob, ShareApplicationBlob } from '../../api/generated/blob'
import type { Access } from '../access'

/** The largest page a read asks for (the contract's `ItemsLimit` cap). */
const PAGE_LIMIT = 1000

/** How many frames a whole read fetches and opens at once (Depot's download bound). */
const READ_IN_FLIGHT = 4

/** One application blob as the client holds it: the listed row, decoded. `writer` is a member read's only. */
export type HeldApplicationBlob = {
  id: string
  ownerId: string
  role: string
  blobId: string
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
  wrappedBlobMeta: string | null
  writer?: string
  createdAt: Date
  /** When its owner first kept it; null while none did, and for good once it was released by name. */
  keptAt: Date | null
  /** When it was released — by name (then [keptAt] is null) or with its owner's end (a keep undoes that). */
  releasedAt: Date | null
  purgeAfter: Date | null
  blockedByOperator: boolean
}

/** Whether [b] was released by name, for good — no keep of its owner reaches it again (ADR 0112 decision 2). */
export const releasedForGood = (b: Pick<HeldApplicationBlob, 'keptAt' | 'releasedAt'>): boolean => b.releasedAt !== null && b.keptAt === null

const held = (r: ApplicationBlob | ShareApplicationBlob): HeldApplicationBlob => ({
  id: r.id,
  ownerId: r.ownerId,
  role: r.role,
  blobId: r.blobId,
  chunkCount: r.chunkCount,
  byteSize: r.byteSize,
  wrappedBlobKey: r.wrappedBlobKey,
  wrappedBlobMeta: r.wrappedBlobMeta ?? null,
  ...('writer' in r ? { writer: r.writer } : {}),
  createdAt: r.createdAt,
  keptAt: r.keptAt ?? null,
  releasedAt: r.releasedAt ?? null,
  purgeAfter: r.purgeAfter ?? null,
  blockedByOperator: r.blockedByOperator,
})

// --- the read ---------------------------------------------------------------------------------------------------------

/** Every application blob of a node and the tag of the listing's first page (`null`: none served). */
export type ApplicationBlobsRead = { blobs: HeldApplicationBlob[]; etag: string | null }

type BlobPage = { blobs: (ApplicationBlob | ShareApplicationBlob)[]; more: boolean; cursor?: string | null }

/** The page loop both reads share: a conditional first page (an unchanged listing answers `null`), later pages `after`
 *  the page before's cursor, never conditional. */
async function readPages(
  first: () => Promise<{ raw: Response; value(): Promise<BlobPage> }>,
  next: (after: string) => Promise<BlobPage>,
  conditional: boolean,
): Promise<ApplicationBlobsRead | null> {
  let res
  try {
    res = await first()
  } catch (e) {
    if (conditional && isHttpStatus(e, HttpStatus.notModified)) return null
    throw e
  }
  const etag = res.raw.headers.get('ETag')
  let page = await res.value()
  const blobs = page.blobs.map(held)
  while (page.more) {
    const after = page.cursor
    if (!after) throw new Error('application_blob_page_without_cursor') // a page that says more must say where it ends
    page = await next(after)
    if (page.more && page.cursor === after) throw new Error('application_blob_page_did_not_advance')
    blobs.push(...page.blobs.map(held))
  }
  return { blobs, etag }
}

/** A member's read of a node's application blobs. With `ifNoneMatch` (the last first page's `ETag`) an unchanged
 *  listing is `null`. */
export function readMemberApplicationBlobs(scopeId: string, nodeId: string, ifNoneMatch?: string): Promise<ApplicationBlobsRead | null> {
  return readPages(
    () => withRefresh(() => applicationBlobsApi().listApplicationBlobsRaw({ scopeId, nodeId, limit: PAGE_LIMIT, ifNoneMatch })),
    (after) => withRefresh(() => applicationBlobsApi().listApplicationBlobs({ scopeId, nodeId, after, limit: PAGE_LIMIT })),
    ifNoneMatch !== undefined,
  )
}

/** A guest's read of a node's application blobs under its link `at`, of the instance that serves it (no writer: the
 *  guest's schema has none). */
export function readGuestApplicationBlobs(
  at: ShareAt, nodeId: string, opts: { pwToken?: string; ifNoneMatch?: string } = {},
): Promise<ApplicationBlobsRead | null> {
  const api = publicShareApplicationBlobsApi(apiBaseOf(at))
  const shareId = shareIdOf(at)
  return readPages(
    () => api.listShareApplicationBlobsRaw({ shareId, nodeId, xSharePassword: opts.pwToken, limit: PAGE_LIMIT, ifNoneMatch: opts.ifNoneMatch }),
    (after) => api.listShareApplicationBlobs({ shareId, nodeId, xSharePassword: opts.pwToken, after, limit: PAGE_LIMIT }),
    opts.ifNoneMatch !== undefined,
  )
}

/** A moderator's read of every application blob a case's hold copied, with the key proof [proof] made for it. */
export async function readCaseApplicationBlobs(
  caseId: string, proof: { xVeyqModerationChallenge?: string; xVeyqModerationSignature?: string },
): Promise<CaseApplicationBlob[]> {
  return (await caseApplicationBlobsApi().listCaseApplicationBlobs({ caseId, ...proof })).blobs
}

// --- the upload -------------------------------------------------------------------------------------------------------

/** Where an upload goes: a member's scope path, or a guest's share path on the instance that serves the link (`apiBase`,
 *  `''` this origin — ADR 0121) with the link's password token and the guest's contributor token (`createShareIdentity`). */
export type ApplicationBlobTarget =
  | { kind: 'member'; scopeId: string; nodeId: string }
  | { kind: 'guest'; scopeId: string; nodeId: string; shareId: string; apiBase: string; contributorToken: string; pwToken?: string }

/** The node's key and epoch an upload is wrapped and stated under. */
export type ApplicationBlobKey = { key: Uint8Array; epoch: number }

/**
 * Seal and upload [file] as the application blob [id] in [role] held by [ownerId] in the node [target], under the node's
 * key and epoch [key], its [meta] sealed under its `K_blob`. Throws the transport's codes (`budget_exceeded` — over the
 * role's cap, already at the start —, `quota_exceeded`, `capability_disabled`, …) and the completion's refusals as the
 * generated client throws them (`too_many_blobs`; `stale_epoch` / `node_rekey_pending` are the caller's re-read).
 */
export async function uploadApplicationBlob(
  target: ApplicationBlobTarget,
  key: ApplicationBlobKey,
  ownerId: string,
  id: string,
  role: string,
  file: BlobSource,
  meta: BlobMeta,
  opts: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<HeldApplicationBlob> {
  const { scopeId, nodeId } = target
  const plan = planBlob(scopeId, { ownerId: nodeId, role }, key.key, file, meta)
  let blob: ApplicationBlob | ShareApplicationBlob
  if (target.kind === 'member') {
    const [uploadId] = await reserveAndPump([plan], (d) => authedReserve(scopeId, d), authedWindow(scopeId), opts.onProgress, opts.signal)
    throwIfAborted(opts.signal)
    blob = await withRefresh(() =>
      applicationBlobsApi().completeApplicationBlob({
        scopeId, nodeId, applicationBlobCompleteRequest: { nodeEpoch: key.epoch, id, ownerId, role, uploadId: uploadId! },
      }),
    )
  } else {
    const to = { shareId: target.shareId, apiBase: target.apiBase, contributorToken: target.contributorToken, pwToken: target.pwToken, nodeId }
    const [uploadId] = await reserveAndPump([plan], (d) => guestReserve(to, d), guestWindow(to), opts.onProgress, opts.signal)
    throwIfAborted(opts.signal)
    blob = await publicShareApplicationBlobsApi(target.apiBase).completeShareApplicationBlob({
      shareId: target.shareId,
      nodeId,
      xSharePassword: target.pwToken,
      shareApplicationBlobCompleteRequest: { nodeEpoch: key.epoch, contributorToken: target.contributorToken, id, ownerId, role, uploadId: uploadId! },
    })
  }
  expectMintedId(plan.blobId, blob.blobId)
  expectMintedId(id, blob.id)
  return held(blob)
}

// --- the bytes --------------------------------------------------------------------------------------------------------

/** What of a listed blob its bytes are read by. */
type BlobRow = Pick<HeldApplicationBlob, 'blobId' | 'chunkCount' | 'byteSize' | 'wrappedBlobKey' | 'wrappedBlobMeta'>

/** The transport's ref for an application blob: the listed row, bound to the node it was listed for and its role — a
 *  row the server moved here from another node, or into another role, does not open. */
export function applicationBlobRef(
  nodeId: string, role: string, r: BlobRow,
): BlobRef {
  return { blobId: r.blobId, chunkCount: r.chunkCount, byteSize: r.byteSize, wrappedBlobKey: r.wrappedBlobKey, wrappedBlobMeta: r.wrappedBlobMeta, ownerId: nodeId, role }
}

/** A blob's plaintext size: its sealed size less every frame's overhead (exact). */
export const applicationBlobSize = (r: Pick<HeldApplicationBlob, 'byteSize' | 'chunkCount'>): number => Math.max(0, plaintextTotal(r.byteSize, r.chunkCount))

/** A blob's sealed meta for display; null when it carries none or it does not open. */
export function applicationBlobMeta(scopeId: string, nodeKey: Uint8Array, nodeId: string, role: string, r: BlobRow): OpenedBlobMeta | null {
  return readBlobMeta(scopeId, nodeKey, applicationBlobRef(nodeId, role, r))
}

/** A blob's `K_blob` (for a report share): throws `OpenRefused` for a row that is not this node's in this role. */
export function applicationBlobKeyOf(scopeId: string, nodeKey: Uint8Array, nodeId: string, role: string, r: BlobRow): Uint8Array {
  return unwrapBlobKey(scopeId, nodeKey, applicationBlobRef(nodeId, role, r))
}

/** Who presigns a blob's chunks for whoever looks: a member's scope READ gate, or a guest's link. */
export function applicationBlobPresign(access: Access, scopeId: string): (blobId: string) => Promise<string[]> {
  if (access.mode === 'guest') return (blobId) => shareChunkUrls(access, blobId, access.pwToken)
  return (blobId) => presignAllChunks(scopeId, blobId)
}

/** The provider over a blob's chunks: `K_blob` unwrapped at once (`OpenRefused` for a row that is not this node's), the
 *  chunks presigned on first read. */
export function applicationBlobChunks(
  scopeId: string, nodeKey: Uint8Array, nodeId: string, role: string, r: BlobRow, presign: (blobId: string) => Promise<string[]>,
): BlobChunkProvider {
  return makeChunkProvider(scopeId, nodeKey, applicationBlobRef(nodeId, role, r), () => presign(r.blobId))
}

/** Read a provider's whole plaintext — for a blob its view holds whole (an application caps the role's size), never a
 *  download, which streams. */
export async function readAll(provider: Pick<BlobChunkProvider, 'chunkCount' | 'getPlaintextChunk'>, signal?: AbortSignal): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  const collect: ByteSink = { write: async (b) => void parts.push(b), close: async () => {}, abort: async () => {} }
  await streamBlob(provider, collect, { inFlight: READ_IN_FLIGHT, signal })
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}

/**
 * Decrypt a blob and save it to the device under [name], streamed into the target `openSaveTarget` picks. Call it
 * straight from the click with nothing awaited before (the save picker needs the click's activation). The type goes no
 * further than the name: the save target stays `application/octet-stream`.
 */
export async function downloadApplicationBlob(provider: BlobChunkProvider, name: string, size: number): Promise<void> {
  const sink = await openSaveTarget(name, size)
  await streamBlob(provider, sink, { inFlight: READ_IN_FLIGHT })
}

/** A refused read of a blocked blob: a member's presign item `blocked_by_operator`, a guest's `451`. The listing marks a
 *  block, so a view rarely meets this — only a block placed after its last read. */
export function isBlockedRead(e: unknown): boolean {
  if (e instanceof Error && e.message === 'blocked_by_operator') return true
  return (e as { response?: { status?: unknown } } | null)?.response?.status === 451
}
