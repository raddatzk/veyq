// Memories' keyboard shortcuts, as core's overview lists them (uc.platform.keyboard-shortcuts): registered once through
// its registrar (`register.ts`, `app.shortcuts`), its labels of its own catalog. A handler that spells one of these keys
// points here in a comment: the gallery's `components/MediaGrid.vue` (`onKeydown`), the large view's
// `components/MediaLightbox.vue` (`onKeydown`) with the zoom of `shared/ui/usePanZoom.ts` (`onKey`).
import type { ShortcutGroup } from '../../core/shortcuts/shortcuts'

const ARROWS = [['ArrowLeft'], ['ArrowRight'], ['ArrowUp'], ['ArrowDown']]

export const MEMORIES_SHORTCUTS: readonly ShortcutGroup[] = [
  {
    title: 'memory.shortcuts.gallery',
    entries: [
      { keys: ARROWS, label: 'memory.shortcuts.galleryMove' },
      { keys: [['Home'], ['End']], label: 'memory.shortcuts.galleryFirstLast' },
      { keys: [['PageUp'], ['PageDown']], label: 'memory.shortcuts.galleryPage' },
    ],
  },
  {
    title: 'memory.shortcuts.largeView',
    entries: [
      { keys: [['ArrowLeft'], ['ArrowRight']], label: 'memory.shortcuts.previousNext' },
      { keys: [['+'], ['-']], label: 'memory.shortcuts.zoom' },
      { keys: [['0']], label: 'memory.shortcuts.zoomReset' },
      { keys: ARROWS, label: 'memory.shortcuts.pan' },
      { keys: [['Delete'], ['Backspace']], label: 'memory.shortcuts.trash' },
      { keys: [['Escape']], label: 'memory.shortcuts.close' },
    ],
  },
]
