// Memories' side of subtree export and import (ADR 0082): the content of a MEMORY or CHAPTER node is its media, its date and
// place, its cover and - as TEXT - who was there. Core carries only the tree and the payload bytes, so everything below
// travels inside the hook's own `content`:
//
//   { type: 'memories.memory' | 'memories.chapter', schema, media: [...], date?, location?, participants?, cover? }
//
// A chapter is a node of its own, so its media sit in ITS content and the Memory hook never needs the chapter's nodes.
//
// What an import can and cannot restore (it adds each original through the ordinary upload, `addMedia`):
//  - the file's own EXIF - GPS, camera, capture time - is read again from the bytes, as for any upload; the manifest's
//    copies of them are for people and other tools to read. There is NO API to seal a coordinate the file lacks, so a
//    coordinate that was only ever sealed (never in the file) does not come back.
//  - a capture time the file lacks is carried by the payload's modified time (the upload falls back to it); one the member
//    CORRECTED (an override) is restored as an override in a second pass, and so is a place name the tier lacks.
//  - the importer is the uploader of every medium; the exported contributor stays text. Participants are not restored:
//    their ids mean nothing in the target (ADR 0083).
//
// A download reads through the downloading session's own source (`source.ts`): a member's, or a READ / READ_WRITE guest's
// link reads. A guest's download carries no names - not the participants, not the contributors - because a guest never
// reads the tenant's member directory; and no EXIF copy, which the link does not serve.
//
// A COPY (ADR 0082 decision 11, `purpose: 'copy'` on both hooks - a member's, within the space) is the same walk and the
// same content plus what an archive never carries, per medium under `copy`: each existing derivative tier as a payload read
// by frame, the meta each of its blobs declared, and the listed tier verbatim (capture time and its correction, geometry,
// coordinates, place name). The copy's import uploads the original and those tiers as they are under fresh `K_blob`s bound to
// the new medium and seals the facts anew (`addCopiedMedia`) - it never decodes an original (`deriveTiers`, EXIF), which
// would hold it whole, a video included - and an uploaded cover is copied as its blob, by frame, without a re-encode
// (`uploadCoverBlob`). So a copy keeps what an archive's import loses: a coordinate only ever sealed, the derived tiers.
// The archive path reads none of it: an import of a download (`purpose: 'import'`) ignores a `copy` it finds.
import { iterateBlob } from '../../core/blob/stream'
import type { ExportContentHook, ExportNodeContext, ImportContentHook, ImportNodeContext, PayloadFile } from '../../core/export/hooks'
import { framesOfBytes } from '../../core/export/copySource'
import { CHUNK_SIZE } from '../../core/blob/transport'
import type { ContentProblem } from '../../core/applications/registry'
import type { ManifestContent, ManifestPayload } from '../../core/export/manifest'
import { loadTenantPeople } from '../../core/people/directory'
import { createContentNode, listTree } from '../../core/node/tree'
import { nodeMetaState, sealNodeMeta, unsealNodeMeta } from '../../core/node/nodeMeta'
import { readCover, uploadedCover } from '../../core/node/cover/coverModel'
import { uploadCoverBlob, uploadCoverImage, writeNodeCover } from '../../core/node/cover/coverWrite'
import type { BlobMeta } from '../../core/blob/meta'
import type { MediaDerivativeKind } from '../../api/generated/media'
import type { NodeKind } from '../../core/node/kind'
import {
  addCopiedMedia, addMedia, backfillPlaceName, effectiveCapturedAt, listMedia, mediaTypeOf, plaintextSize, rawMediaMeta, readByChunk,
  setMediaMeta, tierChunkProviders, type MediaExif, type MediaItem, type ReadByChunk,
} from './media'
import { guestMemorySource, memberMemorySource, type MemorySource } from './source'
import { normalizeDate } from './memoryDate'
import { normalizeLocation } from './memoryLocation'
import { unsealMemoryParticipants } from './memoryParticipants'
import { CHAPTER_KIND } from './kinds'
import { memoryPhotoSource, normalizeMemoryCover } from './memoryCover'

