// Memories' part of the unified level list (inbox #407, ADR 0079): the ROW DATA of a folder level's `MEMORY` (and `CHAPTER`)
// children. Core draws the list (`core/node/LevelView.vue`, `LevelRowItem.vue`); this factory is called once per level view
// (`register.ts` names it as both kinds' `list.rows`) and returns what core needs per node (`LevelRowSource`). It replaces
// the former `MemoriesSection.vue` and `MemoryCard.vue`; what those did beyond drawing lives here:
//
// - **The cover** of each Memory (the visual of its row or tile): a member's from the shared `coverStore` (one decrypt per
//   Memory per scope for every surface), a share guest's from the link's own reads (`./guestCover.ts`). This source only says
//   WHICH nodes are on screen - the store owns the key chain, the object-URL lifecycle and the staleness rule. The sealed meta
//   is part of the watched key for the same reason it is in the sidebar rail: a cover edit patches `wrappedMeta` in place.
// - **The sealed date range** (the meta line) from each node's `wrappedMeta` under the key its parent's chain opens - read from
//   the tree, no media load. A rotated scope mixes epochs across the level; locked (`epochs` null) means no dates.
// - **The heart** (inbox #409 moves it to core): the member's own pin set, sealed under the Master Key (`favoritesStore`), a
//   first-class action of the row. A guest has none (the capability matrix, `./capabilities.ts`).
// - **The map** (the third presentation, `register.ts` names it among the kinds' `views`): core asks `presentation('map')`
//   and draws the component handed back in place of the list. Its pins span the whole subtree (folders included), unsealed on
//   device from the `wrappedMeta` the tree already holds (operator-blind - there is no locations API), a guest's from the
//   opened subtree. Only Memories offers it.
//
// What a row offers is Memories' capability matrix for the node (`./capabilities.ts`, inbox #401), fail closed: a share guest
// gets the same row with the member-only functions removed. A row's primary click OPENS the Memory (its detail).
import { computed, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelPresentation, LevelRow, LevelRowAction, LevelRowSource, LevelRowsContext, LevelRowsHost } from '../../core/node/levelRow'
import { parentContentKey, type TreeNode } from '../../core/node/tree'
import { nodeById } from '../../core/node/browse'
import { treeStore } from '../../core/node/treeStore'
import { activeSpace } from '../../app/activeSpace'
import { targetOf, type TreeSource } from '../../core/access'
import { sortNodesByDate, type MemoryDate } from './memoryDate'
import { collectLocatedPins, collectLocatedPinsBy } from './spaceMap'
import { covers, ensureCovers } from './coverStore'
import { isFavorite, toggleFavorite } from './favoritesStore'
import { memoryCapabilities } from './capabilities'
import { useGuestCovers } from './guestCover'
import { formatClosedRange, formatDay } from './memoryDateRange'
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import MemoryRowCover from './components/MemoryRowCover.vue'
import SpaceMap from './components/SpaceMap.vue'

/** Every node of `tree` in the subtree of `rootId`, `rootId`'s own included - what a guest's map spans. */
function subtreeNodes(tree: TreeSource<TreeNode>, rootId: string | null): TreeNode[] {
  const out: TreeNode[] = []
  const root = rootId ? tree.node(rootId) : null
  if (root) out.push(root)
  const queue: (string | null)[] = [rootId]
  for (let i = 0; i < queue.length; i++) {
    for (const child of tree.children(queue[i])) {
      out.push(child)
      queue.push(child.id)
    }
  }
  return out
}

