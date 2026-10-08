// Where a streamed download is saved (inbox #371): the sink `core/blob/stream.ts`'s `streamBlob` writes into. Three, in
// order, the first that is available wins:
//
// 1. **A file the user picks** (File System Access, `showSaveFilePicker` — Chromium desktop): the bytes go straight to
//    disk. The picker needs the click's user activation, so it is the FIRST thing `openSaveTarget` awaits, and a caller
//    must await nothing before `openSaveTarget` in its click path. Cancelling the picker is not a failure.
// 2. **The Service Worker's download route** (`sw/register.ts` `registerDownload`, ADR 0047 § Addendum): an attachment
//    navigation whose body the SW pulls from the page — the browser's own download, any size.
// 3. **An in-memory `Blob`**, saved by `saveBlob` — only up to [BLOB_SAVE_CAP]. Above it there is no streaming target in
//    this browser, and a multi-GB Blob is a tab crash or a silent failure, so it is refused as [SaveTooLarge] for the
//    caller to say so.
//
// Every sink is aborted, never closed, on a failure (`streamBlob`'s rule): a picked file is discarded (`abort()` drops
// the browser's swap file), the SW download is failed, the Blob parts are dropped.
import type { ByteSink } from '../core/blob/stream'
import { registerDownload } from '../sw/register'
import { saveBlob } from './saveBytes'

/** The largest download held in tab memory as one `Blob` when no streaming target exists. Every desktop browser and
 *  current phone holds this; the real ceiling per browser is not measured yet (inbox #371, S0). */
export const BLOB_SAVE_CAP = 512 * 1024 * 1024

/** A download too large for an in-memory save, in a browser with no streaming target. */
export class SaveTooLarge extends Error {
  constructor(readonly size: number) {
    super('save_too_large')
    this.name = 'SaveTooLarge'
  }
}

/** The user cancelled — the save picker, or the browser's own download. Nothing to report. */
export class SaveCancelled extends Error {
  constructor() {
    super('save_cancelled')
    this.name = 'SaveCancelled'
  }
}

/** Whether a failed save is one the user chose: no toast for it. */
export function isSaveCancelled(e: unknown): boolean {
  return e instanceof SaveCancelled || (e instanceof DOMException && e.name === 'AbortError')
}

// --- File System Access, typed locally (not in the DOM lib this project builds against) -------------------------

type FsWritable = {
  write(data: Uint8Array): Promise<void>
  close(): Promise<void>
  abort(reason?: unknown): Promise<void>
}
type FsFileHandle = { createWritable(): Promise<FsWritable> }
type SavePicker = (options?: { suggestedName?: string }) => Promise<FsFileHandle>

function savePicker(): SavePicker | null {
  const picker = (globalThis as { showSaveFilePicker?: unknown }).showSaveFilePicker
  return typeof picker === 'function' ? (picker as SavePicker).bind(globalThis) : null
}

/** The picked file as a sink. `abort` discards what was written — the target file is left as it was. */
function fileSink(writable: FsWritable): ByteSink {
  return {
    write: (bytes) => writable.write(bytes),
    close: () => writable.close(),
    abort: (reason) => writable.abort(reason),
  }
}

/** Collect the bytes and save them as one `Blob` on close. Refuses to grow past `size` — the declared size is what the
 *  cap was checked against. */
export function blobSink(name: string, size: number, save: (blob: Blob, name: string) => void = saveBlob): ByteSink {
  let parts: Uint8Array[] = []
  let held = 0
  return {
    async write(bytes) {
      held += bytes.length
      if (held > size) throw new Error('save_overrun')
      parts.push(bytes)
    },
    async close() {
      // `application/octet-stream` whatever the file is: the anchor downloads it, nothing renders it.
      save(new Blob(parts as BlobPart[], { type: 'application/octet-stream' }), name)
      parts = []
    },
    async abort() {
      parts = []
    },
  }
}

export type SaveTargetDeps = {
  picker?: SavePicker | null
  registerDownload?: typeof registerDownload
  saveBlob?: (blob: Blob, name: string) => void
}

/**
 * Open where a download of `size` plaintext bytes named `name` is saved: a picked file, else the SW's download route,
 * else an in-memory Blob up to [BLOB_SAVE_CAP]. Throws [SaveCancelled] when the user dismissed the picker and
 * [SaveTooLarge] when only the Blob is left and the file is over the cap. Call it before awaiting anything else in a
 * click handler: the picker is the first await here, and only a click's activation may open it.
 */
export async function openSaveTarget(name: string, size: number, deps: SaveTargetDeps = {}): Promise<ByteSink> {
  const picker = deps.picker === undefined ? savePicker() : deps.picker
  if (picker) {
    let handle: FsFileHandle | null = null
    try {
      handle = await picker({ suggestedName: name })
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw new SaveCancelled()
      // Any other refusal (no activation left, a blocked name, a policy) is not the user's choice: try the next sink.
    }
    if (handle) return fileSink(await handle.createWritable())
  }
  const viaSw = await (deps.registerDownload ?? registerDownload)({ name, size })
  if (viaSw) return viaSw
  if (size > BLOB_SAVE_CAP) throw new SaveTooLarge(size)
  return blobSink(name, size, deps.saveBlob)
}
