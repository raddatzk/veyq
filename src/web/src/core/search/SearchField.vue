<script setup lang="ts">
// The shell's search field (uc.platform.search, ADR 0125): one field over every space of the tenant, its chips and its
// results in one list - the chips it offers for the typed text first, under their own heading (*Filter by*), then the
// results grouped by where the member stands
// (the open node, this space, other spaces), then the tenant's members and the settings sections (ADR 0125 decision 9). The
// model is core's (`searchIndex.ts`, `query.ts`, `groups.ts`, `peopleAndSettings.ts`); this draws it on the layer's tags
// input and opens what is chosen. The layer highlights the first entry, a suggestion or a result, and keeps an entry the
// member moved to while they type on; Enter takes the highlighted one (`VeyqTagsInput`'s header).
//
// **The first focus builds the index, every later one revalidates it** (`refreshSearchIndex`), and while the list is open
// the spaces are revalidated again every {@link REVALIDATE_MS} - each a `304` while nothing moved (ADR 0081). Nothing about
// the query leaves this component: the text, the chips and the results are local state.
//
// **Opening a result.** A node opens through the navigation the sidebar uses - a push of its fragment, which enters its
// space when it lies in another (`SpaceView` re-enters on a new scope token); a contributed document opens itself (a photo
// in the open Memory's lightbox); a person opens their profile page in the tenant space, and a settings section opens on
// the settings page by a push, which keeps the active space as the profile menu's Settings entry does (a hard navigation
// would reset it). The field says what it searched: the spaces still loading, those it could not search, and
// - before and instead of results - what is searched, with each application's note on what it searches only in part.
import { computed, onBeforeUnmount, ref, toRaw, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import VeyqTagsInput, { type VeyqTag, type VeyqTagOption, type VeyqTagOptionGroup } from '../../shared/ui/VeyqTagsInput.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import type { VeyqIconName } from '../../shared/ui/icons'
import { activeSpace } from '../../app/activeSpace'
import { breadcrumb } from '../../app/breadcrumb'
import { ScopeKind } from '../../api/generated/scopes'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { hashForNode, parseHash, personHash, TENANT_SCOPE } from '../node/location'
import { loadTenantPeople, tenantPeopleNow } from '../people/directory'
import PersonAvatar from '../people/PersonAvatar.vue'
import { useSettingsSections } from '../../features/settings/sections'
import { searchContribution } from './contribution'
import { PERSON_DOCUMENT, searchDocument, yearsOf, type SearchDocument } from './model'
import { chipKey, suggestChips, type ChipSuggestion, type SearchChip } from './query'
import { SEARCH_GROUPS, searchGroups, type SearchGroupId } from './groups'
import { personDocuments, settingsDocuments } from './peopleAndSettings'
import { indexedDocuments, indexedScope, indexedTree, refreshSearchIndex, searchState, type SearchScope } from './searchIndex'

/** How often an open list revalidates every space (a `304` while nothing moved). */
const REVALIDATE_MS = 20_000
/** How many results a group shows at first, and how many more each "show more" adds. */
const GROUP_PAGE = 8

const props = defineProps<{ inputId?: string }>()
/** A result was opened (the sheet around the field closes). */
const emit = defineEmits<{ opened: [] }>()

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

const text = ref('')
const chips = ref<SearchChip[]>([])
const open = ref(false)
const firstPage = () => Object.fromEntries(SEARCH_GROUPS.map((id) => [id, GROUP_PAGE])) as Record<SearchGroupId, number>
const shown = ref<Record<SearchGroupId, number>>(firstPage())
/** The tenant's members as the directory last read them (none after a sign-out): the person chips and the People group. */
const people = computed(() => tenantPeopleNow())
/** The settings sections the rail offers now, with the rail's own gates (none until every answer is in). */
const sections = useSettingsSections()
const field = useTemplateRef<{ focus(): void }>('field')

/** Put the focus in the field (the shell's shortcut). */
defineExpose({ focus: () => field.value?.focus() })

function onFocus() {
  void refreshSearchIndex().catch(() => undefined)
  void loadTenantPeople().catch(() => undefined)
  void sections.load()
}

let timer: ReturnType<typeof setInterval> | null = null
watch(open, (isOpen) => {
  if (timer) clearInterval(timer)
  timer = isOpen ? setInterval(() => void refreshSearchIndex().catch(() => undefined), REVALIDATE_MS) : null
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

// --- where the member stands -----------------------------------------------------------------------------------------
const activeTree = computed(() => (activeSpace.scopeId ? (indexedTree(activeSpace.scopeId) ?? []) : []))
/** The node whose page is on screen (the breadcrumb's last crumb), when it is no folder. */
const openNode = computed(() => {
  const id = breadcrumb.trail.at(-1)?.id
  const node = id ? activeTree.value.find((n) => n.id === id) : undefined
  return node && node.kind !== FOLDER_KIND ? node : null
})

// --- the documents: the index's and what the page on screen contributed ----------------------------------------------
const contributed = computed<SearchDocument[]>(() => {
  const c = searchContribution()
  if (!c) return []
  const tree = indexedTree(c.scopeId) ?? []
  const node = tree.find((n) => n.id === c.nodeId)
  const path = node ? [...pathOf(node.id, tree), node.name] : []
  return c.documents.map((d) => searchDocument({ ...d, scopeId: c.scopeId, nodeId: c.nodeId, path }))
})
const content = computed(() => [...indexedDocuments(), ...contributed.value])
/** The members and the settings sections (ADR 0125 decision 9): documents of no space, each opening itself. */
const elsewhere = computed<SearchDocument[]>(() => [
  ...personDocuments(people.value, (userId) => void router.push({ name: 'home', hash: personHash(userId) })),
  ...settingsDocuments(sections.offered.value, (id) => void router.push(`/settings/${id}`)),
])
const documents = computed(() => [...content.value, ...elsewhere.value])

function pathOf(nodeId: string, tree: readonly { id: string; parentId: string | null; name: string }[]): string[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const names: string[] = []
  const seen = new Set<string>([nodeId])
  let cur = byId.get(nodeId)?.parentId ? byId.get(byId.get(nodeId)!.parentId!) : undefined
  while (cur && !seen.has(cur.id)) {
    seen.add(cur.id)
    names.unshift(cur.name)
    cur = cur.parentId ? byId.get(cur.parentId) : undefined
  }
  return names
}

// --- words ------------------------------------------------------------------------------------------------------------
function spaceLabel(scope: SearchScope | null): string {
  if (scope?.name) return scope.name
  if (scope?.kind === ScopeKind.Personal) return t('shell.personal')
  if (scope?.kind === ScopeKind.Tenant) return t('shell.shared')
  return t('shell.groups')
}
/** Each kind's word with the kinds it names (a word two kinds share is one chip), and each application's note. */
const kindWords = computed(() => {
  const byLabel = new Map<string, Set<string>>()
  const notes = new Set<string>()
  const icons = new Map<string, VeyqIconName>()
  const add = (label: string, kind: string) => byLabel.set(t(label), (byLabel.get(t(label)) ?? new Set()).add(kind))
  for (const kind of registry.kinds()) {
    const d = registry.kind(kind)
    if (!d.supported || !d.search) continue
    add(d.search.label, kind)
    for (const doc of d.search.documents ?? []) {
      add(doc.label, doc.kind)
      icons.set(doc.kind, doc.icon)
    }
    if (d.search.note) notes.add(d.search.note)
  }
  return { kinds: [...byLabel].map(([label, kinds]) => ({ label, kinds: [...kinds] })), notes: [...notes], icons }
})
function iconOf(doc: SearchDocument): VeyqIconName {
  if (doc.icon) return doc.icon
  const d = registry.kind(doc.kind)
  return d.supported ? d.icon : (kindWords.value.icons.get(doc.kind) ?? 'file')
}
const dayFormat = computed(() => new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }))
const collator = computed(() => new Intl.Collator(locale.value, { numeric: true, sensitivity: 'base' }))

// --- the chips -------------------------------------------------------------------------------------------------------
const CHIP_ICON: Record<SearchChip['type'], VeyqIconName> = { year: 'calendar', place: 'map-marker', person: 'user', space: 'users', kind: 'tag' }
const CHIP_WORD: Record<SearchChip['type'], string> = {
  year: 'shell.search.chipYear', place: 'shell.search.chipPlace', person: 'shell.search.chipPerson', space: 'shell.search.chipSpace', kind: 'shell.search.chipKind',
}
const chipLabel = (c: SearchChip) => (c.type === 'year' ? String(c.year) : c.label)

type ChipTag = VeyqTag & { chip: SearchChip }
type ChipOption = VeyqTagOption & { suggestion: ChipSuggestion }
type ResultOption = VeyqTagOption & { doc?: SearchDocument; group: SearchGroupId; more?: number }

const tags = computed<ChipTag[]>(() => chips.value.map((chip) => ({ key: chipKey(chip), label: chipLabel(chip), chip })))
const suggestions = computed<ChipOption[]>(() =>
  suggestChips(text.value, chips.value, {
    documents: content.value,
    spaces: searchState.scopes.map((s) => ({ scopeId: s.id, label: spaceLabel(s) })),
    people: people.value.map((p) => ({ userId: p.userId, name: p.displayName })),
    kinds: kindWords.value.kinds,
  }).map((suggestion) => ({ key: `chip:${chipKey(suggestion.chip)}`, label: chipLabel(suggestion.chip), suggestion })),
)
function addChip(option: ChipOption) {
  chips.value = [...chips.value, option.suggestion.chip]
  text.value = option.suggestion.text
}
function removeChip(tag: ChipTag) {
  chips.value = chips.value.filter((c) => chipKey(c) !== tag.key)
}

// --- the results -----------------------------------------------------------------------------------------------------
// the chips as plain objects: the query reads them once per document, and a reactive read each time costs a keystroke
// at volume (cr-search-at-volume)
const groups = computed(() =>
  searchGroups(documents.value, { chips: chips.value.map((c) => toRaw(c)), text: text.value }, {
    activeScopeId: activeSpace.scopeId, openNodeId: openNode.value?.id ?? null, activeTree: activeTree.value,
  }, collator.value),
)
watch([text, chips], () => (shown.value = firstPage()))
const HEADING: Record<Exclude<SearchGroupId, 'open'>, string> = {
  space: 'shell.search.groupSpace', other: 'shell.search.groupOther', people: 'shell.search.groupPeople', settings: 'shell.search.groupSettings',
}
const heading = (id: SearchGroupId) => (id === 'open' ? t('shell.search.groupOpen', { name: openNode.value?.name ?? '' }) : t(HEADING[id]))
const resultGroups = computed<VeyqTagOptionGroup<ResultOption>[]>(() =>
  groups.value.map((g) => {
    const visible = g.results.slice(0, shown.value[g.id])
    const rest = g.results.length - visible.length
    return {
      key: g.id,
      label: heading(g.id),
      options: [
        ...visible.map((doc) => ({ key: `result:${doc.scopeId}:${doc.kind}:${doc.id}`, label: doc.name, doc, group: g.id })),
        ...(rest > 0 ? [{ key: `more:${g.id}`, label: t('shell.search.showMore', { count: Math.min(rest, GROUP_PAGE) }), group: g.id, more: rest }] : []),
      ],
    }
  }),
)

/** What a row says under the name: its own line (a person's handle, a section's heading), where it lives (and in which
 *  space, for another space's), or a photo's day. */
function secondLine(option: ResultOption): string {
  const doc = option.doc!
  if (doc.subtitle !== undefined) return doc.subtitle
  if (doc.facets.capturedAt !== undefined) return dayFormat.value.format(doc.facets.capturedAt)
  const path = doc.path.join(' / ')
  if (option.group !== 'other') return path
  const space = spaceLabel(indexedScope(doc.scopeId))
  return path ? `${space} · ${path}` : space
}
/** The values of the chips in force a row carries (its years, its place, the person), shown beside it. */
function matchedValues(doc: SearchDocument): string[] {
  const out: string[] = []
  for (const c of chips.value) {
    if (c.type === 'year') {
      const y = yearsOf(doc)
      if (y) out.push(y.from === y.to ? String(y.from) : `${y.from}–${y.to}`)
    } else if (c.type === 'place') {
      const at = doc.foldedPlaces.indexOf(c.place)
      if (at >= 0) out.push(doc.facets.places![at])
    } else if (c.type === 'person') out.push(c.label)
  }
  return [...new Set(out)]
}

function choose(option: ResultOption) {
  if (option.more !== undefined) {
    shown.value = { ...shown.value, [option.group]: shown.value[option.group] + GROUP_PAGE }
    return
  }
  const doc = option.doc
  if (!doc) return
  open.value = false
  emit('opened')
  if (doc.open) {
    doc.open()
    return
  }
  const tree = indexedTree(doc.scopeId)
  if (!tree) return
  void router.push({ name: 'home', hash: hashForNode([...tree], doc.id, tokenOf(doc.scopeId)) })
}
/** The fragment's scope token of a space: the one on screen keeps the address it was reached by. */
function tokenOf(scopeId: string): string {
  if (scopeId === activeSpace.scopeId && route.name === 'home') return parseHash(route.hash).scope
  const scope = indexedScope(scopeId)
  if (scope?.kind === ScopeKind.Personal) return '~me'
  if (scope?.kind === ScopeKind.Group) return `@${scopeId}`
  return TENANT_SCOPE
}

// --- what the field says ---------------------------------------------------------------------------------------------
const asked = computed(() => text.value.trim() !== '' || chips.value.length > 0)
const loadingCount = computed(() => searchState.loading.length)
const notSearched = computed(() => searchState.failed.map((s) => spaceLabel(s)).join(', '))
</script>

<template>
  <div class="veyq-search">
  <VeyqTagsInput
    ref="field"
    v-model:query="text"
    v-model:open="open"
    list-class="veyq-search__list"
    :input-id="props.inputId"
    :tags="tags"
    :options="suggestions"
    :groups="resultGroups"
    :options-label="t('shell.search.filterBy')"
    :placeholder="t('shell.search.placeholder')"
    :aria-label="t('shell.search.label')"
    autocomplete="off"
    open-on-focus
    @focus="onFocus"
    @add="addChip"
    @remove="removeChip"
    @choose="choose"
  >
    <template #leading><VeyqIcon name="search" class="veyq-search__icon" /></template>
    <template #tag="{ tag }"><VeyqIcon :name="CHIP_ICON[tag.chip.type]" class="veyq-search__chip-icon" /></template>
    <template #option="{ option }">
      <VeyqIcon :name="CHIP_ICON[option.suggestion.chip.type]" />
      <span class="veyq-search__option-label">{{ option.label }}</span>
      <span class="veyq-search__option-kind">{{ t(CHIP_WORD[option.suggestion.chip.type]) }}</span>
    </template>
    <template #result="{ option }">
      <span v-if="option.more !== undefined" class="veyq-search__more">{{ option.label }}</span>
      <template v-else-if="option.doc">
        <PersonAvatar v-if="option.doc.kind === PERSON_DOCUMENT" :user-id="option.doc.id" :name="option.doc.name" class="veyq-search__result-avatar" />
        <VeyqIcon v-else :name="iconOf(option.doc)" class="veyq-search__result-icon" />
        <span class="veyq-search__result-main">
          <span class="veyq-search__result-name">{{ option.doc.name }}</span>
          <span v-if="secondLine(option)" class="veyq-search__result-meta">{{ secondLine(option) }}</span>
        </span>
        <span v-for="v in matchedValues(option.doc)" :key="v" class="veyq-badge muted veyq-search__result-chip">{{ v }}</span>
      </template>
    </template>
    <template #status>
      <span v-if="loadingCount > 0" class="veyq-search__loading">{{ t('shell.search.loading', { count: loadingCount }, loadingCount) }}</span>
      <span v-if="notSearched" class="veyq-search__failed">{{ t('shell.search.notSearched', { spaces: notSearched }) }}</span>
      <span v-if="searchState.listFailed" class="veyq-search__failed">{{ t('shell.search.listFailed') }}</span>
      <template v-if="!groups.length && !suggestions.length && loadingCount === 0">
        <span v-if="asked" class="veyq-search__empty">{{ t('shell.search.empty') }}</span>
        <span class="veyq-search__hint">{{ t('shell.search.hint') }}</span>
        <span v-for="note in kindWords.notes" :key="note" class="veyq-search__hint">{{ t(note) }}</span>
      </template>
    </template>
  </VeyqTagsInput>
  </div>
</template>
