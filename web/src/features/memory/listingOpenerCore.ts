// What the listing opener Worker does (ADR 0105 decision 3, inbox #489), as a plain function of its messages so a unit
// test runs it without a Worker: it holds the content keys of the Memories the page has OPEN — `K_node` and the keys of
// its WRITE_ONLY uploaders, each derived on the page exactly as `mediaKey` derives them — opens listed seals and
// thumbnail frames under them, and forgets a Memory's keys the moment the page closes it. A request for a Memory it
// does not hold (never opened, or closed) is refused, never answered under another key.
//
// It is the page's own origin and the page's own code, so it is the page's trust tier (ADR 0026): no key leaves the
// browser, no party sees one it does not see today. It imports the pure crypto alone (`mediaOpen.ts`, `core/blob/keys.ts`)
// — never the app — so the Worker chunk stays small and carries nothing that could reach the network.
import { openMediaMeta } from './mediaOpen'
import { openFrames, readBlobMeta, type WrappedBlobKey } from '../../core/blob/keys'
import type { OpenedBlobMeta } from '../../core/blob/meta'
import type { MediaMeta } from './media'

/** One listed media as the page hands it over: its id, which of the Memory's keys its seals are under, and what is
 *  still to open — its listed tier (`openMeta`, the seal in `wrappedMeta`, which may be absent: nothing sealed) and its
 *  original's blob meta (`original`, absent when the page already holds it). */
export type OpenItem = {
  mediaId: string
  keyId: string
  openMeta: boolean
  wrappedMeta?: string
  original?: WrappedBlobKey & { wrappedBlobMeta?: string }
}

/** One media, opened: what was asked of it — its listed tier's display projection, its original's blob meta (null
 *  where none opens; absent where it was not asked). */
export type OpenedItem = { mediaId: string; meta?: MediaMeta | null; blobMeta?: OpenedBlobMeta | null }

export type OpenerRequest =
  | { id: number; op: 'open-memory'; memory: number; keys: [string, Uint8Array][] }
  | { id: number; op: 'open-items'; memory: number; scopeId: string; items: OpenItem[] }
  | { id: number; op: 'open-frames'; memory: number; blobKey: Uint8Array; frames: Uint8Array[] }
  | { id: number; op: 'close-memory'; memory: number }

/**
 * - `refused` — the Memory is not open here (never opened, or closed): the page's read is stale and is dropped;
 * - `source`  — the input does not open (a frame that fails its AEAD, a key the page never sent): it would fail the same
 *               way on the page thread, so a frame read fails, and a listing batch is opened on the page instead;
 * - `infra`   — anything else this Worker could not do: the page latches it off and finishes on its own thread.
 */
export type OpenerResponse =
  | { id: number; ok: true; items?: OpenedItem[]; bytes?: Uint8Array }
  | { id: number; ok: false; kind: 'refused' | 'source' | 'infra'; error: string }

/** The refusal of a request for a Memory this opener does not hold. Spelled here and in no other `src/` module: it is
 *  also the sentinel `verify:listing-worker` finds in the emitted Worker chunk. */
export const MEMORY_NOT_OPEN = 'memory_not_open_in_listing_opener'

/** A fresh opener: its keys, per open Memory, and the handler of one message. */
export function createListingOpener() {
  const memories = new Map<number, Map<string, Uint8Array>>()

  function handle(req: OpenerRequest): { response: OpenerResponse; transfer: Transferable[] } {
    const answer = (response: OpenerResponse, transfer: Transferable[] = []) => ({ response, transfer })
    try {
      if (req.op === 'open-memory') {
        const keys = memories.get(req.memory) ?? new Map<string, Uint8Array>()
        for (const [id, key] of req.keys) keys.set(id, key)
        memories.set(req.memory, keys)
        return answer({ id: req.id, ok: true })
      }
      if (req.op === 'close-memory') {
        const keys = memories.get(req.memory)
        if (keys) for (const key of keys.values()) key.fill(0) // this Worker's own copies; the page's are its own
        memories.delete(req.memory)
        return answer({ id: req.id, ok: true })
      }
      const keys = memories.get(req.memory)
      if (!keys) return answer({ id: req.id, ok: false, kind: 'refused', error: MEMORY_NOT_OPEN })
      if (req.op === 'open-frames') {
        let bytes: Uint8Array
        try {
          bytes = openFrames(req.blobKey, req.frames)
        } catch (e) {
          return answer({ id: req.id, ok: false, kind: 'source', error: String(e) })
        }
        return answer({ id: req.id, ok: true, bytes }, [bytes.buffer])
      }
      const opened: OpenedItem[] = []
      for (const item of req.items) {
        const key = keys.get(item.keyId)
        if (!key) return answer({ id: req.id, ok: false, kind: 'source', error: 'key_not_sent' })
        const out: OpenedItem = { mediaId: item.mediaId }
        if (item.openMeta) out.meta = openMediaMeta(req.scopeId, key, item.mediaId, item.wrappedMeta)
        if (item.original) out.blobMeta = readBlobMeta(req.scopeId, key, item.original)
        opened.push(out)
      }
      return answer({ id: req.id, ok: true, items: opened })
    } catch (e) {
      return answer({ id: req.id, ok: false, kind: 'infra', error: String(e) })
    }
  }

  return { handle, /** Memories whose keys are held — for a test. */ held: () => [...memories.keys()] }
}
