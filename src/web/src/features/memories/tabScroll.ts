// Where the page stands when a member switches a Memory's tab. The panels are Reka's `TabsContent`, which unmounts the
// tab that is left: the window (the one scroller, `MemoryDetail`'s sheet) loses the old panel's height before the new
// one is drawn, the browser clamps the scroll to what is left, and the page jumped back to the banner on every switch.
// This keeps it where it was: with the tab row stuck, the row STAYS stuck and the picked tab opens where the member left
// it (its start on a first visit); with the row not yet stuck, the page keeps its offset. The panels' minimum height
// (`MemoryDetail`'s sheet) is what lets the row stay stuck over a short tab.
//
// Only a member's click goes through here (`select`): a switch the page makes itself (a search opening a photo, another
// Memory or chapter) keeps its old behaviour.
import { nextTick, type Ref } from 'vue'

/** How many frames a restore waits for the picked tab to reach its height (the gallery lays out once it measured). */
const SETTLE_FRAMES = 10

export interface TabScrollGeometry {
  /** The tabs' root: the tab row is its first child, so its top is the row's place before it sticks. */
  root: () => HTMLElement | null
  /** The `top` the row sticks at (the shell's sticky chrome above it). */
  stickyTop: () => number
}

export function useTabScroll<T extends string>(active: Ref<T>, geometry: TabScrollGeometry) {
  /** Per tab, how far below the row's sticking point the member stood when they left it. */
  const offsets = new Map<T, number>()

  /** The scroll at which the row sticks, from where the tabs' root lies in the document. */
  function stickPoint(): number | null {
    const root = geometry.root()
    if (!root) return null
    return Math.max(0, root.getBoundingClientRect().top + window.scrollY - geometry.stickyTop())
  }

  async function select(next: T) {
    const prev = active.value
    if (next === prev) return
    const before = window.scrollY
    const stick = stickPoint()
    const stuck = stick !== null && before >= stick - 1
    if (stuck) offsets.set(prev, before - stick)
    active.value = next
    if (stick === null) return
    await nextTick()
    let frames = 0
    const restore = () => {
      if (active.value !== next) return // switched on meanwhile: that switch restores
      const now = stickPoint()
      if (now === null) return
      const target = stuck ? now + (offsets.get(next) ?? 0) : before
      window.scrollTo({ top: target, left: window.scrollX, behavior: 'instant' })
      if (Math.abs(window.scrollY - target) > 1 && frames++ < SETTLE_FRAMES) requestAnimationFrame(restore)
    }
    restore()
  }

  /** Another Memory or chapter: the offsets were about the one left. */
  function forget() {
    offsets.clear()
  }

  return { select, forget }
}
