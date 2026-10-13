// Files' side of subtree export and import (ADR 0082): the content of a `FILE` node is its one blob. Export lists the
// folder's blobs once (cached for the run), opens the file's blob under its own key and streams the plaintext chunks;
// import creates the node and uploads the payload through the same job the "Upload files" action runs
// (`fileUploadJob`), so a replayed file is sealed under the new node's key like any upload, and a node whose upload fails
// is trashed by the job's own `abandon`. A drop box is not exported (it is a share object with guest identities).
import { iterateBlob } from '../../core/blob/stream'
import type { ExportContentHook, ImportContentHook } from '../../core/export/hooks'
import { withRefresh } from '../../app/sessionRefresh'
import type { FileBlobRef } from '../../api/generated/files'
import { filesApi } from './api'
import { fileChunkProvider, fileFacts } from './download'
import { fileUploadJob } from './uploadFile'

export const FILES_FILE_CONTENT = 'files.file'
const SCHEMA = 1

/** The listed blob of each file node under one parent, read once per run and parent. */
async function blobsUnder(cache: Map<string, unknown>, scopeId: string, parentId: string | null): Promise<Map<string, FileBlobRef | null>> {
  const key = `files:${scopeId}:${parentId ?? ''}`
  const hit = cache.get(key) as Map<string, FileBlobRef | null> | undefined
  if (hit) return hit
  const page = await withRefresh(() => filesApi().listFiles({ scopeId, parentId: parentId ?? undefined }))
  const map = new Map(page.items.map((i) => [i.nodeId, i.fileBlob ?? null]))
  cache.set(key, map)
  return map
}

export const exportFile: ExportContentHook = async (ctx) => {
  const blob = (await blobsUnder(ctx.cache, ctx.scopeId, ctx.node.parentId)).get(ctx.node.id)
  if (!blob) return null // a file whose upload never finished holds nothing
  const { size, meta } = fileFacts(ctx.scopeId, ctx.nodeKey, ctx.node.id, blob)
  const provider = fileChunkProvider(ctx.scopeId, ctx.nodeKey, ctx.node.id, blob)
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
