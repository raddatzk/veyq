// cr-inline-video-playback Slice 2 (ADR 0047) — main-thread Service-Worker registration + per-playback wiring.
//
// This is the page side of the key-free delegation: it registers the SW, does the versioned handshake, and for
// each playback opens a MessagePort whose page end answers the SW's chunk requests by running the given
// ChunkProvider (presign→openFrame, in the page). Keys and the auth context NEVER cross to the SW — only opaque
// handles and decrypted plaintext do. If no SW controls the page, or the handshake version mismatches, every
// entry point resolves to "no SW" so the caller falls back to the Slice-1 full-blob path.

import type { ByteSink } from '../core/blob/stream'
import {
  DOWNLOAD_PATH_PREFIX,
  MEDIA_PATH_PREFIX,
  PROTOCOL_VERSION,
  type ChunkReply,
  type ChunkRequest,
  type EndSessionAck,
  type EndSessionMsg,
  type ChunkProvider,
  type DownloadKeepaliveMsg,
  type PageToSwDownloadMessage,
  type RegisterDownloadMsg,
  type SwToPageDownloadMessage,
} from './protocol'

/** Metadata the SW needs to size the resource + set the response Content-Type; carries no key and no id that
 *  leaks what the blob is to the operator (the SW is on-device code — see protocol.ts RegisterMsg). */
export type PlaybackMeta = { chunkCount: number; byteSize: number; mediaType: string }

let registration: Promise<ServiceWorkerRegistration | null> | undefined
const active = new Map<string, { channel: MessageChannel; sw: ServiceWorker }>()

/** Register the media SW once, lazily (on first play). Resolves null when SWs are unavailable or registration
 *  fails — the caller then uses the Slice-1 fallback. `updateViaCache:'none'` + the stable `/sw.js` name is what
 *  makes a redeployed SW detectable. */
export function ensureMediaSw(): Promise<ServiceWorkerRegistration | null> {
  if (registration) return registration
  registration = (async () => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return null
    try {
      return await navigator.serviceWorker.register('/sw.js', { type: 'module', updateViaCache: 'none' })
    } catch {
      return null
    }
  })()
  return registration
}

/** The SW that actually controls this page, or null. Waits briefly for `controllerchange` on a first install
 *  (the SW claims clients in `activate`, but an already-open page is uncontrolled until that claim lands). */
async function controllingSw(waitMs = 3000): Promise<ServiceWorker | null> {
  const reg = await ensureMediaSw()
  if (!reg) return null
  await navigator.serviceWorker.ready
  if (navigator.serviceWorker.controller) return navigator.serviceWorker.controller
  return new Promise((resolve) => {
    const done = (sw: ServiceWorker | null) => {
      clearTimeout(timer)
      navigator.serviceWorker.removeEventListener('controllerchange', onChange)
      resolve(sw)
    }
    const onChange = () => done(navigator.serviceWorker.controller)
    const timer = setTimeout(() => done(null), waitMs)
    navigator.serviceWorker.addEventListener('controllerchange', onChange)
  })
}

/** Probe a controlling SW's protocol version; true only if it answers our exact PROTOCOL_VERSION in time. */
async function handshake(sw: ServiceWorker, timeoutMs = 2000): Promise<boolean> {
  return new Promise((resolve) => {
    const ch = new MessageChannel()
    const finish = (ok: boolean) => {
      clearTimeout(timer)
      ch.port1.onmessage = null
      ch.port1.close()
      resolve(ok)
    }
    const timer = setTimeout(() => finish(false), timeoutMs)
    ch.port1.onmessage = (e: MessageEvent) => {
      const d = e.data as { type?: string; version?: number }
      finish(d?.type === 'veyq-handshake-ack' && d.version === PROTOCOL_VERSION)
    }
    sw.postMessage({ type: 'veyq-handshake', version: PROTOCOL_VERSION }, [ch.port2])
  })
}

/** An opaque, unguessable per-playback handle — never a blobId (ADR 0047). */
function newHandle(): string {
  return crypto.randomUUID()
}

/**
 * Register a playback with the controlling SW and return its `/__media/<handle>` URL to set as the `<video>` src,
 * or null if no SW is usable (caller falls back to Slice 1). The page end of the port answers each chunk request
 * by running `provider.getPlaintextChunk` and transferring the plaintext back — the SW never sees a key.
 */