export const MEMORY_CONTENT = 'memories.memory'
export const CHAPTER_CONTENT = 'memories.chapter'
/** The application id of both content types above, and the content schema this build writes and reads. */
export const MEMORIES_CONTENT_SCHEMA = { application: 'memories', version: 1 } as const
const SCHEMA = MEMORIES_CONTENT_SCHEMA.version

export type ExportedMedium = {
  file: ManifestPayload
  name?: string
  capturedAt?: string
  capturedAtCorrected?: true
  location?: { lat?: number; lon?: number; place?: string }
  exif?: Record<string, unknown>
  contributor?: string
  /** A copy's only (see the header); never in an archive. */
  copy?: CopiedMedium
}

/** What a copy hands over of one medium beside its original (ADR 0082 decision 11): the meta the original's blob declared
 *  (absent: it declared none), the listed tier verbatim (absent: none sealed), each derivative tier as a payload read by
 *  frame with the meta its blob declared. The EXIF bundle rides the medium's own `exif`. */
type CopiedMedium = {
  original?: BlobMeta
  meta?: Record<string, unknown>
  tiers: { kind: MediaDerivativeKind; file: ManifestPayload; meta?: BlobMeta }[]
}

type ExportedCover = { mediaIndex: number; crop: unknown } | { file: ManifestPayload; crop: unknown }

function contributorOf(item: MediaItem, people: Awaited<ReturnType<typeof loadTenantPeople>> | null): string | undefined {
  const owner = item.ownerKey
  if (!owner?.startsWith('user:') || !people) return undefined
  return people.get(owner.slice('user:'.length))?.displayName
}

/** The session's Memories source, made once per run: a member's, or the link's for a guest. */
function sourceOf(ctx: ExportNodeContext): MemorySource {
  const hit = ctx.cache.get('memories:source') as MemorySource | undefined
  if (hit) return hit
  const a = ctx.access
  const source = a.mode === 'member' ? memberMemorySource({ scopeId: () => a.scopeId }) : guestMemorySource(() => a, ctx.tree)
  ctx.cache.set('memories:source', source)
  return source
}

/** The tenant's people, read once per run, a member's only (a guest never reads the member directory). */
async function peopleOf(ctx: ExportNodeContext): Promise<Awaited<ReturnType<typeof loadTenantPeople>> | null> {
  if (ctx.access.mode !== 'member') return null
  if (!ctx.cache.has('memories:people')) ctx.cache.set('memories:people', await loadTenantPeople().catch(() => null))
  return ctx.cache.get('memories:people') as Awaited<ReturnType<typeof loadTenantPeople>> | null
}

