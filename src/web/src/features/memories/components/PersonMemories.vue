<script setup lang="ts">
// A person's profile section from Memories (ADR 0083 decision 6, uc.memory.participants): "Memories with <name>" - the
// Memories in which the person is a participant AND the viewer has access, in core's level-list item (`LevelRowItem`:
// cover, name, a meta line with the Memory's date and place - the very builder the level uses, `memoryRowDetails.ts` - and a
// click that opens the Memory), as rows, as tiles or on the map. The switch is core's `ViewModeToggle` over the one per-user
// view-mode preference (`useViewMode`), so the choice made here is the folder view's too, and it offers what a level of
// Memories offers (`MEMORY_VIEWS`): core's two layouts and Memories' places map (inbox #417) - the level's own `SpaceMap`,
// with its consent prompt, tiles and empty state unchanged, over the person's Memories that have a place (`locatedPin`, the
// level's rule: a Memory without one is left out; none left is the map's "No places yet"). A pin opens its Memory like a row.
// The stored mode shows where offered (`effectiveViewMode`), never rewritten. The list is computed here by the
// participants index (`../participantIndex.ts`): a client-side walk of the tenant and the viewer's group scopes, revalidated
// by ETag, bounded - the page says so when it is `partial`. Four honest states: loading, error (retry), empty, list.
// A former member's section is the same list; the rows link to Memories, never to the person.
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LevelRowItem from '../../../core/node/components/LevelRowItem.vue'
import ViewModeToggle from '../../../core/node/ViewModeToggle.vue'
import type { LevelRow } from '../../../core/node/levelRow'
import { effectiveViewMode } from '../../../core/node/levelViews'
import { useViewMode } from '../../../shared/state/useViewMode'
import type { PersonSectionProps } from '../../../core/applications/registry'
import { hashForNode } from '../../../core/node/location'
import { createCoverStore } from '../../../core/node/cover/coverStore'
import { isAbandoned, loadParticipantIndex, type ParticipantIndex, type ParticipatedMemory } from '../participantIndex'
import { memoryDetails } from '../memoryRowDetails'
import { MEMORY_VIEWS } from '../memoryViews'
import { locatedPin, type LocatedPin } from '../spaceMap'
import MemoryRowCover from './MemoryRowCover.vue'
import SpaceMap from './SpaceMap.vue'

const props = defineProps<PersonSectionProps>()
const { t, locale } = useI18n()
const router = useRouter()

type State = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; index: ParticipantIndex }
const state = ref<State>({ kind: 'loading' })
const coverStore = createCoverStore(null)
let token = 0

async function load() {
  const mine = ++token
  state.value = { kind: 'loading' }
  try {
    const index = await loadParticipantIndex()
    if (mine !== token) return
    state.value = { kind: 'ready', index }
    for (const [scopeId, group] of byScope(memoriesOf(index))) {
      const keys = new Map(group.map((m) => [m.node.id, m.key]))
      coverStore.ensure(scopeId, group.map((m) => m.node), (id) => keys.get(id) ?? null)
    }
  } catch (e) {
    if (mine === token && !isAbandoned(e)) state.value = { kind: 'error' }
  }
}
onMounted(load)
watch(() => props.userId, load)
onBeforeUnmount(() => {
  token++
  coverStore.reset()
})

function memoriesOf(index: ParticipantIndex): ParticipatedMemory[] {
  const out = [...(index.byUser.get(props.userId) ?? [])].map((ref) => index.memories.get(ref)!).filter(Boolean)
  // newest start day first, undated last, then by name
  return out.sort((a, b) => {
    const da = a.date?.from
    const db = b.date?.from
    if (da && db && da !== db) return da < db ? 1 : -1
    if (!da !== !db) return da ? -1 : 1
    return a.node.name.localeCompare(b.node.name, locale.value)
  })
}
function byScope(memories: ParticipatedMemory[]): Map<string, ParticipatedMemory[]> {
  const map = new Map<string, ParticipatedMemory[]>()
  for (const m of memories) map.set(m.scopeId, [...(map.get(m.scopeId) ?? []), m])
  return map
}

