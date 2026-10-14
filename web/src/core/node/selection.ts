// The selection of a level (inbox #421, owner decisions 2026-10-14): a set of node ids the level holds while its Select mode
// is on. Core's, like the level itself: it names no kind, and what a selected node may do is its application's capability
// answer (`LevelRow.selection`), read where an action is offered - never here.
//
// The set belongs to the LEVEL, not to a tab (`LevelView.vue`): switching tabs keeps it, "All" adds what the tab on screen
// shows and "None" takes exactly that away again, so a part another tab hides stays selected and the count says so. The level
// clears it when the folder changes or the mode is left, and drops an id whose node is no longer at the level (moved away,
// deleted - here or by someone else) with `retain`.
//
// Memories' media selection (`MemoryBuckets.vue`) is a different set over a different list and is not built on this (inbox #427).
import { computed, shallowRef, type ComputedRef } from 'vue'
import type { TreeNode } from './tree'

/** A node the selection's delete reaches, with its kind's own write where it has one (`LevelRowSelection.remove`). */
export interface SelectedForDelete {
  node: TreeNode
  remove?: (nodeId: string) => Promise<void>
}

export interface Selection {
  /** The selected ids (a new set on every change: the view re-renders on it). */
  readonly ids: ComputedRef<ReadonlySet<string>>
  /** How many are selected. */
  readonly size: ComputedRef<number>
  has(id: string): boolean
  /** Select `id` if it is not, else take it away. */
  toggle(id: string): void
  /** Add every one of `ids` (what the tab on screen shows); the rest stays as it is. */
  all(ids: Iterable<string>): void
  /** Take every one of `ids` away (what the tab on screen shows); the rest stays as it is. */
  none(ids: Iterable<string>): void
  /** Empty the selection. */
  clear(): void
  /** Keep only the ids among `ids` (the nodes still at the level). */
  retain(ids: Iterable<string>): void
}

export function useSelection(): Selection {
  const set = shallowRef<ReadonlySet<string>>(new Set())
  const write = (next: Set<string>) => {
    if (next.size !== set.value.size || [...next].some((id) => !set.value.has(id))) set.value = next
  }
  return {
    ids: computed(() => set.value),
    size: computed(() => set.value.size),
    has: (id) => set.value.has(id),
    toggle(id) {
      const next = new Set(set.value)
      if (!next.delete(id)) next.add(id)
      set.value = next
    },
    all(ids) {
      write(new Set([...set.value, ...ids]))
    },
    none(ids) {
      const next = new Set(set.value)
      for (const id of ids) next.delete(id)
      write(next)
    },
    clear() {
      write(new Set())
    },
    retain(ids) {
      const keep = new Set(ids)
      write(new Set([...set.value].filter((id) => keep.has(id))))
    },
  }
}

/** Several names or nouns as one phrase of `locale` ("“a”, “b” and “c”" / "„a“, „b“ und „c“"), for the selection's
 *  questions and summaries. */
export function joinList(locale: string, items: readonly string[]): string {
  return new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }).format(items)
}
