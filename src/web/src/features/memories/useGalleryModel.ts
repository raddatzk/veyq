// How the web observes Memories' gallery model (ADR 0155 decision 8, § The gallery model's move): the one composable over a
// Kotlin model with a flow — `GalleryModel`, through `@veyq/core-js/memories`. The model is an opaque handle, held raw;
// its state crosses as a plain value into one `shallowRef`, replaced whole on each change (never Vue's deep reactivity
// over a Kotlin object); the watch and the model end with the scope. The live poll runs while the tab is visible — the
// model's poll asks nothing in the background, and a page shown again catches up at once (core's `LivePoll`).
import { markRaw, onScopeDispose, shallowRef, type ShallowRef } from 'vue'
import * as core from '@veyq/core-js/memories'
import type { GalleryDoorShape, GalleryHandle, GalleryStateShape, GalleryWatch } from '@veyq/core-js/memories'

export interface GalleryModelOpen {
  door: GalleryDoorShape
  scopeId: string
  nodeId: string
  /** `MEMORY` or `CHAPTER`. */
  kind: string
  /** A link guest's role (`READ`, `READ_WRITE`); null for a member. */
  role: string | null
  /** A member who may not delete (no trash door). */
  readonly: boolean
}

/** The state before the model's first answer: loading, nothing listed. */
export const GALLERY_LOADING: GalleryStateShape = Object.freeze({
  status: 'loading', rows: [], music: [], tracks: [], undo: false, purging: null, said: null,
})

/** One open model: its handle (raw), its state, and its end. */
export interface OpenGallery {
  readonly handle: GalleryHandle
  readonly state: ShallowRef<GalleryStateShape>
  close(): void
}

/**
 * [open]'s page as a model: its state in [state], the live poll while the document is visible, everything ended by
 * [OpenGallery.close] — called by the caller on a node switch, and at the latest when the calling scope ends.
 */
export function useGalleryModel(state: ShallowRef<GalleryStateShape> = shallowRef(GALLERY_LOADING)) {
  let current: { handle: GalleryHandle; watch: GalleryWatch; live: GalleryWatch | null; onVisibility: () => void } | null = null

  function close() {
    const c = current
    if (!c) return
    current = null
    document.removeEventListener('visibilitychange', c.onVisibility)
    if (c.live) core.memoriesGalleryUnwatch(c.live)
    core.memoriesGalleryUnwatch(c.watch)
    core.memoriesGalleryClose(c.handle)
  }

  function open(o: GalleryModelOpen): OpenGallery {
    close()
    state.value = GALLERY_LOADING
    const handle = markRaw(core.memoriesGallery(o.door, o.scopeId, o.nodeId, o.kind, o.role, o.readonly))
    const c: NonNullable<typeof current> = {
      handle,
      watch: core.memoriesGalleryWatch(handle, (s) => {
        if (current?.handle === handle) state.value = s
      }),
      live: null,
      onVisibility: () => {
        if (current !== c) return
        if (document.visibilityState === 'hidden') {
          if (c.live) core.memoriesGalleryUnwatch(c.live)
          c.live = null
        } else if (!c.live) c.live = core.memoriesGalleryLive(handle)
      },
    }
    current = c
    document.addEventListener('visibilitychange', c.onVisibility)
    c.onVisibility()
    return { handle, state, close: () => { if (current === c) close() } }
  }

  onScopeDispose(close)
  return { state, open, close, handle: () => current?.handle ?? null }
}