// The presentation: the stored per-user mode where this page offers it (all three, as a level of Memories), never rewritten.
const { viewMode } = useViewMode()
const mode = computed(() => effectiveViewMode(viewMode.value, MEMORY_VIEWS))
/** How core's item draws a row outside the map. */
const itemMode = computed<'grid' | 'list'>(() => (mode.value === 'list' ? 'list' : 'grid'))

const memories = computed<ParticipatedMemory[]>(() => (state.value.kind === 'ready' ? memoriesOf(state.value.index) : []))

function openMemory(m: ParticipatedMemory) {
  void router.push({ hash: hashForNode(m.tree, m.node.id, m.scopeToken) })
}

// The map's pins: the person's Memories that have a place, by the level's own rule.
const pins = computed<LocatedPin[]>(() => memories.value.flatMap((m) => locatedPin(m.node, m.location, m.date) ?? []))
/** A pin carries a node id (as on the level); the Memory it names opens in its own scope. */
function onPinOpen(nodeId: string) {
  const m = memories.value.find((x) => x.node.id === nodeId)
  if (m) openMemory(m)
}

const rows = computed<{ m: ParticipatedMemory; row: LevelRow }[]>(() => {
  const covers = coverStore.covers.value
  return memories.value.map((m) => {
    const cover = covers.get(m.node.id)
    return {
      m,
      row: {
        icon: 'images',
        leading: { component: MemoryRowCover, props: { src: cover?.url ?? null, crop: cover?.crop ?? null, alt: m.node.name } },
        title: m.node.name,
        meta: t('memory.kindMemory'),
        details: [
          ...memoryDetails({ date: m.date, placeName: m.placeName }, t, locale.value),
          ...(m.scopeLabel ? [{ id: 'scope', text: m.scopeLabel }] : []),
        ],
        primary: { label: m.node.name, onClick: () => openMemory(m) },
        actions: [],
      },
    }
  })
})
const partial = computed(() => state.value.kind === 'ready' && state.value.index.partial)
const heading = computed(() => (props.name ? t('memory.people.withName', { name: props.name }) : t('memory.people.with')))
</script>

<template>
  <section class="person-memories" aria-labelledby="person-memories-title" data-testid="person-memories">
    <div class="person-memories-head">
      <h2 id="person-memories-title" class="person-memories-title">{{ heading }}</h2>
      <ViewModeToggle v-if="state.kind === 'ready' && rows.length > 0" :views="MEMORY_VIEWS" />
    </div>
    <p v-if="state.kind === 'loading'" class="person-memories-state" role="status">{{ t('memory.people.loading') }}</p>
    <div v-else-if="state.kind === 'error'" class="person-memories-state" role="alert">
      <p>{{ t('memory.people.error') }}</p>
      <button type="button" class="person-memories-retry" @click="load">{{ t('memory.people.retry') }}</button>
    </div>
    <template v-else>
      <p v-if="rows.length === 0" class="person-memories-state" data-testid="person-memories-empty">{{ t('memory.people.empty') }}</p>
      <SpaceMap v-else-if="mode === 'map'" :pins="pins" :covers="coverStore.covers.value" @open="onPinOpen" />
      <ul v-else class="person-memories-list veyq-level-list" :class="{ 'is-list': mode === 'list' }" data-testid="person-memories-list">
        <LevelRowItem v-for="r in rows" :key="r.m.ref" :row="r.row" :mode="itemMode" kind="MEMORY" />
      </ul>
      <p v-if="partial" class="person-memories-partial" role="note" data-testid="person-memories-partial">{{ t('memory.people.partial') }}</p>
    </template>
  </section>
</template>

<style scoped>
.person-memories-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin: 0 0 0.6rem;
}
.person-memories-title {
  margin: 0;
  font-size: 1.1rem;
}
.person-memories-state,
.person-memories-partial {
  color: var(--app-muted);
}
.person-memories-list {
  margin: 0;
  padding: 0;
}
.person-memories-retry {
  margin-top: 0.5rem;
  padding: 0.35rem 0.8rem;
  border: 1px solid var(--app-control-line);
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text);
  cursor: pointer;
}
</style>
