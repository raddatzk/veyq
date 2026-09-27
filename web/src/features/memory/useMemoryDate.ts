// uc.memory.date-place (web) — the Memory-date reactive slice of MemoryDetail, extracted into a composable
// (cr-0145, audit #77/P4a). The effective range is sealed on the NODE under K_mem (operator-blind) and shown in
// the header; an editor sets a manual mode or `auto`. `auto` is **materialized** from the Memory's own media and
// self-heals on open + after an upload batch. Pure date maths live in `./memoryDate`; this composable owns only
// the reactive wiring the component used to inline (editor open-state, the header label, save + self-heal).
import { computed, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { unsealMemoryDate, computeAutoRange, setMemoryDate, sameMemoryDate, sortNodesByDate, type MemoryDate } from './memoryDate'
import { formatClosedRange, formatDay } from './memoryDateRange'
import type { MediaItem } from './media'
import type { TreeNode } from './tree'

/** The reactive context MemoryDetail hands the date slice — getters so props stay reactive, plus the live media
 *  list (`auto` materializes from it) and the host's `metaUpdated` emit. */
export interface MemoryDateContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  chapters: () => TreeNode[] | undefined
  items: Ref<MediaItem[]>
  onMetaUpdated: (payload: { nodeId: string; wrappedMeta: string }) => void
}

export function useMemoryDate(ctx: MemoryDateContext) {
  const { t, locale } = useI18n()

  const dateEditorOpen = ref(false)
  const storedDate = computed(() => (ctx.memoryKey() ? unsealMemoryDate(ctx.scopeId(), ctx.memoryKey()!, ctx.node()) : null))
  const autoRange = computed<MemoryDate>(() => {
    const key = ctx.memoryKey()
    return key ? computeAutoRange(ctx.scopeId(), ctx.items.value, key) : { mode: 'auto' }
  })
  // What the header shows: a manual date as sealed, else the live materialized `auto` range (before/without a seal).
  const effectiveDate = computed<MemoryDate>(() => {
    const s = storedDate.value
    return s && (s.mode !== 'auto' || s.from) ? s : autoRange.value
  })
  const dateLabel = computed(() => {
    const d = effectiveDate.value
    if (d.mode === 'ongoing') return d.from ? t('memory.date.since', { day: formatDay(d.from, locale.value) }) : ''
    return formatClosedRange(d.from, d.to, locale.value)
  })
  // Chapters ordered by their own sealed start date — a chapter's K_mem opens under THIS Memory's K_mem (cr-0067),
  // epoch-independent (only the top-level wrap keys off the epoch), so every chapter resolves under the one memoryKey.
  const datedChapters = computed(() => sortNodesByDate(ctx.scopeId(), ctx.memoryKey, ctx.chapters() ?? []))

  /** Save an edited date: re-seal under K_mem, PATCH via updateNode, and let the host update the tree. */
  async function onDateSave(date: MemoryDate) {
    const key = ctx.memoryKey()
    if (!key) return
    const nodeId = ctx.node().id
    try {
      const wrappedMeta = await setMemoryDate(ctx.scopeId(), nodeId, key, date, ctx.node().wrappedMeta)
      ctx.onMetaUpdated({ nodeId, wrappedMeta })
    } catch {
      // best-effort: keep the header stable; the next open-time reconcile can re-materialize an `auto` range
    }
  }

  /** Materialize the `auto` range from the full media list and re-seal only when it changed (the self-heal). */
  async function reconcileAuto() {
    const key = ctx.memoryKey()
    if (!key) return
    const stored = unsealMemoryDate(ctx.scopeId(), key, ctx.node())
    if (stored && stored.mode !== 'auto') return // a manual date is the user's — never overwritten
    const next = computeAutoRange(ctx.scopeId(), ctx.items.value, key)
    const current: MemoryDate = stored ?? { mode: 'auto' }
    if (sameMemoryDate(current, next)) return
    if (!stored && next.from === undefined) return // absent meta already == the empty `auto` state; nothing to seal
    const nodeId = ctx.node().id
    try {
      const wrappedMeta = await setMemoryDate(ctx.scopeId(), nodeId, key, next, ctx.node().wrappedMeta)
      if (ctx.node().id === nodeId) ctx.onMetaUpdated({ nodeId, wrappedMeta })
    } catch {
      // leave the stale range; the next Memory open self-heals it
    }
  }

  return { dateEditorOpen, storedDate, autoRange, effectiveDate, dateLabel, datedChapters, onDateSave, reconcileAuto }
}