export async function registerPlayback(meta: PlaybackMeta, provider: ChunkProvider): Promise<string | null> {
  const sw = await controllingSw()
  if (!sw || !(await handshake(sw))) return null

  const handle = newHandle()
  const channel = new MessageChannel()
  channel.port1.onmessage = async (e: MessageEvent) => {
    const req = e.data as ChunkRequest
    if (req?.type !== 'veyq-chunk-req') return
    try {
      const bytes = await provider.getPlaintextChunk(req.index)
      // Copy into a standalone buffer so we can transfer it (the provider's bytes may be a view into a larger one).
      const copy = bytes.slice()
      channel.port1.postMessage({ type: 'veyq-chunk-res', id: req.id, bytes: copy.buffer } satisfies ChunkReply, [
        copy.buffer,
      ])
    } catch (err) {
      channel.port1.postMessage({ type: 'veyq-chunk-err', id: req.id, error: String(err) } satisfies ChunkReply)
    }
  }
  channel.port1.start?.()
  sw.postMessage(
    {
      type: 'veyq-register-playback',
      handle,
      chunkCount: meta.chunkCount,
      byteSize: meta.byteSize,
      mediaType: meta.mediaType,
    },
    [channel.port2],
  )
  active.set(handle, { channel, sw })
  return MEDIA_PATH_PREFIX + handle
}

/** Tear down a playback: tell the SW to drop its session (+ its plaintext cache) and close the page's port. */
export function revokePlayback(handle: string): void {
  const wired = active.get(handle)
  if (!wired) return
  active.delete(handle)
  wired.sw.postMessage({ type: 'veyq-revoke-playback', handle })
  wired.channel.port1.onmessage = null
  wired.channel.port1.close()
}

/**
 * A session ended (uc.account.sign-out § The ending, step 2): tell the ACTIVE media SW to drop every playback it
 * holds, and wait for its ack. Resolves once the SW has confirmed, or after `timeoutMs`, or at once when no SW
 * is registered. Never throws — an ending must not stop here.
 *
 * It asks the registration for its active worker rather than using `controller`, because another tab may be the
 * one playing: a page opened with a hard reload is not controlled, and the SW still holds that tab's plaintext.
 * It never registers a SW either — with none registered there is nothing to drop. The message is not
 * version-gated like a playback: dropping plaintext is safe to ask of any worker, and a stale one that does not
 * know it simply lets the timeout run.
 */
export async function endAllPlaybacks(timeoutMs = 1000): Promise<void> {
  for (const end of [...activeDownloads]) end(new Error('download_ended'))
  for (const wired of active.values()) {
    wired.channel.port1.onmessage = null
    wired.channel.port1.close()
  }
  active.clear()
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
  const sw = (await navigator.serviceWorker.getRegistration().catch(() => undefined))?.active
  if (!sw) return
  await new Promise<void>((resolve) => {
    const ch = new MessageChannel()
    const finish = () => {
      clearTimeout(timer)
      ch.port1.onmessage = null
      ch.port1.close()
      resolve()
    }
    const timer = setTimeout(finish, timeoutMs)
    ch.port1.onmessage = (e: MessageEvent) => {
      if ((e.data as EndSessionAck | undefined)?.type === 'veyq-end-session-ack') finish()
    }
    sw.postMessage({ type: 'veyq-end-session' } satisfies EndSessionMsg, [ch.port2])
  })
}

// --- streamed downloads (inbox #371; ADR 0047 § The download route) --------------------------------------------------

/** What the SW needs to serve a download: the exact plaintext size (its `Content-Length`) and the file name. */
export type DownloadMeta = { name: string; size: number }

export type DownloadOptions = {
  /** Starts the download: a top-level navigation to the handle's URL (a link click by default; tests replace it). */
  navigate?: (url: string) => void
  /** How long to wait for the SW to acknowledge the registration before giving up (the caller falls back). */
  ackTimeoutMs?: number
  /** How long, after the navigation, the first pull may take before the download is given up as never started. */
  startTimeoutMs?: number
  /** How often the page pings the SW's global scope while the download runs (a port message does not keep it alive). */
  keepaliveMs?: number
}

/** The downloads this page is streaming, each by its "end now" function — sign-out ends them all. */
const activeDownloads = new Set<(reason: unknown) => void>()

/** A navigation to `url` in this tab. The SW answers it as an attachment, so the page stays; never a `download`
 *  attribute (that is a browser download the SW may not see) and never a new window (a popup to block). */
