// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref } from 'vue'
import { useTabScroll } from './tabScroll'

// The page as the browser lays it out: the tabs' root lies at `ROOT_Y` in the document, the row sticks at `STICKY_TOP`,
// so it is stuck from scroll `ROOT_Y - STICKY_TOP` on. `maxScroll` is how far the page can scroll (the clamp a short tab
// causes), `scrollTo` clamps to it as a browser does.
const ROOT_Y = 600
const STICKY_TOP = 56
const STICK = ROOT_Y - STICKY_TOP
let scrollY = 0
let maxScroll = 10_000

function page() {
  const root = document.createElement('div')
  root.getBoundingClientRect = () => ({ top: ROOT_Y - scrollY }) as DOMRect
  return { root: () => root, stickyTop: () => STICKY_TOP }
}

/** What the unmount of the left panel does: the page shrinks and the browser clamps the scroll. */
function shrinkTo(max: number) {
  maxScroll = max
  scrollY = Math.min(scrollY, max)
}

beforeEach(() => {
  scrollY = 0
  maxScroll = 10_000
  vi.spyOn(window, 'scrollY', 'get').mockImplementation(() => scrollY)
  vi.spyOn(window, 'scrollTo').mockImplementation(((opts: ScrollToOptions) => {
    scrollY = Math.max(0, Math.min(opts.top ?? 0, maxScroll))
  }) as typeof window.scrollTo)
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
    queueMicrotask(() => cb(0))
    return 0
  })
})
afterEach(() => vi.restoreAllMocks())

const settle = () => new Promise((r) => setTimeout(r, 0))

describe('useTabScroll', () => {
  it('keeps the row stuck when a tab is left far down, and opens the next tab at its start', async () => {
    const active = ref<'gallery' | 'map'>('gallery')
    const { select } = useTabScroll(active, page())
    scrollY = STICK + 3000 // deep in the gallery
    const done = select('map')
    shrinkTo(STICK + 200) // the gallery unmounted, the map is short: the browser clamped
    await done
    expect(active.value).toBe('map')
    expect(scrollY).toBe(STICK)
  })

  it('opens a tab again where the member left it', async () => {
    const active = ref<'gallery' | 'map'>('gallery')
    const { select } = useTabScroll(active, page())
    scrollY = STICK + 3000
    await select('map')
    scrollY = STICK + 40
    const back = select('gallery')
    shrinkTo(STICK) // the gallery's grid lays out only after it measured
    await back
    maxScroll = 10_000 // ...one frame later it reached its height
    await settle()
    expect(scrollY).toBe(STICK + 3000)
    await select('map')
    expect(scrollY).toBe(STICK + 40)
  })

  it('keeps the offset when the row was not yet stuck', async () => {
    const active = ref<'gallery' | 'map'>('gallery')
    const { select } = useTabScroll(active, page())
    scrollY = 200
    const done = select('map')
    shrinkTo(STICK) // the panels' minimum height keeps at least the sticking point reachable
    await done
    expect(scrollY).toBe(200)
  })

  it('forgets the offsets of the Memory left', async () => {
    const active = ref<'gallery' | 'map'>('gallery')
    const { select, forget } = useTabScroll(active, page())
    scrollY = STICK + 3000
    await select('map')
    forget()
    await select('gallery')
    expect(scrollY).toBe(STICK)
  })

  it('does nothing on the tab already shown', async () => {
    const active = ref<'gallery' | 'map'>('gallery')
    const { select } = useTabScroll(active, page())
    scrollY = STICK + 3000
    await select('gallery')
    expect(window.scrollTo).not.toHaveBeenCalled()
  })
})
