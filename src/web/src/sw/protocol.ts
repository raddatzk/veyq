// ADR 0047 — the page↔Service-Worker wire protocol.
//
// Shared by the main-thread register.ts and the worker-side mediaSw.ts. Pure types, constants and one pure
// predicate: NO globals, NO key material. The SW is key-free by design — every message below carries handles,
// indices and plaintext, never a key or an auth token.

/**
 * Protocol version. Bump on ANY breaking change to the messages below (4: a playback may be `audio/*` too, inbox #412; 5: a
 * download's SW-side failure and its clean end reach the page, `veyq-dl-failed` / `veyq-dl-done`, F11). register.ts sends it in the handshake; a
 * controlling SW built at a different version answers a mismatch (or nothing), and the page falls back to the
 * full-blob path. This is what lets a freshly-deployed page detect a stale still-controlling SW.
 */
export const PROTOCOL_VERSION = 5

/** The synthetic in-origin path the SW intercepts. `<handle>` is opaque + per-playback — never a blobId (ADR 0047). */
export const MEDIA_PATH_PREFIX = '/__media/'

/** The synthetic in-origin path of a streamed download (inbox #371): a navigation to `/__download/<handle>` is answered
 *  with the bytes the page streams over the registered port, as an attachment. `<handle>` is opaque and one-shot. */
export const DOWNLOAD_PATH_PREFIX = '/__download/'

/** One `video/<subtype>` or `audio/<subtype>` MIME essence: no list, no parameters, no whitespace — the subtype is an
 *  RFC 9110 token. */
