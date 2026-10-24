// The platform's blob transport (ADR 0079; cr-files-application B1, moved from the Memories media layer unchanged):
// operator-blind, direct-to-store. A blob is sealed client-side as chunked AEAD under a fresh random K_blob, wrapped
// under the key its caller hands in and bound to (scope, owner, blob, role) (ADR 0012, ADR 0072); the ciphertext
// chunks travel straight to the object store via short-TTL presigned URLs (ADR 0013) — never through the backend.
//
// What is here is what every upload and read shares, whoever owns the blob: plan, the one pump, the authed reserve and
// window of core's `blob` control plane, and the read side (whole-blob open, the Service Worker's chunk provider). What
// an owner does with its blobs — which roles it declares, how the upload completes, what metadata rides along — is the
// application's, which calls in here; this module imports no application (`validate_web_core_imports_no_applications`).
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { HttpStatus } from '../../shared/httpError'
import { blobApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import type { PresignedUrl } from '../../api/generated/blob'
import type { ChunkProvider } from '../../sw/protocol'
import type { BlobDeclare, BlobPlan, BlobRef, BlobRole, BlobSource, ChunkPut, ReservedUpload, WindowFn } from './types'
import { openBlobMetaOf, type BlobMeta, type OpenedBlobMeta } from './meta'
import { blobKeyContext, openFrames, unwrapBlobKey } from './keys'
import { PRESIGN_BATCH_MAX, PRESIGN_REFRESH_MS, PresignExpired, readScheduler, type Presigned, type ReadOpts } from './readScheduler'

// The wrap's binding, its unwrap, a listed blob's meta and the frames' open are pure, and live in `keys.ts` so a Worker
// can import them; re-exported here for the callers that always imported them from the transport.
export { unwrapBlobKey, readBlobMeta, openFrames } from './keys'

/** Plaintext bytes per full chunk before sealing — the contract's frame size (ADR 0013 §3), not a choice made here:
 *  the server derives `chunkCount` from `byteSize` on exactly this and refuses any other. */
export const CHUNK_SIZE = v1.CHUNK_FRAME_SIZE.plaintextBytes

// --- upload ----------------------------------------------------------------

/** Plan `source` as a blob under a fresh random `K_blob`, wrapped under `key` bound to where it will live — the
 *  client-minted blob id, its owner and its role (ADR 0072). Its shape follows from its size alone: every frame
 *  but the last carries [CHUNK_SIZE] plaintext bytes, and an empty source is one empty frame. `meta`, when given, is
 *  sealed under that same `K_blob` (`blob-meta`, inbox #397) and declared with the blob — write-once, so it describes
 *  exactly these bytes; without it the plan, and so the declare, is what it was before the field (no randomness drawn). */
export function planBlob(
  scopeId: string, owner: { ownerId: string; role: BlobRole }, key: Uint8Array, source: BlobSource, meta?: BlobMeta,
): BlobPlan {
  const blobKey = v1.randomBytes(32)
  const blobId = crypto.randomUUID()
  const wrappedBlobKey = b64std(v1.seal(blobKeyContext(scopeId, { blobId, ...owner }), key, blobKey))
  const chunkCount = Math.max(1, Math.ceil(source.size / CHUNK_SIZE))
  const plan: BlobPlan = { source, blobKey, wrappedBlobKey, chunkCount, byteSize: source.size + chunkCount * v1.FRAME_OVERHEAD, blobId, ...owner }
  if (meta) plan.wrappedBlobMeta = b64std(v1.sealBlobMeta(blobKey, meta))
  return plan
}

/** Read and seal frame `index` of a planned blob — one [CHUNK_SIZE] slice of the source, never more. */
async function sealFrameAt(plan: BlobPlan, index: number): Promise<Uint8Array> {
  const slice = plan.source.slice(index * CHUNK_SIZE, (index + 1) * CHUNK_SIZE)
  const plain = new Uint8Array(await slice.arrayBuffer())
  // Outside the typed seal union on purpose (ADR 0072 Decision 2): the frame key is this blob's own K_blob, so the
  // key is the object binding; the AAD binds only the frame's position (index ‖ total).
  return v1.sealFrame(plan.blobKey, index, plan.chunkCount, plain, v1.randomBytes(12))
}

/** Throw an AbortError if the caller cancelled — the upload queue maps it to a cancelled (not failed) upload. */
export function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
}

