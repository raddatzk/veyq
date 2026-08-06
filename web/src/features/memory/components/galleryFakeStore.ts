// Test kit for the gallery component tests (cr-0049). A tiny in-process object store that — unlike the
// single-media fake in media.test.ts — **accumulates** several media per node, so MemoryDetail/MediaLightbox
// can list a real multi-item grid and round-trip each tier (original + derivatives) through a real v1
// seal/open under a K_blob wrapped by K_mem. Operator-blind: the "store" only ever holds ciphertext.
// Not a test file (no test cases); imported by the *.test.ts that mount the components.
import { vi } from 'vitest'

type Session = { wrappedBlobKey: string; chunkCount: number }
type MediaRecord = {
  mediaId: string
  originalUploadId: string
  derivatives: { kind: string; uploadId: string }[]
  wrappedMeta: string | null
  wrappedExif: string | null
}

export function galleryFakeStore() {
  const objects = new Map<string, Uint8Array>()
  const sessions = new Map<string, Session>()
  const media: MediaRecord[] = []
  let upCounter = 0
  let mediaCounter = 0

  const blobOf = (uploadId: string) => {
    const s = sessions.get(uploadId)!
    return { blobId: uploadId, chunkCount: s.chunkCount, byteSize: 0, wrappedBlobKey: s.wrappedBlobKey }
  }
  const getsFor = (uploadId: string) =>
    Array.from({ length: sessions.get(uploadId)!.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` }))

  const api = {
    startBlobUploads: vi.fn(async ({ blobUploadsBatchRequest }: any) => ({
      results: blobUploadsBatchRequest.items.map((item: any) => {
        const uploadId = `up-${upCounter++}`
        sessions.set(uploadId, { wrappedBlobKey: item.wrappedBlobKey, chunkCount: item.chunkCount })
        return {
          uploadId,
          presignedPuts: Array.from({ length: item.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` })),
          error: null,
        }
      }),
    })),
    completeMedia: vi.fn(async ({ mediaBatchRequest }: any) => ({
      results: mediaBatchRequest.items.map((item: any) => {
        const mediaId = `media-${mediaCounter++}`
        media.push({
          mediaId,
          originalUploadId: item.originalUploadId,
          derivatives: item.derivatives ?? [],
          wrappedMeta: item.wrappedMeta ?? null,
          wrappedExif: item.wrappedExif ?? null,
        })
        return { blobId: `blob-${item.originalUploadId}`, mediaId, error: null }
      }),
    })),
    // cr-0107: the MediaListPage envelope (delta rows + both revisions + optional id-set). This fake always
    // returns the full list with a revision = the media count (so a poll after an add sees a higher revision);
    // it doesn't emulate the 304 / since-cursor filtering — the component tests exercise the initial render.
    listNodeMedia: vi.fn(async () => ({
      media: media.map((m, i) => ({
        mediaId: m.mediaId,
        ...blobOf(m.originalUploadId),
        derivatives: m.derivatives.map((d) => ({ kind: d.kind, ...blobOf(d.uploadId) })),
        wrappedMeta: m.wrappedMeta, // the listed-metadata tier is listed, for grid layout + date sorting (cr-0068)
        updatedAt: new Date(1_700_000_000_000 + i * 1000), // distinct per media → a valid keyset cursor (cr-0107)
        // wrappedExif is deliberately NOT listed — it is lazy via getMediaExif (cr-0064)
      })),
      mediaRevision: media.length,
      deleteRevision: 0,
      mediaIds: null,
    })),
    // The heavy EXIF, echoed only through the exif endpoint (kept out of the list) — operator-blind ciphertext.
    getMediaExif: vi.fn(async ({ mediaId }: any) => ({
      wrappedExif: media.find((m) => m.mediaId === mediaId)?.wrappedExif ?? null,
    })),
    // Overwrite a media's sealed listed-metadata tier verbatim (cr-0068) — e.g. a corrected date.
    updateMediaMeta: vi.fn(async ({ mediaId, mediaMetaUpdate }: any) => {
      const rec = media.find((m) => m.mediaId === mediaId)
      if (rec) rec.wrappedMeta = mediaMetaUpdate.wrappedMeta ?? null
      return { wrappedMeta: mediaMetaUpdate.wrappedMeta ?? null }
    }),
    presignBlobDownloads: vi.fn(async ({ blobDownloadsBatchRequest }: any) => ({
      results: blobDownloadsBatchRequest.items.map((it: any) => ({ presignedGets: getsFor(it.blobId), error: null })),
    })),
  }

  const fetchStub = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      objects.set(url, new Uint8Array(init.body as Uint8Array))
      return { ok: true, status: 200 } as Response
    }
    const body = objects.get(url)
    if (!body) return { ok: false, status: 404 } as Response
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer } as unknown as Response
  })

  return { api, fetchStub, objects, media }
}
