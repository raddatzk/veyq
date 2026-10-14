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
import { iterateBlob } from '../../core/blob/stream'
import type { ExportContentHook, ExportNodeContext, ImportContentHook, ImportNodeContext } from '../../core/export/hooks'
import type { ManifestContent, ManifestPayload } from '../../core/export/manifest'
import { loadTenantPeople } from '../../core/people/directory'
import { createContentNode, listTree } from '../../core/node/tree'
import { nodeMetaState, sealNodeMeta, unsealNodeMeta } from '../../core/node/nodeMeta'
import { readCover, uploadedCover } from '../../core/node/cover/coverModel'
import { uploadCoverImage, writeNodeCover } from '../../core/node/cover/coverWrite'
import type { NodeKind } from '../../core/node/kind'
import {
  addMedia, backfillPlaceName, effectiveCapturedAt, listMedia, originalMediaType, plaintextSize, setMediaMeta, type MediaItem,
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
    const exif = source.loaders.exif ? await source.loaders.exif(item, nodeKey).catch(() => null) : null
    const provider = source.loaders.chunk(item, nodeKey)
    const file = ctx.addPayload(name, {
      size: plaintextSize(item.original),
      mediaType: originalMediaType(scopeId, nodeKey, item) ?? 'application/octet-stream',
      // The upload falls back to the modified time for a capture time the file lacks: carry the capture time there.
      modifiedAt: captured ?? (typeof blobMeta?.modifiedAt === 'number' ? blobMeta.modifiedAt : undefined),
      chunks: () => iterateBlob(provider, { signal: ctx.signal }),
    })
    const medium: ExportedMedium = { file, name }
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

async function coverOf(
  ctx: ExportNodeContext, source: MemorySource, cover: NonNullable<ReturnType<typeof normalizeMemoryCover>>, items: MediaItem[],
): Promise<ExportedCover | undefined> {
  if (cover.source.kind === 'media') {
    const id = cover.source.mediaId
    const mediaIndex = items.findIndex((i) => i.mediaId === id)
    return mediaIndex >= 0 ? { mediaIndex, crop: cover.crop } : undefined
  }
  const bytes = await source.loaders.coverBlob(cover.source.ref, ctx.nodeKey).catch(() => null)
  if (!bytes) return undefined
  const file = ctx.addPayload('cover.jpg', { size: bytes.length, mediaType: 'image/jpeg', chunks: async function* () { yield bytes } })
  return { file, crop: cover.crop }
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

    const total = media.reduce((s, m) => s + m.file.size, 0) || 1
    let done = 0
    const added: { mediaId: string; medium: ExportedMedium }[] = []
    for (const medium of media) {
      if (ctx.signal?.aborted) throw ctx.signal.reason ?? new DOMException('Import cancelled', 'AbortError')
      const file = await ctx.payload(medium.file, medium.name ?? 'file')
      const before = done
      const { mediaId } = await addMedia(
        ctx.scopeId, nodeId, nodeKey, { blob: file, mediaType: medium.file.mediaType, lastModified: file.lastModified, name: file.name },
        undefined, { signal: ctx.signal, onProgress: (f) => ctx.onProgress?.((before + f * medium.file.size) / total) },
      )
      done += medium.file.size
      added.push({ mediaId, medium })
    }

    // Second pass: what the upload cannot derive from the file. One listing gives the rows (wrappedMeta, metaVersion).
    const fixes = added.filter(({ medium }) => medium.capturedAtCorrected || medium.location?.place)
    if (fixes.length > 0) {
      const rows = new Map((await listMedia(ctx.scopeId, nodeId)).map((r) => [r.mediaId, r]))
      for (const { mediaId, medium } of fixes) {
        const row = rows.get(mediaId)
        if (!row) continue
        let target = row
        const at = isoToMs(medium.capturedAt)
        if (medium.capturedAtCorrected && at !== undefined) {
          const w = await setMediaMeta(ctx.scopeId, target, nodeKey, at)
          target = { ...target, wrappedMeta: w.wrappedMeta ?? undefined, metaVersion: w.metaVersion }
        }
        if (medium.location?.place) await backfillPlaceName(ctx.scopeId, target, nodeKey, medium.location.place)
      }
    }

    const cover = content.cover as ExportedCover | undefined
    if (cover) await restoreCover(ctx, nodeId, nodeKey, cover, added.map((a) => a.mediaId))
    return { nodeId, nodeKey }
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
  const file = await ctx.payload(cover.file, 'cover')
  const { uploadId, plan } = await uploadCoverImage(ctx.scopeId, nodeId, nodeKey, { blob: file, mediaType: cover.file.mediaType, name: 'cover' }, undefined, { signal: ctx.signal })
  await writeNodeCover({ scopeId: ctx.scopeId, nodeId, nodeKey, current, cover: uploadedCover(plan, readCover({ crop }, nodeId)!.crop), uploadId, surfaceConflict: false })
}