export function memoryRows(ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost): LevelRowSource {
  const { t, locale } = useI18n()
  // Who looks does not change while the level is mounted (a host mounts its level for one session).
  const guestAccess = ctx.value.access.mode === 'guest' ? ctx.value.access : null
  const guestCovers = guestAccess ? useGuestCovers(guestAccess) : null
  const folderId = () => ctx.value.folder?.id ?? null
  const treeOf = () => ctx.value.tree as TreeSource<TreeNode>

  /** Only this application's nodes, whatever the level hands in. */
  const memoryNodes = computed(() => ctx.value.nodes.filter((n) => n.kind === MEMORY_KIND || n.kind === CHAPTER_KIND))

  // Each Memory's sealed date range - each K_mem opened under its parent's key: E_{node.epoch} at the scope root, the folder's
  // key inside a folder (ADR 0080 Decision 1); read from wrappedMeta, no media load.
  const dates = computed(() => {
    const nodes = [...memoryNodes.value]
    const dated = guestAccess
      ? sortNodesByDate(guestAccess.head.scopeId, (node) => (node.parentId ? ctx.value.tree.keyOf(node.parentId) : null), nodes)
      : sortNodesByDate(
          activeSpace.scopeId ?? '',
          (node) => (ctx.value.epochs && activeSpace.scopeId ? parentContentKey(activeSpace.scopeId, ctx.value.epochs, treeStore.nodes, node) : null),
          nodes,
        )
    return new Map<string, MemoryDate | null>(dated.map((d) => [d.node.id, d.date]))
  })

  // The map pins: every located node in this folder's subtree (or the whole space at root), unsealed on device from the
  // wrappedMeta `listNodes` already returned. Locked => no pins.
  const pins = computed(() =>
    guestAccess
      ? collectLocatedPinsBy(guestAccess.head.scopeId, (id) => ctx.value.tree.keyOf(id), subtreeNodes(treeOf(), folderId()), folderId())
      : ctx.value.epochs && activeSpace.scopeId
        ? collectLocatedPins(activeSpace.scopeId, ctx.value.epochs, treeStore.nodes, folderId())
        : [],
  )

  /** A pin click carries a node id - resolve it to the TreeNode and open it, exactly like a row click. */
  function onPinOpen(nodeId: string) {
    const node = guestAccess ? treeOf().node(nodeId) : nodeById(treeStore.nodes, nodeId)
    if (node) host.open(node)
  }

  // The covers come from the shared `coverStore` (cr-0072 + the sidebar rail): one decrypt per Memory per scope for every
  // surface. In the map the pins' nodes are what is on screen, else the level's.
  function syncCovers() {
    const nodes = ctx.value.viewMode === 'map' ? pins.value.map((pin) => pin.node) : ctx.value.nodes
    if (guestCovers) guestCovers.ensure(nodes, (id) => ctx.value.tree.keyOf(id))
    else ensureCovers(nodes.map((n) => n.id))
  }
  const coverMap = computed(() => (guestCovers ? guestCovers.covers.value : covers.value))
  // The sealed meta is part of the key: a cover edit patches `wrappedMeta` in place, so watching the array (whose identity
  // does not change) would leave a stale cover on the very row the new one was chosen from.
  const metaKey = computed(() => ctx.value.nodes.map((n) => `${n.id}:${n.wrappedMeta ?? ''}`).join('|'))
  const viewMode = computed(() => ctx.value.viewMode)
  watch([viewMode, pins, metaKey], syncCovers, { immediate: true })

  /** A member's own pin of a Memory; never a guest's (pins are sealed under the member's Master Key). */
  const favorite = (node: TreeNode, allowed: boolean) => allowed && isFavorite(node.id)

  function dateLabel(date: MemoryDate | null | undefined): string {
    if (!date) return ''
    if (date.mode === 'ongoing') return date.from ? t('memory.date.since', { day: formatDay(date.from, locale.value) }) : ''
    return formatClosedRange(date.from, date.to, locale.value)
  }

  function memoryRow(node: TreeNode): LevelRow {
    // The matrix for THIS node: the heart, the rename and the move are about the Memory the row stands for.
    const c = memoryCapabilities(ctx.value.access, targetOf(ctx.value.tree, node))
    const pinned = favorite(node, c.favorite)
    const cover = coverMap.value.get(node.id)
    const actions: LevelRowAction[] = [
      {
        id: 'favorite', label: pinned ? t('memory.unfavorite') : t('memory.favorite'), icon: pinned ? 'pi pi-heart-fill' : 'pi pi-heart',
        first: true, pressed: pinned, shown: c.favorite,
        onClick: () => {
          if (c.favorite && activeSpace.scopeId) void toggleFavorite(node.id, activeSpace.scopeId)
        },
      },
      { id: 'rename', label: t('node.rename'), icon: 'pi pi-pencil', shown: c.rename, onClick: () => host.rename(node) },
      { id: 'move', label: t('node.move.action'), icon: 'pi pi-file-export', shown: c.move, onClick: () => host.move(node) },
    ]
    return {
      icon: 'pi pi-images',
      // The cover always: a Memory's tile stays a cover tile (the soft placeholder) while it has none or it is loading.
      leading: { component: MemoryRowCover, props: { src: cover?.url ?? null, crop: cover?.crop ?? null, alt: node.name } },
      title: node.name,
      meta: [t(node.kind === CHAPTER_KIND ? 'memory.kindChapter' : 'memory.kindMemory'), dateLabel(dates.value.get(node.id))].filter(Boolean).join(' · '),
      primary: { label: node.name, onClick: () => host.open(node) },
      actions,
    }
  }

  const rowTable = computed(() => new Map(memoryNodes.value.map((n) => [n.id, memoryRow(n)])))

  return {
    row: (node) => rowTable.value.get(node.id) ?? null,
    presentation(mode): LevelPresentation | null {
      return mode === 'map' ? { component: SpaceMap, props: { pins: pins.value, covers: coverMap.value }, on: { open: onPinOpen } } : null
    },
  }
}