export const exportMemoryContent: ExportContentHook = async (ctx) => {
  const { scopeId, node, nodeKey } = ctx
  const kind = node.kind
  const source = sourceOf(ctx)
  const { items } = await source.listMedia(node.id)
  const people = await peopleOf(ctx)
  const media: ExportedMedium[] = []
  for (const item of items) {
    if (ctx.signal?.aborted) throw ctx.signal.reason ?? new DOMException('Download cancelled', 'AbortError')
    const meta = source.loaders.meta(item, nodeKey)
    const blobMeta = source.loaders.blobMeta(item, nodeKey)
    const captured = effectiveCapturedAt(meta)
    const name = blobMeta?.name ?? `${item.mediaId}`
    // A download names no EXIF it cannot read; a copy that could not read it would drop the medium's camera data unseen, so
    // its read failing stops the copy's walk instead.
    const exif = !source.loaders.exif ? null
      : ctx.purpose === 'copy' ? await source.loaders.exif(item, nodeKey) : await source.loaders.exif(item, nodeKey).catch(() => null)
    const provider = source.loaders.chunk(item, nodeKey)
    const file = ctx.addPayload(name, {
      size: plaintextSize(item.original),
      mediaType: mediaTypeOf(blobMeta) ?? 'application/octet-stream',
      // The upload falls back to the modified time for a capture time the file lacks: carry the capture time there.
      modifiedAt: captured ?? (typeof blobMeta?.modifiedAt === 'number' ? blobMeta.modifiedAt : undefined),
      chunks: () => iterateBlob(provider, { signal: ctx.signal }),
      frames: framesOf(provider),
    })
    const medium: ExportedMedium = { file, name }
    if (ctx.purpose === 'copy') medium.copy = copiedMedium(ctx, item, name, blobMeta)
    if (captured !== undefined) medium.capturedAt = new Date(captured).toISOString()
    if (meta?.capturedAtOverride !== undefined) medium.capturedAtCorrected = true
    const lat = meta?.gpsLat
    const lon = meta?.gpsLng
    if (lat !== undefined || lon !== undefined || meta?.placeName) medium.location = { lat, lon, place: meta?.placeName }
    if (exif) medium.exif = { ...exif }
    const contributor = contributorOf(item, people)
    if (contributor) medium.contributor = contributor
    media.push(medium)
  }

  const raw = unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta)
  const content: ManifestContent = { type: kind === CHAPTER_KIND ? CHAPTER_CONTENT : MEMORY_CONTENT, schema: SCHEMA, media }
  const date = normalizeDate(raw.date)
  if (date) content.date = date
  const location = normalizeLocation(raw.location)
  if (location) content.location = location
  const names = unsealMemoryParticipants(scopeId, nodeKey, node)
    .map((id) => people?.get(id)?.displayName)
    .filter((n): n is string => !!n)
  if (names.length > 0) content.participants = names

  const cover = normalizeMemoryCover(raw.cover, node.id)
  if (cover) content.cover = await coverOf(ctx, source, cover, items)
  return content
}

/** A blob's frames as a copy reads them: the chunks its upload sealed, one at a time. */
const framesOf = (provider: ReadByChunk['provider']) =>
  ({ frameSize: CHUNK_SIZE, chunkCount: provider.chunkCount, getPlaintextChunk: (i: number) => provider.getPlaintextChunk(i) })

/** A blob's declared meta as a copy declares it again: its type, name and time; none where it declared none. */
function declaredMeta(meta: { type?: unknown; name?: unknown; modifiedAt?: unknown } | null | undefined): BlobMeta | undefined {
  const type = mediaTypeOf(meta as Parameters<typeof mediaTypeOf>[0])
  if (!meta || type === undefined) return undefined
  const out: BlobMeta = { type }
  if (typeof meta.name === 'string') out.name = meta.name
  if (typeof meta.modifiedAt === 'number') out.modifiedAt = meta.modifiedAt
  return out
}

/** What a copy hands over of `item` beside its original (see the header): its tiers by frame, its blob meta, its listed tier. */
function copiedMedium(
  ctx: ExportNodeContext, item: MediaItem, name: string, originalMeta: Parameters<typeof declaredMeta>[0],
): CopiedMedium {
  const { scopeId, nodeKey } = ctx
  const tiers = tierChunkProviders(scopeId, nodeKey, item).map(({ kind, ref, meta, provider }) => {
    const declared = declaredMeta(meta)
    const file = ctx.addPayload(`${name}.${kind.toLowerCase()}`, {
      size: plaintextSize(ref),
      mediaType: declared?.type ?? 'image/jpeg',
      chunks: () => iterateBlob(provider, { signal: ctx.signal }),
      frames: framesOf(provider),
    })
    return declared ? { kind, file, meta: declared } : { kind, file }
  })
  const copied: CopiedMedium = { tiers }
  const original = declaredMeta(originalMeta)
  if (original) copied.original = original
  const meta = rawMediaMeta(scopeId, nodeKey, item)
  if (meta) copied.meta = meta
  return copied
}