const VIDEO_ESSENCE = /^video\/[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/
const AUDIO_ESSENCE = /^audio\/[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/

/**
 * Whether a sealed `mediaType` names a video — the signal for the tile play badge, the lightbox `<video>` branch
 * and, in the SW, whether a playback may be registered at all. The value is sealed by the **uploader's** client
 * and the shim serves it back as `Content-Type` on the app origin, so a prefix check is not enough: a browser that
 * opens `video/mp4, text/html` as a page resolves the list to its last valid entry and renders a document. Only
 * one well-formed video essence passes (ADR 0047 § The shim serves inert responses).
 */
export function isVideoMediaType(mediaType: string | undefined): boolean {
  return typeof mediaType === 'string' && VIDEO_ESSENCE.test(mediaType)
}

/**
 * Whether `mediaType` is one well-formed audio or video essence - what the SW may serve a playback under (inbox #412 added
 * audio: Depot plays an `<audio>` through the same route). The same rule as [isVideoMediaType]: the value is a peer's, and
 * the shim serves it as `Content-Type` on the app origin, so a list or a parameter never passes.
 */
export function isStreamableMediaType(mediaType: string | undefined): boolean {
  return isVideoMediaType(mediaType) || (typeof mediaType === 'string' && AUDIO_ESSENCE.test(mediaType))
}

/** A per-blob source of decrypted plaintext chunks, addressed by chunk index. Backs the SW range layer; the SW
 *  never sees a key — it calls {@link ChunkProvider.getPlaintextChunk} over a port and slices the result. */
export type ChunkProvider = {
  /** Total chunk count of the whole blob — the AAD `total` every `getPlaintextChunk` binds (never a subset). */
  readonly chunkCount: number
  /** Total ciphertext byte size (sum of framed chunk sizes). Use [plaintextSize] for the original's own size. */
  readonly byteSize: number
  /** Open exactly chunk `index` to plaintext. Re-presigns the whole blob once on a stale-URL 403, then retries. */
  getPlaintextChunk(index: number): Promise<Uint8Array>
}

// --- page → SW (via the SW's own postMessage) --------------------------------

/** Handshake probe: the page asks a controlling SW for its protocol version (reply on the transferred port). */
export type HandshakeMsg = { type: 'veyq-handshake'; version: number }
/** Handshake reply: the SW echoes the version it speaks. A match means the page may use the SW path. */
export type HandshakeAck = { type: 'veyq-handshake-ack'; version: number }

/** Register a playback: hand the SW its metadata + a MessagePort to pull plaintext chunks over. `mediaType` is
 *  the sealed MIME the page already holds — given to the SW only to set the response `Content-Type` so the demuxer
 *  picks the right container, and refused unless [isStreamableMediaType]. It is on-device code; the SW never sends it
 *  (or anything) to the operator. */
export type RegisterMsg = {
  type: 'veyq-register-playback'
  handle: string
  chunkCount: number
  byteSize: number
  mediaType: string
}
/** Tear a playback down: the SW drops the session + its in-memory plaintext cache. */
export type RevokeMsg = { type: 'veyq-revoke-playback'; handle: string }

/** A session ended (uc.account.sign-out § The ending): drop EVERY playback — whichever tab registered it — with
 *  its pending chunk requests and its plaintext cache. The SW acks on the transferred port once they are gone. */
export type EndSessionMsg = { type: 'veyq-end-session' }
/** The end-session reply: every playback this SW held is gone. */
export type EndSessionAck = { type: 'veyq-end-session-ack' }

/** Register a download (inbox #371): the SW answers ONE navigation to `/__download/<handle>` with `size` bytes the page
 *  pushes over the transferred port, saved as `name`. Neither is trusted by the SW: the name is sanitised into the
 *  `Content-Disposition`, and a stream that ends short of, or runs past, `size` is errored. The SW acks on the port
 *  ({@link DownloadRegistered}) before the page navigates. */
export type RegisterDownloadMsg = { type: 'veyq-register-download'; handle: string; size: number; name: string }
/** Sent to the SW's global scope while a download streams: a message event keeps an idle-terminated SW alive, a
 *  message on the port does not. Carries nothing. */
export type DownloadKeepaliveMsg = { type: 'veyq-download-keepalive' }

export type PageToSwMessage = HandshakeMsg | RegisterMsg | RevokeMsg | EndSessionMsg | RegisterDownloadMsg | DownloadKeepaliveMsg

// --- SW ↔ page (over the per-playback MessagePort) ---------------------------

/** SW → page: give me the decrypted plaintext of chunk `index`; `id` correlates the reply. */
export type ChunkRequest = { type: 'veyq-chunk-req'; id: number; index: number }
/** page → SW: the plaintext of the requested chunk (transferred as an ArrayBuffer), or an error. */
export type ChunkReply =
  | { type: 'veyq-chunk-res'; id: number; bytes: ArrayBuffer }
  | { type: 'veyq-chunk-err'; id: number; error: string }

// --- SW ↔ page (over the per-download MessagePort) ---------------------------
//
// Credit-based: the page sends one `veyq-dl-data` (or the final `veyq-dl-end`) per `veyq-dl-pull` it received, so the
// SW never holds more than one chunk the browser has not consumed. `veyq-dl-error` needs no credit. Every way the SW ends a
// download reaches the page: a clean end is confirmed (`veyq-dl-done`), a stream the SW refuses is reported
// (`veyq-dl-failed`) - a page waiting for a credit that will never come would otherwise wait for ever (F11).

/** SW → page: the registration is held; the page may navigate to the download URL now. */
export type DownloadRegistered = { type: 'veyq-dl-registered' }
/** SW → page: one credit — send the next chunk, or `veyq-dl-end` if there is none. */
export type DownloadPull = { type: 'veyq-dl-pull' }
/** SW → page: the browser cancelled the download (the user, a closed tab): stop. */
export type DownloadCancel = { type: 'veyq-dl-cancel' }
/** SW → page: the handle was never fetched within its time-to-live, or the session ended: it is gone. */
export type DownloadGone = { type: 'veyq-dl-gone'; reason: 'expired' | 'ended' }
/** SW → page: the SW errored the body because of what the page sent - more bytes than registered (`overrun`), an end short
 *  of them (`short`), bytes or an end without a credit (`protocol`). The browser has failed the download. */
export type DownloadFailed = { type: 'veyq-dl-failed'; reason: DownloadFailure }
export type DownloadFailure = 'overrun' | 'short' | 'protocol'
/** SW → page: the end arrived with every registered byte, and the body is closed: the browser has the whole file. */
export type DownloadDone = { type: 'veyq-dl-done' }
export type SwToPageDownloadMessage = DownloadRegistered | DownloadPull | DownloadCancel | DownloadGone | DownloadFailed | DownloadDone

/** page → SW: the next plaintext bytes (a transferred ArrayBuffer), the clean end, or a failure. A failure errors the
 *  response stream, so the browser marks the download failed — never a clean, truncated file. */
export type PageToSwDownloadMessage =
  | { type: 'veyq-dl-data'; bytes: ArrayBuffer }
  | { type: 'veyq-dl-end' }
  | { type: 'veyq-dl-error'; error: string }
