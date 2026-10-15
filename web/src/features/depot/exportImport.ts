// Depot's side of a download and its import (ADR 0082): the content of a `FILE` node is its one blob, and a file IS one file in
// the archive (`downloadsAsFile`), at the node's own path. A download lists the folder's blobs once (cached for the run) through
// the downloading session's own source - a member's listing, or a READ / READ_WRITE guest's link listing - opens the file's
// blob under its own key and streams the plaintext chunks; import creates the node and uploads the payload through the same
// job the "Upload files" action runs (`fileUploadJob`), so a replayed file is sealed under the new node's key like any upload,
// and a node whose upload fails is trashed by the job's own `abandon`.
//
// A drop box downloads like any other container (cr-download-is-the-export Decision 7): a directory, its guests' folders as
// directories under the names the detail shows them by, their files inside - its content names only what it was
// (`depot.drop-box`), no payload. It IMPORTS as a plain folder (`importsAsFolder` on its descriptor): a drop key, its guests'
// identities and its links cannot be rebuilt from a download, so nothing tries.
import { iterateBlob } from '../../core/blob/stream'
import { CHUNK_SIZE } from '../../core/blob/transport'
import type { ExportContentHook, ExportNodeContext, ImportContentHook } from '../../core/export/hooks'
import type { ContentProblem } from '../../core/applications/registry'
import type { ManifestContent } from '../../core/export/manifest'
import type { FileBlobRef } from '../../api/generated/depot'
import { fileFacts } from './download'
import { guestDepotSource, memberDepotSource, type DepotSource } from './source'
import { fileUploadJob } from './uploadFile'

export const DEPOT_FILE_CONTENT = 'depot.file'
export const DEPOT_DROPBOX_CONTENT = 'depot.drop-box'
/** The application id of every content type above, and the content schema this build writes and reads. */
export const DEPOT_CONTENT_SCHEMA = { application: 'depot', version: 1 } as const
const SCHEMA = DEPOT_CONTENT_SCHEMA.version

/** The session's Depot source, made once per run. */
function sourceOf(ctx: ExportNodeContext): DepotSource {
  const hit = ctx.cache.get('files:source') as DepotSource | undefined
  if (hit) return hit
  const a = ctx.access
  const source = a.mode === 'member'
    ? memberDepotSource({ scopeId: () => a.scopeId, epochs: () => a.epochs, keyOf: (id) => ctx.tree.keyOf(id) })
    : guestDepotSource(() => a)
  ctx.cache.set('files:source', source)
  return source
}

/** The listed blob of each file node under one parent, read once per run and parent (a guest's listing is the link's whole
 *  subtree, read once). */
async function blobsUnder(ctx: ExportNodeContext, parentId: string | null): Promise<Map<string, FileBlobRef | null>> {
  const key = ctx.access.mode === 'guest' ? 'files:blobs' : `files:blobs:${parentId ?? ''}`
  const hit = ctx.cache.get(key) as Map<string, FileBlobRef | null> | undefined
  if (hit) return hit
  const map = await sourceOf(ctx).listBlobs(parentId)
  ctx.cache.set(key, map)
  return map
}

export const exportFile: ExportContentHook = async (ctx) => {
  const blob = (await blobsUnder(ctx, ctx.node.parentId)).get(ctx.node.id)
  if (!blob) return null // a file whose upload never finished holds nothing
  const { size, meta } = fileFacts(ctx.scopeId, ctx.nodeKey, ctx.node.id, blob)
  const provider = sourceOf(ctx).chunks(ctx.nodeKey, ctx.node.id, blob)
  const file = ctx.addPayload(ctx.node.name, {
    size,
    mediaType: meta?.type || 'application/octet-stream',
    modifiedAt: typeof meta?.modifiedAt === 'number' ? meta.modifiedAt : undefined,
    chunks: () => iterateBlob(provider, { signal: ctx.signal }),
    // the same bytes by frame, for a copy (cr-copy-node Decision 3): the job below reads its payload only by frame-sized
    // slices (the upload pump), so the kind declares `importStreams`
    frames: { frameSize: CHUNK_SIZE, chunkCount: provider.chunkCount, getPlaintextChunk: (i) => provider.getPlaintextChunk(i) },
  })
  return { type: DEPOT_FILE_CONTENT, schema: SCHEMA, file }
}

/** A drop box's content: what it was, nothing else - its guests' folders are its children, their files theirs. */
export const exportDropBox: ExportContentHook = async () => ({ type: DEPOT_DROPBOX_CONTENT, schema: SCHEMA })

/** A `depot.drop-box` content from a download carries nothing but its type and version (the import makes it a plain folder). */
export function validateDropBoxContent(content: ManifestContent): ContentProblem | null {
  if (content.type !== DEPOT_DROPBOX_CONTENT) return { code: 'invalid_manifest', message: `not a Depot drop box: ${content.type}` }
  const extra = Object.keys(content).filter((k) => k !== 'type' && k !== 'schema')
  return extra.length > 0 ? { code: 'invalid_manifest', message: `a drop box with ${extra.join(', ')}` } : null
}

/** A `depot.file` content from a download is its one payload reference (core checks the reference itself). */
export function validateFileContent(content: ManifestContent): ContentProblem | null {
  const f = content.file as Record<string, unknown> | undefined
  if (content.type !== DEPOT_FILE_CONTENT) return { code: 'invalid_manifest', message: `not a Depot file: ${content.type}` }
  if (typeof f !== 'object' || f === null || typeof f.path !== 'string' || typeof f.size !== 'number') {
    return { code: 'invalid_manifest', message: 'a file without its payload' }
  }
  return null
}

export const importFile: ImportContentHook = async (ctx) => {
  const content = ctx.node.content
  const ref = content?.type === DEPOT_FILE_CONTENT ? (content.file as Parameters<typeof ctx.payload>[0] | undefined) : undefined
  if (!ref) throw new Error('import_invalid_content')
  if ((content!.schema as number | undefined) !== undefined && (content!.schema as number) > SCHEMA) throw new Error('import_newer_content')
  const file = await ctx.payload(ref, ctx.name)
  const job = fileUploadJob({ scopeId: ctx.scopeId, epochs: ctx.epochs, parent: ctx.parent }, file)
  try {
    const result = await job.run({ onProgress: (f) => ctx.onProgress?.(f), signal: ctx.signal ?? new AbortController().signal })
    // the job keeps the node key inside; the children of a file do not exist, so a placeholder key is never used
    return { nodeId: result.nodeId, nodeKey: new Uint8Array(0) }
  } catch (e) {
    await Promise.resolve(job.abandon?.()).catch(() => {})
    throw e
  }
}
