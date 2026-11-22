// What a decrypted blob IS, read from its own leading bytes (inbox #412) — the platform's Kotlin `blob/Sniff.kt`, drawn
// through `@veyq/core-js/files` (ADR 0155 decision 7.2, the file facts' slice), one rule for the web and the apps, held by
// contracts/platform/files/v1-vectors.json. A blob's sealed type and an object's name are a PEER's statement about the bytes;
// nothing here lets that statement decide how the bytes are RENDERED: a viewer combines the answer with the claim only to
// NARROW (text needs a text-ish claim too, SVG needs the claim to agree), never to widen. This file holds no rule: it keeps
// the web's types for the answers and turns the façade's `null` into the `undefined` its callers read.
import * as core from '@veyq/core-js/files'

/** The raster image formats a viewer tries to show; HEIC is tried too (Safari decodes it) and falls back on a decode failure. */
export type ImageType = 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp' | 'image/avif' | 'image/bmp' | 'image/heic'

/** How a text is decoded: UTF-8 (a BOM is dropped), or UTF-16 when — and only when — a byte-order mark says so. */
export type TextEncoding = 'utf-8' | 'utf-16le' | 'utf-16be'

/** A streamable container: the element that plays it and the MIME the service worker serves it under. */
export type StreamKind = { element: 'video' | 'audio'; type: string }

/** The raster image format `head` is, by its magic bytes, or undefined. SVG is text and answered by {@link sniffSvg}. */
export function sniffImage(head: Uint8Array): ImageType | undefined {
  return (core.sniffImage(head) ?? undefined) as ImageType | undefined
}

/** The encoding `head` is valid text in, or undefined; `complete`: `head` is the whole file, so a cut sequence is an error. */
export function sniffText(head: Uint8Array, complete = false): TextEncoding | undefined {
  return (core.sniffText(head, complete) ?? undefined) as TextEncoding | undefined
}

/** Whether the CLAIMED type or name says "text" — a claim that may only narrow what the bytes already allow. */
export function claimsText(type: string | null | undefined, name: string | null | undefined): boolean {
  return core.claimsText(type ?? null, name ?? null)
}

/** Whether the CLAIM names SVG: the sealed type or the extension. */
export function claimsSvg(type: string | null | undefined, name: string | null | undefined): boolean {
  return core.claimsSvg(type ?? null, name ?? null)
}

/** The lower-cased extension of `name` (no dot), or '' when it has none. */
export function extensionOf(name: string | null | undefined): string {
  return core.extensionOf(name ?? null)
}

/** Whether `head` is an SVG document by its markup; the caller still requires a claim that agrees ({@link claimsSvg}) and
 *  shows it only as an `<img>` from a blob URL, never inline. */
export function sniffSvg(head: Uint8Array): boolean {
  return core.sniffSvg(head)
}

/** The audio/video container `head` is, by its magic bytes (ADR 0047: the demuxer, not this, decides if it can PLAY it). */
export function sniffStream(head: Uint8Array): StreamKind | undefined {
  const s = core.sniffStream(head)
  return s ? { element: s.element as StreamKind['element'], type: s.type } : undefined
}

/** Whether `head` is a PDF: the `%PDF-` header at byte 0, or within the first KiB where `head` is no text. */
export function sniffPdf(head: Uint8Array): boolean {
  return core.sniffPdf(head)
}