async function coverOf(
  ctx: ExportNodeContext, source: MemorySource, cover: NonNullable<ReturnType<typeof normalizeMemoryCover>>, items: MediaItem[],
): Promise<ExportedCover | undefined> {
  if (cover.source.kind === 'media') {
    const id = cover.source.mediaId
    const mediaIndex = items.findIndex((i) => i.mediaId === id)
    return mediaIndex >= 0 ? { mediaIndex, crop: cover.crop } : undefined
  }
  if (ctx.purpose === 'copy') {
    // A copy reads the uploaded cover by frame and uploads it as it is (no re-encode); a download reads it whole - it is one
    // cover-sized JPEG - through the session's source.
    const ref = cover.source.ref
    const { meta, provider } = readByChunk(ctx.scopeId, ctx.nodeKey, ref)
    const file = ctx.addPayload('cover.jpg', {
      size: plaintextSize(ref), mediaType: declaredMeta(meta)?.type ?? 'image/jpeg',
      chunks: () => iterateBlob(provider, { signal: ctx.signal }), frames: framesOf(provider),
    })
    return { file, crop: cover.crop }
  }
  const bytes = await source.loaders.coverBlob(cover.source.ref, ctx.nodeKey).catch(() => null)
  if (!bytes) return undefined
  const file = ctx.addPayload('cover.jpg', { size: bytes.length, mediaType: 'image/jpeg', chunks: async function* () { yield bytes }, frames: framesOfBytes(bytes) })
  return { file, crop: cover.crop }
}

/** The contract's `maxLength` / `maxItems` of Memories' fields (`contracts/export/veyq-subtree.schema.json`, `memories*`
 *  `$defs`); `exportImport.test.ts` pins each to the schema. */
export const MEMORIES_LIMITS = { name: 1024, contributor: 1024, place: 1024, participant: 1024, participants: 100 } as const

const DATE = /^\d{4}-\d{2}-\d{2}$/
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const isPayload = (v: unknown): boolean => isObj(v) && typeof v.path === 'string' && typeof v.size === 'number'
const isNum = (v: unknown, min: number, max: number) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max

/** A `memories.memory` / `memories.chapter` content from a download, checked against its contract (decision 10) before the
 *  import writes anything: the shape of every field the import reads, and each string at its `maxLength`. Unknown fields are
 *  ignored, as the format promises. */
export function validateMemoryContent(content: ManifestContent): ContentProblem | null {
  const shape = (message: string): ContentProblem => ({ code: 'invalid_manifest', message })
  const long = (what: string, v: unknown, max: number): ContentProblem | null =>
    v === undefined ? null : typeof v !== 'string' ? shape(`${what} is not text`) : v.length > max ? { code: 'too_large', message: `${what} is longer than ${max}` } : null
  if (content.type !== MEMORY_CONTENT && content.type !== CHAPTER_CONTENT) return shape(`not a Memory or chapter: ${content.type}`)
  if (!Array.isArray(content.media)) return shape('media is not a list')
  for (const [i, m] of content.media.entries()) {
    if (!isObj(m) || !isPayload(m.file)) return shape(`medium ${i} has no file`)
    const problem = long(`medium ${i} name`, m.name, MEMORIES_LIMITS.name) ?? long(`medium ${i} contributor`, m.contributor, MEMORIES_LIMITS.contributor)
    if (problem) return problem
    if (m.capturedAt !== undefined && (typeof m.capturedAt !== 'string' || Number.isNaN(Date.parse(m.capturedAt)))) return shape(`medium ${i} capturedAt`)
    if (m.capturedAtCorrected !== undefined && m.capturedAtCorrected !== true) return shape(`medium ${i} capturedAtCorrected`)
    if (m.exif !== undefined && !isObj(m.exif)) return shape(`medium ${i} exif`)
    if (m.location !== undefined) {
      const l = m.location
      if (!isObj(l) || (l.lat !== undefined && !isNum(l.lat, -90, 90)) || (l.lon !== undefined && !isNum(l.lon, -180, 180))) return shape(`medium ${i} location`)
      const p = long(`medium ${i} place`, l.place, MEMORIES_LIMITS.place)
      if (p) return p
    }
  }
  const d = content.date
  if (d !== undefined && (!isObj(d) || !['auto', 'single', 'range', 'ongoing'].includes(d.mode as string)
    || (d.from !== undefined && !(typeof d.from === 'string' && DATE.test(d.from))) || (d.to !== undefined && !(typeof d.to === 'string' && DATE.test(d.to))))) {
    return shape('date')
  }
  const l = content.location
  if (l !== undefined) {
    if (!isObj(l)) return shape('location')
    const p = long('place', l.name, MEMORIES_LIMITS.place)
    if (p) return p
  }
  const people = content.participants
  if (people !== undefined) {
    if (!Array.isArray(people)) return shape('participants is not a list')
    if (people.length > MEMORIES_LIMITS.participants) return { code: 'too_large', message: `more than ${MEMORIES_LIMITS.participants} participants` }
    for (const [i, p] of people.entries()) {
      const problem = long(`participant ${i}`, p, MEMORIES_LIMITS.participant)
      if (problem) return problem
    }
  }
  const c = content.cover
  if (c !== undefined && (!isObj(c) || ('mediaIndex' in c ? !(Number.isSafeInteger(c.mediaIndex) && (c.mediaIndex as number) >= 0) : !isPayload(c.file)))) {
    return shape('cover')
  }
  return null
}

