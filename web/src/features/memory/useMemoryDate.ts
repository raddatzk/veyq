// uc.memory.date (web) — the Memory-date reactive slice of MemoryDetail, extracted into a composable
// (cr-0145, audit #77/P4a). The effective range is sealed on the NODE under K_node (operator-blind) and shown in
// the header; an editor sets a manual mode or `auto`. `auto` is **materialized** from the Memory's own media and
// self-heals on open + after an upload batch. Pure date maths live in `./memoryDate`; this composable owns only
// the reactive wiring the component used to inline (editor open-state, the header label, save + self-heal).
import { computed, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { unsealMemoryDate, computeAutoRange, sortNodesByDate, type MemoryDate } from './memoryDate'
import { nodeMetaState } from '../../core/node/nodeMeta'
import { useMetaWriteFeedback, type NodeMetaUpdate } from './useMetaWriteFeedback'
import { formatClosedRange, formatDay } from './memoryDateRange'
import type { MediaItem } from './media'
import type { TreeNode } from '../../core/node/tree'
import type { NodeMetaDoor } from './source'

/** The reactive context MemoryDetail hands the date slice — getters so props stay reactive, plus the live media
 *  list (`auto` materializes from it) and the host's `metaUpdated` emit. */
export interface MemoryDateContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  chapters: () => TreeNode[] | undefined
  /** The node-meta writes this view may make — the source's door where the rights grant `editMeta`, else null:
   *  then the save and the `auto` self-heal write nothing (a guest, a scope pending deletion). */
  write: () => NodeMetaDoor | null
  items: Ref<MediaItem[]>
  onMetaUpdated: (payload: NodeMetaUpdate) => void
}

export function useMemoryDate(ctx: MemoryDateContext) {
  const { t, locale } = useI18n()

  const dateEditorOpen = ref(false)
  const { editorFailed, selfHealFailed } = useMetaWriteFeedback(ctx.onMetaUpdated)
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
  // Chapters ordered by their own sealed start date — a chapter's K_node opens under THIS Memory's K_node (cr-0067),
  // epoch-independent (only the top-level wrap keys off the epoch), so every chapter resolves under the one memoryKey.
  const datedChapters = computed(() => sortNodesByDate(ctx.scopeId(), ctx.memoryKey, ctx.chapters() ?? []))

  /** Save an edited date: re-seal under K_node, PATCH version-checked, and let the host update the tree. A peer's
   *  date written meanwhile re-opens the editor on it (ADR 0077); other failures stay best-effort. */
  async function onDateSave(date: MemoryDate) {
    const key = ctx.memoryKey()
    const write = ctx.write()
    if (!key || !write) return
    const nodeId = ctx.node().id
    try {
      const state = await write.setDate(nodeId, key, date, nodeMetaState(ctx.node()))
      ctx.onMetaUpdated({ nodeId, ...state })
    } catch (e) {
      await editorFailed(nodeId, e, () => (dateEditorOpen.value = true))
    }
  }

  /** The `auto` self-heal: materialize the range from the full media list and re-seal only when it changed. The
   *  decision — "a manual date is never overwritten" included — runs on the server's current blob, not on the tree
   *  loaded at space entry (uc.memory.concurrent-curation E2). Silent either way. */
  async function reconcileAuto() {
    const key = ctx.memoryKey()
    const write = ctx.write()
    if (!key || !write) return
    const nodeId = ctx.node().id
    try {
      const { state } = await write.reconcileDate(nodeId, key, ctx.items.value, nodeMetaState(ctx.node()))
      if (ctx.node().id === nodeId) ctx.onMetaUpdated({ nodeId, ...state })
    } catch (e) {
      // leave the stale range; the next Memory open self-heals it (a blob that does not open is never rebuilt)
      await selfHealFailed(nodeId, e)
    }
  }

  return { dateEditorOpen, storedDate, autoRange, effectiveDate, dateLabel, datedChapters, onDateSave, reconcileAuto }
}
