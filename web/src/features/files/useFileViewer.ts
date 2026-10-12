// The state machine of a file page's viewer (inbox #412): `idle` (nothing to read yet) -> `loading` (a fraction) -> one of
// `ready` (an image, a text or a media element's source), `too-large`, `unsupported` (and why), `error` or `cancelled`. The
// reading and the decision are `view.ts`'s; this owns what must be undone: the AbortController of a read in flight, the
// object URL of an image (core's `useObjectUrl`, revoked on replace, on a new target and on unmount) and the service
// worker's playback handle (revoked the same way).
//
// A new target (the page's file was replaced, or the page moved to another file) cancels the read in flight and starts
// again; a result that arrives for an old read is dropped, never shown.
import { onBeforeUnmount, shallowRef, watch, type Ref } from 'vue'
import type { BlobChunkProvider } from '../../core/blob/transport'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import { handleFromUrl, registerPlayback, revokePlayback } from '../../sw/register'
import { loadFileView, type UnsupportedReason, type ViewHints } from './view'

/** What the viewer opens: `id` changes whenever the content does (the blob's id); the provider reads and decrypts it. */
export type ViewerTarget = { id: string; source: BlobChunkProvider; hints: ViewHints }

/** Why a file is not shown: {@link UnsupportedReason}, plus what only the browser can tell once it tried. */
export type ViewerUnsupported = UnsupportedReason | 'codec' | 'no-sw'

export type ReadyView =
  | { kind: 'image'; url: string; svg: boolean }
  | { kind: 'text'; text: string; truncated: boolean }
  | { kind: 'stream'; element: 'video' | 'audio'; type: string; src: string }

export type ViewerState =
  | { status: 'idle' }
  | { status: 'loading'; progress: number }
  | { status: 'ready'; view: ReadyView }
  | { status: 'too-large'; limit: number }
  | { status: 'unsupported'; reason: ViewerUnsupported }
  | { status: 'error' }
  | { status: 'cancelled' }

export function useFileViewer(target: () => ViewerTarget | null): {
  state: Ref<ViewerState>
  /** Read the file again (after an error or a cancel). */
  retry: () => void
  /** Stop a read in flight; the state becomes `cancelled`. */
  cancel: () => void
  /** The page's `<img>` could not decode what was sniffed as an image (HEIC off Safari, a truncated file). */
  imageFailed: () => void
  /** The page's `<video>`/`<audio>` could not play the stream (a codec the browser lacks, a failed read). */
  mediaFailed: () => void
} {
  const state = shallowRef<ViewerState>({ status: 'idle' })
  const image = useObjectUrl()
  let run = 0
  let controller: AbortController | null = null
  let handle: string | null = null

  function release(): void {
    controller?.abort()
    controller = null
    if (handle) revokePlayback(handle)
    handle = null
    image.clear()
  }

  async function open(): Promise<void> {
    release()
    const t = target()
    const mine = ++run
    if (!t) {
      state.value = { status: 'idle' }
      return
    }
    const ctl = new AbortController()
    controller = ctl
    state.value = { status: 'loading', progress: 0 }
    try {
      const view = await loadFileView(t.source, t.hints, {
        signal: ctl.signal,
        onProgress: (progress) => {
          if (mine === run) state.value = { status: 'loading', progress }
        },
      })
      if (mine !== run) return
      if (view.kind === 'image') {
        image.set(view.blob)
        state.value = { status: 'ready', view: { kind: 'image', url: image.url.value!, svg: view.type === 'image/svg+xml' } }
      } else if (view.kind === 'text') {
        state.value = { status: 'ready', view: { kind: 'text', text: view.text, truncated: view.truncated } }
      } else if (view.kind === 'stream') {
        // The page holds the key; the service worker only asks it for plaintext chunks (ADR 0047). No SW, no stream.
        const src = await registerPlayback({ chunkCount: t.source.chunkCount, byteSize: t.source.byteSize, mediaType: view.type }, t.source)
        if (mine !== run) {
          const orphan = src ? handleFromUrl(src) : null
          if (orphan) revokePlayback(orphan)
          return
        }
        if (!src) state.value = { status: 'unsupported', reason: 'no-sw' }
        else {
          handle = handleFromUrl(src)
          state.value = { status: 'ready', view: { kind: 'stream', element: view.element, type: view.type, src } }
        }
      } else if (view.kind === 'too-large') {
        state.value = { status: 'too-large', limit: view.limit }
      } else {
        state.value = { status: 'unsupported', reason: view.reason }
      }
    } catch {
      if (mine !== run) return
      state.value = ctl.signal.aborted ? { status: 'cancelled' } : { status: 'error' }
    }
  }

  function cancel(): void {
    if (state.value.status === 'loading') controller?.abort()
  }
  function imageFailed(): void {
    image.clear()
    state.value = { status: 'unsupported', reason: 'format' }
  }
  function mediaFailed(): void {
    if (handle) revokePlayback(handle)
    handle = null
    state.value = { status: 'unsupported', reason: 'codec' }
  }

  watch(() => target()?.id ?? null, () => void open(), { immediate: true })
  onBeforeUnmount(() => {
    run++ // a read still in flight is dropped when it ends
    release()
  })
  return { state, retry: () => void open(), cancel, imageFailed, mediaFailed }
}
