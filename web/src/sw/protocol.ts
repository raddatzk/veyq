// cr-inline-video-playback Slice 2 (ADR 0047) — the page↔Service-Worker wire protocol.
//
// Shared by the main-thread register.ts and the worker-side mediaSw.ts. Pure types, constants and one pure
// predicate: NO globals, NO key material. The SW is key-free by design — every message below carries handles,
// indices and plaintext, never a key or an auth token.

/**
 * Protocol version. Bump on ANY breaking change to the messages below. register.ts sends it in the handshake; a
 * controlling SW built at a different version answers a mismatch (or nothing), and the page falls back to the
 * Slice-1 full-blob path. This is what lets a freshly-deployed page detect a stale still-controlling SW.
 */
export const PROTOCOL_VERSION = 2

/** The synthetic in-origin path the SW intercepts. `<handle>` is opaque + per-playback — never a blobId (ADR 0047). */
export const MEDIA_PATH_PREFIX = '/__media/'

/** One `video/<subtype>` MIME essence: no list, no parameters, no whitespace — the subtype is an RFC 9110 token. */
const VIDEO_ESSENCE = /^video\/[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/

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

// --- page → SW (via the SW's own postMessage) --------------------------------

/** Handshake probe: the page asks a controlling SW for its protocol version (reply on the transferred port). */
export type HandshakeMsg = { type: 'veyq-handshake'; version: number }
/** Handshake reply: the SW echoes the version it speaks. A match means the page may use the SW path. */
export type HandshakeAck = { type: 'veyq-handshake-ack'; version: number }

/** Register a playback: hand the SW its metadata + a MessagePort to pull plaintext chunks over. `mediaType` is
 *  the sealed MIME the page already holds — given to the SW only to set the response `Content-Type` so the demuxer
 *  picks the right container, and refused unless [isVideoMediaType]. It is on-device code; the SW never sends it
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

export type PageToSwMessage = HandshakeMsg | RegisterMsg | RevokeMsg | EndSessionMsg

// --- SW ↔ page (over the per-playback MessagePort) ---------------------------

/** SW → page: give me the decrypted plaintext of chunk `index`; `id` correlates the reply. */
export type ChunkRequest = { type: 'veyq-chunk-req'; id: number; index: number }
/** page → SW: the plaintext of the requested chunk (transferred as an ArrayBuffer), or an error. */
export type ChunkReply =
  | { type: 'veyq-chunk-res'; id: number; bytes: ArrayBuffer }
  | { type: 'veyq-chunk-err'; id: number; error: string }