/** How many `PUT`s the pump keeps in flight — and so how many sealed frames it holds (≈256 KiB each). */
export const PUMP_IN_FLIGHT = 4

/**
 * How close to the end of the signed range the pump asks for the next window: when it starts a chunk this few
 * places before the last signed one. Enough that the next window is usually back before the pump reaches it.
 */
const WINDOW_LOOKAHEAD = 2 * PUMP_IN_FLIGHT

/**
 * The one upload pump (ADR 0013 §2 + Consequences): read, seal and `PUT` a planned blob one frame at a time, with
 * at most [PUMP_IN_FLIGHT] `PUT`s in flight, signing each next window before the URLs in hand run out. A `PUT` the
 * store answers `403` (its window's TTL ran out — a stall, a slow link) re-signs from that chunk and is retried
 * once, as the GET side does. Every upload — member, contributor, cover — goes through here.
 */
async function pumpBlob(
  plan: BlobPlan,
  upload: ReservedUpload,
  signWindow: WindowFn,
  onChunkDone: () => void,
  signal?: AbortSignal,
): Promise<void> {
  const urls = new Map<number, string>()
  let signedUpTo = 0 // exclusive: every chunk below it has been signed at least once
  const take = (puts: ChunkPut[]) => {
    for (const p of puts) {
      urls.set(p.index, p.url)
      signedUpTo = Math.max(signedUpTo, p.index + 1)
    }
  }
  take(upload.puts)

  // At most one next-window request at a time; every worker that needs it waits on the same one.
  let pending: Promise<void> | null = null
  const nextWindow = (): Promise<void> => {
    pending ??= signWindow(upload.uploadId, signedUpTo).then(take).finally(() => { pending = null })
    return pending
  }

  let next = 0
  const worker = async () => {
    for (;;) {
      throwIfAborted(signal)
      const i = next++
      if (i >= plan.chunkCount) return
      if (signedUpTo < plan.chunkCount && i >= signedUpTo - WINDOW_LOOKAHEAD) nextWindow().catch(() => {}) // ahead; a failure resurfaces below
      while (!urls.has(i)) {
        const before = signedUpTo
        await nextWindow()
        if (signedUpTo === before) throw new Error('upload_failed') // a window that signs nothing would spin forever
      }
      const frame = await sealFrameAt(plan, i)
      try {
        await putChunk(urls.get(i)!, frame, signal)
      } catch (e) {
        if (chunkPutStatus(e) !== HttpStatus.forbidden) throw e
        take(await signWindow(upload.uploadId, i)) // the window expired → re-sign from this chunk, retry it once
        await putChunk(urls.get(i)!, frame, signal)
      }
      urls.delete(i)
      onChunkDone()
    }
  }
  await Promise.all(Array.from({ length: Math.min(PUMP_IN_FLIGHT, plan.chunkCount) }, worker))
}

