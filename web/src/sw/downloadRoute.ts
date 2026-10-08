// The Service Worker's download route (inbox #371; ADR 0047 § Addendum): the SW half of a streamed download. Like the
// playback shim it holds NO key, NO auth token and NO plaintext at rest. The page registers a download — an opaque
// one-shot handle, the plaintext size, the file name and a MessagePort — then navigates to `/__download/<handle>`; the
// SW answers that ONE navigation with an attachment whose body pulls the page's decrypted bytes over the port, one
// credit at a time. A download is all-or-nothing as far as the SW can tell: a page that fails, a stream that ends short
// of or runs past the registered size, or an ended session errors the body, so the browser marks the download failed
// instead of saving a truncated file as if it were whole.
//
// Typechecks under the DOM lib like `mediaSw.ts` (see its header); uses only DOM-available types.
import type { DownloadGone, PageToSwDownloadMessage, SwToPageDownloadMessage } from './protocol'

/** How long a registered handle waits for its navigation. The page navigates right after the SW's ack, so this only
 *  bounds a handle whose navigation never came (a blocked download, a page that died) — it never lives on. */
export const DOWNLOAD_HANDLE_TTL_MS = 30_000

/** Bidi controls that would let a name like `invoice\u202Etxt.exe` show an extension it does not have. */
const BIDI_CONTROLS = /[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g
/** C0, DEL and C1 controls — CR/LF among them, which would otherwise split the header. */
const CONTROLS = /[\u0000-\u001f\u007f-\u009f]/g
const LONE_SURROGATE = /[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/g
const MAX_NAME_CODE_POINTS = 200

/** The name the SW serves: the page's name without controls, bidi overrides or path separators, well-formed, bounded,
 *  and never empty or a bare run of dots. The browser sanitises again; this is what the SW itself vouches for. */
export function safeFilename(name: string): string {
  const cleaned = name
    .replace(LONE_SURROGATE, '\ufffd')
    .normalize('NFC')
    .replace(CONTROLS, '')
    .replace(BIDI_CONTROLS, '')
    .replace(/[\\/]/g, '_')
    .trim()
  const bounded = Array.from(cleaned).slice(0, MAX_NAME_CODE_POINTS).join('')
  return bounded === '' || /^\.+$/.test(bounded) ? 'download' : bounded
}

/** RFC 8187 `ext-value` encoding: percent-encode everything outside `attr-char`. */
const extValue = (s: string) =>
  encodeURIComponent(s).replace(/['()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`)

/** `attachment` with an ASCII `filename` fallback (quote, backslash, `%` and non-ASCII replaced) and the exact name as
 *  `filename*=UTF-8''…`. The whole value is ASCII, so it is a valid header byte string whatever the name held. */
export function contentDisposition(name: string): string {
  const safe = safeFilename(name)
  const ascii = safe.replace(/[^\x20-\x7e]/g, '_').replace(/["\\%]/g, '_')
  return `attachment; filename="${ascii}"; filename*=UTF-8''${extValue(safe)}`
}

/** One registered download: the port back to the page and, once its navigation came, the response body. */
export class DownloadSession {
  private controller: ReadableStreamDefaultController<Uint8Array> | null = null
  private pendingPull: (() => void) | null = null
  private sent = 0
  private finished = false

  /** Ended — completed, failed, cancelled or dropped. */
  get done(): boolean {
    return this.finished
  }

  constructor(
    readonly size: number,
    readonly name: string,
    private readonly port: MessagePort,
  ) {
    this.port.onmessage = (e: MessageEvent) => this.onPage(e.data as PageToSwDownloadMessage)
    this.port.start?.()
  }

  private post(msg: SwToPageDownloadMessage): void {
    this.port.postMessage(msg)
  }

  /** The SW holds the registration: the page may navigate. */
  acknowledge(): void {
    this.post({ type: 'veyq-dl-registered' })
  }

  /** The response body. Each pull asks the page for one chunk and resolves when it (or the end, or a failure) came. */
  body(): ReadableStream<Uint8Array> {
    return new ReadableStream<Uint8Array>({
      start: (controller) => {
        this.controller = controller
      },
      pull: () =>
        new Promise<void>((resolve) => {
          if (this.finished) return resolve()
          this.pendingPull = resolve
          this.post({ type: 'veyq-dl-pull' })
        }),
      cancel: () => {
        if (this.finished) return
        this.post({ type: 'veyq-dl-cancel' })
        this.finish()
      },
    })
  }

  private onPage(msg: PageToSwDownloadMessage): void {
    if (this.finished || !msg || typeof msg !== 'object') return
    if (msg.type === 'veyq-dl-error') return this.fail(new Error(msg.error))
    const controller = this.controller
    const pull = this.pendingPull
    // Bytes or an end without a credit is a page breaking the protocol: nothing it sends is trusted after that.
    if (!controller || !pull) return this.fail(new Error('download_protocol'))
    this.pendingPull = null
    if (msg.type === 'veyq-dl-data') {
      const bytes = new Uint8Array(msg.bytes)
      this.sent += bytes.length
      if (this.sent > this.size) return this.fail(new Error('download_overrun'))
      controller.enqueue(bytes)
      pull()
    } else if (msg.type === 'veyq-dl-end') {
      if (this.sent !== this.size) return this.fail(new Error('download_short'))
      this.finish()
      controller.close()
      pull()
    }
  }

  /** Error the body (the browser fails the download) and release the port. Never a clean close. */
  private fail(error: Error): void {
    if (this.finished) return
    const pull = this.pendingPull
    this.finish()
    this.controller?.error(error)
    pull?.()
  }

  /** Drop the download from the SW's side — its handle expired, or the session ended — telling the page why. */
  drop(reason: DownloadGone['reason']): void {
    if (this.finished) return
    this.post({ type: 'veyq-dl-gone', reason })
    this.fail(new Error(`download_${reason}`))
  }

  private finish(): void {
    this.finished = true
    this.pendingPull = null
    this.port.onmessage = null
    this.port.close?.()
  }
}

export type DownloadRegistry = {
  /** Hold a download until its navigation, at most {@link DOWNLOAD_HANDLE_TTL_MS}. */
  register(handle: string, session: DownloadSession): void
  /** The download for `handle`, removed: a handle serves exactly one navigation. */
  take(handle: string): DownloadSession | undefined
  /** End every download, waiting or streaming: a session ended. */
  dropAll(): void
}

export function makeDownloadRegistry(ttlMs = DOWNLOAD_HANDLE_TTL_MS): DownloadRegistry {
  const waiting = new Map<string, { session: DownloadSession; timer: ReturnType<typeof setTimeout> }>()
  const streaming = new Set<DownloadSession>()
  const remove = (handle: string) => {
    const entry = waiting.get(handle)
    if (entry) clearTimeout(entry.timer)
    waiting.delete(handle)
    return entry?.session
  }
  return {
    register: (handle, session) => {
      remove(handle)?.drop('expired')
      const timer = setTimeout(() => remove(handle)?.drop('expired'), ttlMs)
      waiting.set(handle, { session, timer })
      session.acknowledge()
    },
    take: (handle) => {
      streaming.forEach((s) => s.done && streaming.delete(s))
      const session = remove(handle)
      if (session) streaming.add(session)
      return session
    },
    dropAll: () => {
      for (const handle of [...waiting.keys()]) remove(handle)?.drop('ended')
      streaming.forEach((session) => session.drop('ended'))
      streaming.clear()
    },
  }
}