/** A payload as the real `Blob` the upload decodes (the derivatives, the dimensions): a download's always is one. A copy's
 *  original read by frame is not, and a copy's import never asks for one - it uploads the tiers it was handed. */
function wholePayload(file: PayloadFile): File {
  if (!(file instanceof Blob)) throw new Error('import_needs_whole_payload')
  return file as File
}

const isoToMs = (v: unknown): number | undefined => {
  const t = typeof v === 'string' ? Date.parse(v) : NaN
  return Number.isFinite(t) ? t : undefined
}

/** The import hook of a kind (MEMORY or CHAPTER): create the node with its date and place sealed at birth, add every
 *  original, then the second pass (corrections, place names) and the cover. */
export function importMemoryContent(kind: NodeKind): ImportContentHook {
  return async (ctx: ImportNodeContext) => {
    const content = ctx.node.content
    if (!content || (content.type !== MEMORY_CONTENT && content.type !== CHAPTER_CONTENT)) throw new Error('import_invalid_content')
    if (typeof content.schema === 'number' && content.schema > SCHEMA) throw new Error('import_newer_content')
    const media = (Array.isArray(content.media) ? content.media : []) as ExportedMedium[]
    const date = normalizeDate(content.date)
    const location = normalizeLocation(content.location)
    const sealer = date || location
      ? (key: Uint8Array, addr: Parameters<typeof sealNodeMeta>[1]) => sealNodeMeta(key, addr, { v: 1, ...(date ? { date } : {}), ...(location ? { location } : {}) })
      : undefined
    const { nodeId, contentKey: nodeKey } = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, kind, ctx.name, sealer)

    const copying = ctx.purpose === 'copy'
    const bytesOf = (m: ExportedMedium) => m.file.size + (copying ? (m.copy?.tiers ?? []).reduce((s, t) => s + t.file.size, 0) : 0)
    const total = media.reduce((s, m) => s + bytesOf(m), 0) || 1
    let done = 0
    const added: { mediaId: string; medium: ExportedMedium }[] = []
    for (const medium of media) {
      if (ctx.signal?.aborted) throw ctx.signal.reason ?? new DOMException('Import cancelled', 'AbortError')
      const before = done
      const size = bytesOf(medium)
      const opts = { signal: ctx.signal, onProgress: (f: number) => ctx.onProgress?.((before + f * size) / total) }
      const { mediaId } = copying
        ? await addCopiedMedia(ctx.scopeId, nodeId, nodeKey, await copiedSource(ctx, medium), opts)
        : await addOriginal(ctx, nodeId, nodeKey, medium, opts)
      done += size
      added.push({ mediaId, medium })
    }

    // Second pass: what the upload cannot derive from the file. One listing gives the rows (wrappedMeta, metaRevision). A copy
    // sealed every fact at birth and needs none.
    const fixes = copying ? [] : added.filter(({ medium }) => medium.capturedAtCorrected || medium.location?.place)
    if (fixes.length > 0) {
      const rows = new Map((await listMedia(ctx.scopeId, nodeId)).map((r) => [r.mediaId, r]))
      for (const { mediaId, medium } of fixes) {
        const row = rows.get(mediaId)
        if (!row) continue
        let target = row
        const at = isoToMs(medium.capturedAt)
        if (medium.capturedAtCorrected && at !== undefined) {
          const w = await setMediaMeta(ctx.scopeId, target, nodeKey, at)
          target = { ...target, wrappedMeta: w.wrappedMeta ?? undefined, metaRevision: w.metaRevision }
        }
        if (medium.location?.place) await backfillPlaceName(ctx.scopeId, target, nodeKey, medium.location.place)
      }
    }

    const cover = content.cover as ExportedCover | undefined
    if (cover) await restoreCover(ctx, nodeId, nodeKey, cover, added.map((a) => a.mediaId))
    return { nodeId, nodeKey }
  }
}