function navigateByLink(url: string): void {
  const a = document.createElement('a')
  a.href = url
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/**
 * Register a streamed download with the controlling SW, start it, and return the sink its bytes go into — or null when
 * no SW at our protocol version controls the page or it did not acknowledge in time (the caller then picks another
 * sink; nothing was written). The sink is credit-based: `write` resolves once the SW asked for the next chunk, so the
 * browser's own download speed is the backpressure. `close` ends the file cleanly and `abort` fails it (the browser
 * marks it failed — never a truncated file saved as whole). A write rejects with an `AbortError` when the browser
 * cancelled the download, and with an Error when the SW dropped it (never fetched, or the session ended). The SW sees
 * only the handle, the size, the name and the plaintext — never a key or a token.
 */
export async function registerDownload(meta: DownloadMeta, options: DownloadOptions = {}): Promise<ByteSink | null> {
  const { navigate = navigateByLink, ackTimeoutMs = 2000, startTimeoutMs = 35_000, keepaliveMs = 10_000 } = options
  const sw = await controllingSw()
  if (!sw || !(await handshake(sw))) return null

  const handle = newHandle()
  const channel = new MessageChannel()
  const port = channel.port1
  let credits = 0
  let pulled = false
  let failure: unknown = null
  let ended = false
  let waiter: { resolve: () => void; reject: (e: unknown) => void } | null = null
  let onAck: (() => void) | null = null
  const timers: { keepalive?: ReturnType<typeof setInterval>; start?: ReturnType<typeof setTimeout> } = {}

  const cleanup = () => {
    ended = true
    clearInterval(timers.keepalive)
    clearTimeout(timers.start)
    // The port is not closed here: a final message just posted must still reach the SW, which closes its end.
    port.onmessage = null
    activeDownloads.delete(fail)
  }
  function fail(e: unknown) {
    if (ended) return
    failure = e
    waiter?.reject(e)
    waiter = null
    cleanup()
  }
  const post = (msg: PageToSwDownloadMessage, transfer: Transferable[] = []) => port.postMessage(msg, transfer)

  port.onmessage = (e: MessageEvent) => {
    const msg = e.data as SwToPageDownloadMessage | undefined
    if (msg?.type === 'veyq-dl-registered') onAck?.()
    else if (msg?.type === 'veyq-dl-pull') {
      if (!pulled) clearTimeout(timers.start)
      pulled = true
      credits += 1
      waiter?.resolve()
      waiter = null
    } else if (msg?.type === 'veyq-dl-cancel') fail(new DOMException('Download cancelled', 'AbortError'))
    else if (msg?.type === 'veyq-dl-gone') fail(new Error(`download_${msg.reason}`))
  }
  port.start?.()

  const acked = await new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => resolve(false), ackTimeoutMs)
    onAck = () => {
      clearTimeout(timer)
      resolve(true)
    }
    sw.postMessage(
      { type: 'veyq-register-download', handle, size: meta.size, name: meta.name } satisfies RegisterDownloadMsg,
      [channel.port2],
    )
  })
  onAck = null
  if (!acked) {
    cleanup()
    port.close()
    return null
  }

  activeDownloads.add(fail)
  timers.start = setTimeout(() => fail(new Error('download_not_started')), startTimeoutMs)
  timers.keepalive = setInterval(() => sw.postMessage({ type: 'veyq-download-keepalive' } satisfies DownloadKeepaliveMsg), keepaliveMs)
  navigate(DOWNLOAD_PATH_PREFIX + encodeURIComponent(handle))

  const credit = async () => {
    if (failure) throw failure
    if (ended) throw new Error('download_closed')
    if (credits === 0) await new Promise<void>((resolve, reject) => (waiter = { resolve, reject }))
    credits -= 1
  }
  return {
    async write(bytes) {
      await credit()
      const copy = bytes.slice() // a standalone buffer to transfer (the chunk may be a view into a larger one)
      post({ type: 'veyq-dl-data', bytes: copy.buffer }, [copy.buffer])
    },
    async close() {
      await credit()
      post({ type: 'veyq-dl-end' })
      cleanup()
    },
    async abort() {
      if (ended) return
      post({ type: 'veyq-dl-error', error: 'page_failed' }) // no detail: the SW only needs to fail the download
      cleanup()
    },
  }
}

/** Extract the handle from a `/__media/<handle>` URL produced by {@link registerPlayback} (for revoke on close). */
export function handleFromUrl(url: string): string | null {
  const path = url.startsWith('http') ? new URL(url).pathname : url
  return path.startsWith(MEDIA_PATH_PREFIX) ? decodeURIComponent(path.slice(MEDIA_PATH_PREFIX.length)) : null
}