/** Reserve [plans] through `reserve` and pump each in turn, reporting progress over every chunk of every blob. */
export async function reserveAndPump(
  plans: BlobPlan[],
  reserve: (declares: BlobDeclare[]) => Promise<ReservedUpload[]>,
  signWindow: WindowFn,
  onProgress?: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<string[]> {
  throwIfAborted(signal)
  const reserved = await reserve(plans.map((p) => ({
    blobId: p.blobId, ownerId: p.ownerId, role: p.role,
    chunkCount: p.chunkCount, byteSize: p.byteSize, wrappedBlobKey: p.wrappedBlobKey,
    ...(p.wrappedBlobMeta ? { wrappedBlobMeta: p.wrappedBlobMeta } : {}),
  })))
  if (reserved.length !== plans.length) throw new Error('upload_failed')
  const totalChunks = plans.reduce((n, p) => n + p.chunkCount, 0)
  let doneChunks = 0
  onProgress?.(0)
  for (const [i, plan] of plans.entries()) {
    await pumpBlob(plan, reserved[i], signWindow, () => {
      doneChunks += 1
      onProgress?.(doneChunks / totalChunks)
    }, signal)
  }
  return reserved.map((r) => r.uploadId)
}

/** Reserve through core's authed blob control plane (`startBlobUploads`) — every member upload shares it. */
export async function authedReserve(scopeId: string, declares: BlobDeclare[]): Promise<ReservedUpload[]> {
  const started = await withRefresh(() =>
    blobApi().startBlobUploads({ scopeId, blobUploadsBatchRequest: { items: declares } }),
  )
  if (started.results.length !== declares.length) throw new Error('upload_failed')
  return started.results.map((r) => {
    if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
    if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
    return { uploadId: r.uploadId, puts: r.presignedPuts }
  })
}

/** Sign an authed upload's next window (`presignBlobUploadWindows`). */
export function authedWindow(scopeId: string): WindowFn {
  return async (uploadId, fromChunk) => {
    const signed = await withRefresh(() =>
      blobApi().presignBlobUploadWindows({ scopeId, blobUploadWindowsRequest: { items: [{ uploadId, fromChunk }] } }),
    )
    return windowPuts(signed.results[0])
  }
}

/** One window result → its PUTs, or the per-item error it carries (`upload_not_found`, `range_invalid`, …). */
export function windowPuts(r: { presignedPuts?: PresignedUrl[] | null; error?: string | null } | undefined): ChunkPut[] {
  if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
  if (!r.presignedPuts) throw new Error('store_unavailable')
  return r.presignedPuts
}

// --- read-back -------------------------------------------------------------
//
// Every read goes through the page's read scheduler (`readScheduler.ts`, inbox #488): presigns batched and bounded,
// chunk GETs bounded and retried, a URL that expired before its GET re-presigned. Who presigns differs — the member's
// scope READ gate, a guest's link, a moderator's hold — and is the `PresignUrls` a reader hands in.

/** A blob's presigned per-chunk GET URLs, in chunk order, scheduled as `opts` says. */
export type PresignUrls = (opts: ReadOpts) => Promise<string[]>

/** Opens a whole blob's fetched frames under its `K_blob` somewhere other than this thread (a grid's thumbnails in
 *  Memories' listing opener, inbox #489); rejects as `openFrames` would. Absent: opened here, by [openFrames]. */
export type FrameOpener = (blobKey: Uint8Array, frames: Uint8Array[]) => Promise<Uint8Array>

/** How a whole-blob read is scheduled ([ReadOpts]) and where its frames are opened. */
export interface OpenOpts extends ReadOpts {
  openFrames?: FrameOpener
}

/** How often one read re-presigns its blob after an expired URL (a store `403`, or one that aged in the queue) before
 *  it fails: twice covers a queue that outlived one TTL and a clock skew on top. */
const MAX_RENEWS = 2

/** Whether a chunk GET failed because its URL expired: the store's `403`, or [PresignExpired] before it was sent. */
function isExpiredPresign(e: unknown): boolean {
  return e instanceof PresignExpired || chunkGetStatus(e) === HttpStatus.forbidden
}

/**
 * Fetch every ciphertext frame of a blob: presign, GET each chunk through the scheduler, and on an expired URL
 * re-presign and fetch only the chunks still missing, at most [MAX_RENEWS] times. Any other failure is final (a
 * transient one was already retried by the scheduler).
 */
async function readFrames(presign: PresignUrls, opts: ReadOpts): Promise<Uint8Array[]> {
  let urls = await presign(opts)
  let issuedAt = readScheduler.now()
  const frames: (Uint8Array | undefined)[] = new Array<Uint8Array | undefined>(urls.length)
  for (let renews = 0; ; renews++) {
    const settled = await Promise.allSettled(
      urls.map((url, i) => frames[i] ?? readScheduler.getChunk(url, { ...opts, issuedAt }).then((frame) => (frames[i] = frame))),
    )
    const failures = settled.flatMap((r) => (r.status === 'rejected' ? [r.reason as unknown] : []))
    if (failures.length === 0) return frames as Uint8Array[]
    const final = failures.find((e) => !isExpiredPresign(e))
    if (final !== undefined) throw final
    if (renews >= MAX_RENEWS) throw failures[0]
    urls = await presign(opts)
    issuedAt = readScheduler.now()
    if (urls.length !== frames.length) throw new Error('store_unavailable')
  }
}

/**
 * Open a blob whose `K_blob` the caller already holds — a moderator's review of a reported medium, whose report share
 * hands over each blob's key itself rather than the key it is wrapped under (uc.moderation.review-a-case).
 */
export async function openBlobWithKey(blobKey: Uint8Array, presign: PresignUrls, opts: OpenOpts = {}): Promise<Uint8Array> {
  const { openFrames: open, ...read } = opts
  const frames = await readFrames(presign, read)
  return open ? open(blobKey, frames) : openFrames(blobKey, frames)
}

/**
 * Open a blob through any presign: unwrap its `K_blob` under `key` (synchronously — a ref that is not this owner's
 * throws `OpenRefused` before anything is fetched), fetch the ciphertext frames direct-from-store, and `openFrame` them
 * back to plaintext (the AAD position check still applies). Shared by the authed [openBlob] and an anonymous share
 * viewer (presign via the gated share endpoint, cr-0100) — the decrypt is identical; only who presigns differs.
 */
export async function openBlobVia(scopeId: string, key: Uint8Array, ref: BlobRef, presign: PresignUrls, opts: OpenOpts = {}): Promise<Uint8Array> {
  const blobKey = unwrapBlobKey(scopeId, key, ref)
  return openBlobWithKey(blobKey, presign, opts)
}

/** The member's batched presign per scope: ids asked together travel in one `blob-downloads-batch` call. */
const memberPresigners = new Map<string, (blobId: string, opts?: ReadOpts) => Promise<Presigned>>()
function memberPresigner(scopeId: string) {
  let presigner = memberPresigners.get(scopeId)
  if (!presigner) {
    presigner = readScheduler.batcher(async (blobIds) => {
      const presigned = await withRefresh(() =>
        blobApi().presignBlobDownloads({ scopeId, blobDownloadsBatchRequest: { items: blobIds.map((blobId) => ({ blobId })) } }),
      )
      return presigned.results.map((d) =>
        !d || d.error || !d.presignedGets ? new Error(d?.error ?? 'store_unavailable') : byIndex(d.presignedGets),
      )
    }, PRESIGN_BATCH_MAX)
    memberPresigners.set(scopeId, presigner)
  }
  return presigner
}

/**
 * Presign GETs for **every** chunk of a blob — the whole-blob presign the authed read paths share (the batch
 * endpoint is whole-blob only; there is no per-chunk presign). Returned as URLs ordered by chunk index. Batched with
 * every other blob of the scope asked at the same time (the contract's `maxItems` per call).
 */
export async function presignAllChunks(scopeId: string, blobId: string, opts: ReadOpts = {}): Promise<string[]> {
  return (await memberPresigner(scopeId)(blobId, opts)).urls
}

/**
 * Open a blob: unwrap its `K_blob` under `key`, presign the chunk GETs, fetch the ciphertext frames
 * direct-from-store, and `openFrame` them back to plaintext (the AAD position check still applies).
 */
export async function openBlob(scopeId: string, key: Uint8Array, ref: BlobRef, opts: OpenOpts = {}): Promise<Uint8Array> {
  return openBlobVia(scopeId, key, ref, (o) => presignAllChunks(scopeId, ref.blobId, o), opts)
}

// --- range streaming: the page-side chunk provider (ADR 0047) ---------------
//
// The Service-Worker range layer holds NO key and NO auth: it asks the page for the decrypted *plaintext* of
// individual chunks over a MessagePort and assembles the byte ranges the <video> demuxer requests. This is that
// page-side seam — a per-blob source of plaintext chunks over the same presign→openFrame plumbing the whole-blob read
// paths use. K_blob and the auth context never leave the page.

/** Parse the HTTP status out of a chunk GET failure (`readScheduler.getChunk`: `chunk_get_failed:<status>`), or undefined if not one. */
function chunkGetStatus(e: unknown): number | undefined {
  const m = e instanceof Error ? /^chunk_get_failed:(\d+)$/.exec(e.message) : null
  return m ? Number(m[1]) : undefined
}

/** A page-side chunk provider that also opens its blob's sealed metadata under the `K_blob` it already holds. The
 *  Service Worker sees only the {@link ChunkProvider} half: the key never leaves the page. */
export type BlobChunkProvider = ChunkProvider & {
  /** The blob's sealed metadata (`meta.ts` `openBlobMetaOf`): null when the ref carries none or it does not open. */
  meta(): OpenedBlobMeta | null
}

/**
 * Build a {@link ChunkProvider} over a `presignAll` that yields the whole blob's per-chunk GET URLs (ordered by
 * index). The auth contexts — the authed app and an anonymous share — differ ONLY in that function; the unwrap +
 * `openFrame` core here is identical, so the SW never learns which route served the bytes. `K_blob` is unwrapped
 * once (synchronously: a ref that is not this owner's throws `OpenRefused` here) and also opens the blob's meta
 * ([BlobChunkProvider.meta]); the presigned URLs are cached with their issue time and re-presigned on TTL/expiry or a
 * store `403`.
 */
export function makeChunkProvider(
  scopeId: string,
  key: Uint8Array,
  ref: BlobRef,
  presignAll: () => Promise<string[]>,
): BlobChunkProvider {
  const blobKey = unwrapBlobKey(scopeId, key, ref)
  let urls: string[] | undefined
  let issuedAt = 0
  // ONE presign in flight: readers that ask at once - the upload pump's workers reading a copy's original by frame
  // (cr-copy-node), the Service Worker's parallel ranges - share it instead of each presigning the whole blob.
  let presigning: Promise<string[]> | null = null
  async function freshUrls(force: boolean): Promise<string[]> {
    if (force || !urls || Date.now() - issuedAt > PRESIGN_REFRESH_MS) {
      presigning ??= presignAll().then((fresh) => {
        urls = fresh
        issuedAt = Date.now()
        return fresh
      }).finally(() => { presigning = null })
      return presigning
    }
    return urls
  }
  return {
    chunkCount: ref.chunkCount,
    byteSize: ref.byteSize,
    meta: () => openBlobMetaOf(ref, blobKey),
    async getPlaintextChunk(index: number): Promise<Uint8Array> {
      if (!Number.isInteger(index) || index < 0 || index >= ref.chunkCount) {
        throw new Error(`chunk_out_of_range:${index}/${ref.chunkCount}`)
      }
      let all = await freshUrls(false)
      let frame: Uint8Array
      try {
        frame = await readScheduler.getChunk(all[index], { issuedAt })
      } catch (e) {
        if (!isExpiredPresign(e)) throw e
        all = await freshUrls(true) // presign expired → re-presign the WHOLE blob, retry the one chunk once
        frame = await readScheduler.getChunk(all[index], { issuedAt })
      }
      // AAD trap (load-bearing): `total` is the WHOLE blob's chunkCount, never the streamed range subset —
      // openFrame binds `index‖total` (v1.ts), so a subset `total` fails every open. See the CR Slice-2 §5b.
      return v1.openFrame(blobKey, index, ref.chunkCount, frame)
    },
  }
}

// --- transport helpers -----------------------------------------------------

/** Order presigned URLs by their declared chunk index (the server returns them in order, but don't assume). */
function byIndex(urls: PresignedUrl[]): string[] {
  return [...urls].sort((a, b) => a.index - b.index).map((u) => u.url)
}

/** The one `Content-Type` a presigned PUT signs, so the store refuses any other (ADR 0013 §6). The uploader no
 *  longer chooses the stored type: whatever the store later serves is never renderable. */
const PUT_CONTENT_TYPE = 'application/octet-stream'

async function putChunk(url: string, frame: Uint8Array, signal?: AbortSignal): Promise<void> {
  const res = await fetch(url, { method: 'PUT', headers: { 'Content-Type': PUT_CONTENT_TYPE }, body: frame as BodyInit, signal })
  // The store's answer is empty (`200`, `Content-Length: 0`) and nothing here needs it — but Chromium cancels the load
  // of an empty body nobody reads and reports the PUT `net::ERR_ABORTED` although the store took the chunk: 1,163 of
  // 1,176 PUTs of a 50-photo upload in the V1 heavy stage. So it is read to its end; a failure to read an empty body
  // says nothing about the PUT, whose status is already known.
  await res.arrayBuffer().catch(() => undefined)
  if (!res.ok) throw new Error(`chunk_put_failed:${res.status}`)
}

/** Parse the HTTP status out of a {@link putChunk} failure (`chunk_put_failed:<status>`), or undefined if not one. */
function chunkPutStatus(e: unknown): number | undefined {
  const m = e instanceof Error ? /^chunk_put_failed:(\d+)$/.exec(e.message) : null
  return m ? Number(m[1]) : undefined
}