/** A download's medium: its original through the ordinary upload, which decodes it to derive the tiers. */
async function addOriginal(
  ctx: ImportNodeContext, nodeId: string, nodeKey: Uint8Array, medium: ExportedMedium, opts: { signal?: AbortSignal; onProgress: (f: number) => void },
): Promise<{ mediaId: string }> {
  const file = wholePayload(await ctx.payload(medium.file, medium.name ?? 'file'))
  return addMedia(ctx.scopeId, nodeId, nodeKey, { blob: file, mediaType: medium.file.mediaType, lastModified: file.lastModified, name: file.name }, undefined, opts)
}

/** A copy's medium as `addCopiedMedia` takes it: the original and each tier read by frame, the facts as the walk read them.
 *  A copy's medium without them is refused - the copy never falls back to decoding the original. */
async function copiedSource(ctx: ImportNodeContext, medium: ExportedMedium): Promise<Parameters<typeof addCopiedMedia>[3]> {
  const copy = medium.copy
  if (!copy || !Array.isArray(copy.tiers)) throw new Error('copy_without_facts')
  const tiers = []
  for (const t of copy.tiers) tiers.push({ kind: t.kind, source: await ctx.payload(t.file, `${medium.name ?? 'file'}.${t.kind.toLowerCase()}`), meta: t.meta })
  return {
    original: { source: await ctx.payload(medium.file, medium.name ?? 'file'), meta: copy.original },
    tiers,
    meta: isObj(copy.meta) ? copy.meta : null,
    exif: isObj(medium.exif) ? (medium.exif as MediaExif) : null,
  }
}

async function restoreCover(ctx: ImportNodeContext, nodeId: string, nodeKey: Uint8Array, cover: ExportedCover, mediaIds: string[]): Promise<void> {
  const crop = (cover as { crop?: unknown }).crop
  const row = (await listTree(ctx.scopeId, ctx.epochs)).find((n) => n.id === nodeId)
  if (!row) return
  const current = nodeMetaState(row)
  if ('mediaIndex' in cover) {
    const mediaId = mediaIds[cover.mediaIndex]
    if (!mediaId) return
    await writeNodeCover({ scopeId: ctx.scopeId, nodeId, nodeKey, current, cover: memoryPhotoSource.seal(mediaId, readCover({ crop }, nodeId)!.crop), surfaceConflict: false })
    return
  }
  // A copy uploads the cover blob as it is, read by frame; a download's cover goes through the cover upload, which re-encodes it.
  const { uploadId, plan } = ctx.purpose === 'copy'
    ? await uploadCoverBlob(ctx.scopeId, nodeId, nodeKey, await ctx.payload(cover.file, 'cover'), { type: cover.file.mediaType }, { signal: ctx.signal })
    : await uploadCoverImage(ctx.scopeId, nodeId, nodeKey, { blob: wholePayload(await ctx.payload(cover.file, 'cover')), mediaType: cover.file.mediaType, name: 'cover' }, undefined, { signal: ctx.signal })
  await writeNodeCover({ scopeId: ctx.scopeId, nodeId, nodeKey, current, cover: uploadedCover(plan, readCover({ crop }, nodeId)!.crop), uploadId, surfaceConflict: false })
}
