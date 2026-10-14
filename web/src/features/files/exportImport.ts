// Files' side of a download and its import (ADR 0082): the content of a `FILE` node is its one blob, and a file IS one file in
// the archive (`downloadsAsFile`), at the node's own path. A download lists the folder's blobs once (cached for the run) through
// the downloading session's own source - a member's listing, or a READ / READ_WRITE guest's link listing - opens the file's
// blob under its own key and streams the plaintext chunks; import creates the node and uploads the payload through the same
// job the "Upload files" action runs (`fileUploadJob`), so a replayed file is sealed under the new node's key like any upload,
// and a node whose upload fails is trashed by the job's own `abandon`. A drop box is not downloaded (it is a share object with
// guest identities).
import { iterateBlob } from '../../core/blob/stream'
import type { ExportContentHook, ExportNodeContext, ImportContentHook } from '../../core/export/hooks'
import type { FileBlobRef } from '../../api/generated/files'
import { fileFacts } from './download'
import { guestFilesSource, memberFilesSource, type FilesSource } from './source'
import { fileUploadJob } from './uploadFile'

export const FILES_FILE_CONTENT = 'files.file'
/** The application id of every content type above, and the content schema this build writes and reads. */
export const FILES_CONTENT_SCHEMA = { application: 'files', version: 1 } as const
const SCHEMA = FILES_CONTENT_SCHEMA.version

/** The session's Files source, made once per run. */
function sourceOf(ctx: ExportNodeContext): FilesSource {
  const hit = ctx.cache.get('files:source') as FilesSource | undefined
  if (hit) return hit
  const a = ctx.access
  const source = a.mode === 'member'
    ? memberFilesSource({ scopeId: () => a.scopeId, epochs: () => a.epochs, keyOf: (id) => ctx.tree.keyOf(id) })
    : guestFilesSource(() => a)
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
  })
  return { type: FILES_FILE_CONTENT, schema: SCHEMA, file }
}

export const importFile: ImportContentHook = async (ctx) => {
  const content = ctx.node.content
  const ref = content?.type === FILES_FILE_CONTENT ? (content.file as Parameters<typeof ctx.payload>[0] | undefined) : undefined
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
