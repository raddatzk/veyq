<script setup lang="ts">
// The shell's search field (uc.platform.search, ADR 0125): one field over every space of the tenant, its chips and its
// results in one list of two areas - the chips it offers for the typed text first, under their own heading (*Filter by*),
// then ONE list of results with no heading (decision 5): ordered by the match, then where the result lies (the open
// node, this space, other spaces, then the tenant's members and the settings sections, decision 9), then the name; each
// row says where it lies. Ten are drawn, and "show more" adds ten. Every kind has its word for a kind chip - core's
// *Person* and *Setting* included - and a node something lies below is offered as *In:* that node, its space and path as
// the suggestion's second line (two nodes of one name are two suggestions). The model is core's (`searchIndex.ts`,
// `query.ts`, `results.ts`, `peopleAndSettings.ts`); this draws it on the layer's tags input and opens what is chosen. The layer highlights the first entry, a suggestion or a result, and keeps an entry the
// member moved to while they type on; Enter takes the highlighted one (`VeyqTagsInput`'s header).
//
// **The first focus builds the index, every later one revalidates it** (`refreshSearchIndex`), and while the list is open
// the spaces are revalidated again every {@link REVALIDATE_MS} - each a `304` while nothing moved (ADR 0081). Nothing about
// the query leaves this component: the text, the chips and the results are local state. **The settings sections are read
// once**, on the first focus (cr-search-polish: a read per focus opened every group's grant and two account documents), and
// again only where the rail's answers move: the tenant's role on another tenant (`sections.ts` watches it), the groups when
// the spaces the index lists change - a group joined, left or deleted.
//
// **What a row draws.** The part of a name the text matched is bold (`nameParts`: found in the folded name, drawn over
// the name's own characters), as text nodes and a `strong` element - never markup from a string. A settings section listed
// for one of its words, not its label, draws that word after its second line, its matched part bold the same way
// (`reasonKeyword`, cr-search-visible-reasons): a result never stands there for a reason the member cannot see. A month chip reads its
// month in the UI's language. **A screen reader** hears, through a polite live region, how many results there are and a
// chip added or removed; the suggestions are named by their heading, the results by their own name, and the field
// stays the layer's combobox.
//
// **Opening a result.** A node opens through the navigation the sidebar uses - a push of its fragment, which enters its
// space when it lies in another (`SpaceView` re-enters on a new scope token); a contributed document opens itself (a photo
// in the open Memory's lightbox); an object a space's search found (a photo of another Memory) opens its node the same way
// and leaves the page a request to open it (`openRequest.ts`); a person opens their profile page in the tenant space, and a
// settings section opens on the settings page by a push, which keeps the active space as the profile menu's Settings entry
// does (a hard navigation would reset it). The field says what it searched: the spaces still loading, those it could not
// search, and - instead of results, when a query found nothing - what is searched.
//
// **A calm focus** (uc.platform.search step 1, cr-search-calm-focus): focusing starts the index (above) and opens
// nothing. The list is open only while something is asked - text or a chip: the first typed character opens it, and
// Backspace to an empty field with no chip closes it (with a chip left, the list stays and follows what remains). A
// space still loading is said only once the list is open AND the loading has lasted {@link LOADING_DELAY_MS} since
// then: a load shorter than that shows no state at all, so the panel does not jump through "N spaces…" lines on a
// click. With nothing to say and nothing listed, no panel shows (the status slot is passed only with words in it).
import { computed, onBeforeUnmount, ref, toRaw, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import VeyqTagsInput, { type VeyqTag, type VeyqTagOption } from '../../shared/ui/VeyqTagsInput.vue'
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
import { requestOpenItem } from './openRequest'
import { PERSON_DOCUMENT, SETTINGS_DOCUMENT, searchDocument, yearsOf, type SearchDocument } from './model'
import {
  chipKey, createMatcher, matchedText, nameParts, reasonKeyword, suggestChips, type ChipSuggestion, type NamePart, type SearchChip,
} from './query'
import { searchResults } from './results'
import { placeWithin } from './documents'
import { personDocuments, settingsDocuments } from './peopleAndSettings'
import { indexedDocuments, indexedScope, indexedTree, refreshSearchIndex, searchState, type SearchScope } from './searchIndex'

/** How often an open list revalidates every space (a `304` while nothing moved). */
const REVALIDATE_MS = 20_000
/** How many results the list shows at first, and how many more each "show more" adds (ADR 0125 decision 5): few rows drawn
 *  is what keeps the first keystroke's task short, which is mostly the list's render (cr-search-polish). */
const PAGE = 10
/** How long the open list waits on a loading space before it says so: a shorter load shows no state (see the header). */
const LOADING_DELAY_MS = 300

const props = defineProps<{ inputId?: string }>()
/** A result was opened (the sheet around the field closes). */
const emit = defineEmits<{ opened: [] }>()

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

const text = ref('')
const chips = ref<SearchChip[]>([])
const open = ref(false)
const shown = ref(PAGE)
/** The tenant's members as the directory last read them (none after a sign-out): the person chips and the person results. */
const people = computed(() => tenantPeopleNow())
/** The settings sections the rail offers now, with the rail's own gates (none until every answer is in). */
const sections = useSettingsSections()
const field = useTemplateRef<{ focus(): void }>('field')

/** Put the focus in the field (the shell's shortcut). */
defineExpose({ focus: () => field.value?.focus() })

/** The settings answers' one read (see the header), null until the first focus. */
let sectionsRead: Promise<void> | null = null
function onFocus() {
  void refreshSearchIndex().catch(() => undefined)
  void loadTenantPeople().catch(() => undefined)
  sectionsRead ??= sections.load()
}
/** The groups the index lists: one the settings answers do not know is a group joined (one they know and the index does
 *  not, left or deleted), and the rail's Groups collection moves with it. */
const groupIds = computed(() => searchState.scopes.filter((s) => s.kind === ScopeKind.Group).map((s) => s.id).sort().join(','))
watch(groupIds, async (ids) => {
  if (!sectionsRead) return
  await sectionsRead
  if (ids !== sections.myGroups.value.map((g) => g.scopeId).sort().join(',')) await sections.loadGroups()
})

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
  const byId = new Map(tree.map((n) => [n.id, n]))
  const node = byId.get(c.nodeId)
  const at = node ? placeWithin(node, byId) : { names: [], ids: [c.nodeId] }
  return c.documents.map((d) => searchDocument({ ...d, scopeId: c.scopeId, nodeId: c.nodeId, path: at.names, pathIds: at.ids }))
})
/** The page's own documents win over the same object a space's search found (ADR 0125 decision 8a): the open Memory's
 *  photo is the gallery's, which it opens in place. */
const content = computed(() => {
  const own = contributed.value
  const indexed = indexedDocuments()
  if (!own.length) return [...indexed]
  const mine = new Set(own.map((d) => `${d.scopeId}:${d.id}`))
  return [...indexed.filter((d) => d.id === d.nodeId || !mine.has(`${d.scopeId}:${d.id}`)), ...own]
})
/** The members and the settings sections (ADR 0125 decision 9): documents of no space, each opening itself. */
const elsewhere = computed<SearchDocument[]>(() => [
  ...personDocuments(people.value, (userId) => void router.push({ name: 'home', hash: personHash(userId) })),
  ...settingsDocuments(sections.offered.value, (id) => void router.push(`/settings/${id}`)),
])
const documents = computed(() => [...content.value, ...elsewhere.value])

// --- words ------------------------------------------------------------------------------------------------------------
function spaceLabel(scope: SearchScope | null): string {
  if (scope?.name) return scope.name
  if (scope?.kind === ScopeKind.Personal) return t('shell.personal')
  if (scope?.kind === ScopeKind.Tenant) return t('shell.shared')
  return t('shell.groups')
}
/** Each kind's word with the kinds it names (a word two kinds share is one chip): every kind's the registry knows, and
 *  core's two documents of no space (decision 9). */
const kindWords = computed(() => {
  const byLabel = new Map<string, Set<string>>()
  const icons = new Map<string, VeyqIconName>()
  const add = (label: string, kind: string) => byLabel.set(t(label), (byLabel.get(t(label)) ?? new Set()).add(kind))
  add('shell.search.kindPerson', PERSON_DOCUMENT)
  add('shell.search.kindSetting', SETTINGS_DOCUMENT)
  for (const kind of registry.kinds()) {
    const d = registry.kind(kind)
    if (!d.supported || !d.search) continue
    add(d.search.label, kind)
    for (const doc of d.search.documents ?? []) {
      add(doc.label, doc.kind)
      icons.set(doc.kind, doc.icon)
    }
  }
  return { kinds: [...byLabel].map(([label, kinds]) => ({ label, kinds: [...kinds] })), icons }
})
function iconOf(doc: SearchDocument): VeyqIconName {
  if (doc.icon) return doc.icon
  const d = registry.kind(doc.kind)
  return d.supported ? d.icon : (kindWords.value.icons.get(doc.kind) ?? 'file')
}
const dayFormat = computed(() => new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }))
const collator = computed(() => new Intl.Collator(locale.value, { numeric: true, sensitivity: 'base' }))

// --- the chips -------------------------------------------------------------------------------------------------------
const CHIP_ICON: Record<SearchChip['type'], VeyqIconName> = {
  month: 'calendar', year: 'calendar', place: 'map-marker', person: 'user', space: 'users', in: 'folder-open', kind: 'tag',
}
const CHIP_WORD: Record<SearchChip['type'], string> = {
  month: 'shell.search.chipMonth', year: 'shell.search.chipYear', place: 'shell.search.chipPlace', person: 'shell.search.chipPerson',
  space: 'shell.search.chipSpace', in: 'shell.search.chipIn', kind: 'shell.search.chipKind',
}
const monthFormat = computed(() => new Intl.DateTimeFormat(locale.value, { month: 'long', year: 'numeric' }))
/** Each month's names, January first: in the UI's language and in English ("Mai", "May"). */
const monthNames = computed(() => {
  const names = [new Intl.DateTimeFormat(locale.value, { month: 'long' }), new Intl.DateTimeFormat('en', { month: 'long' })]
  return Array.from({ length: 12 }, (_, i) => [...new Set(names.map((f) => f.format(new Date(2000, i, 15))))])
})
function chipLabel(c: SearchChip): string {
  if (c.type === 'year') return String(c.year)
  if (c.type === 'month') return monthFormat.value.format(new Date(c.year, c.month - 1, 15))
  return c.label
}

type ChipTag = VeyqTag & { chip: SearchChip }
type ChipOption = VeyqTagOption & { suggestion: ChipSuggestion }
type ResultOption = VeyqTagOption & { doc?: SearchDocument; more?: number }

const tags = computed<ChipTag[]>(() => chips.value.map((chip) => ({ key: chipKey(chip), label: chipLabel(chip), chip })))
const suggestions = computed<ChipOption[]>(() =>
  suggestChips(text.value, chips.value, {
    documents: content.value,
    spaces: searchState.scopes.map((s) => ({ scopeId: s.id, label: spaceLabel(s) })),
    people: people.value.map((p) => ({ userId: p.userId, name: p.displayName })),
    kinds: kindWords.value.kinds,
    months: monthNames.value,
  }).map((suggestion) => ({ key: `chip:${chipKey(suggestion.chip)}`, label: chipLabel(suggestion.chip), suggestion })),
)
/** Where an *In:* suggestion's node lies: its space and the names above it - what tells two nodes of one name apart. */
function chipWhere(c: SearchChip): string {
  return c.type === 'in' ? [spaceLabel(indexedScope(c.scopeId)), c.path.join(' / ')].filter(Boolean).join(' · ') : ''
}
/** What the live region says of the last chip added or removed, until the member types on. */
const chipNote = ref<{ said: string; text: string } | null>(null)
function addChip(option: ChipOption) {
  chips.value = [...chips.value, option.suggestion.chip]
  text.value = option.suggestion.text
  chipNote.value = { said: t('shell.search.announceChipAdded', { label: option.label }), text: text.value }
}
function removeChip(tag: ChipTag) {
  chips.value = chips.value.filter((c) => chipKey(c) !== tag.key)
  chipNote.value = { said: t('shell.search.announceChipRemoved', { label: tag.label }), text: text.value }
}
watch(text, (now) => {
  if (chipNote.value && now !== chipNote.value.text) chipNote.value = null
})

// --- the results -----------------------------------------------------------------------------------------------------
// the chips as plain objects: the query reads them once per document, and a reactive read each time costs a keystroke
// at volume (cr-search-at-volume); the matcher remembers the queries asked while the list is open and narrows them as the
// text grows, and the list is ranked only as far as it is shown (cr-search-polish). Its memory goes when the list
// closes, when the documents move (the index's next generation, a sign-out's reset included) and with the field.
const matcher = createMatcher()
watch(open, (isOpen) => {
  if (!isOpen) matcher.clear()
})
watch(documents, () => matcher.clear())
onBeforeUnmount(() => matcher.clear())
const found = computed(() =>
  searchResults(documents.value, { chips: chips.value.map((c) => toRaw(c)), text: text.value }, {
    activeScopeId: activeSpace.scopeId, openNodeId: openNode.value?.id ?? null,
  }, collator.value, { limit: shown.value, match: matcher }),
)
watch([text, chips], () => (shown.value = PAGE))
const resultOptions = computed<ResultOption[]>(() => {
  const { results, total } = found.value
  const rest = total - results.length
  return [
    ...results.map((doc) => ({ key: `result:${doc.scopeId}:${doc.kind}:${doc.id}`, label: doc.name, doc })),
    ...(rest > 0 ? [{ key: 'more', label: t('shell.search.showMore', { count: Math.min(rest, PAGE) }), more: rest }] : []),
  ]
})

/** What a row says under the name - where it lies, as the one list has no heading to say it: its own line (a person's
 *  handle, a section's heading), or its space and its path - the space always, the active one too, so a node at a
 *  space's top level still says where it lies (owner 2026-11-09) - a photo's day after it; a photo of the open Memory
 *  says its space and its day. */
function secondLine(option: ResultOption): string {
  const doc = option.doc!
  if (doc.subtitle !== undefined) return doc.subtitle
  const day = doc.facets.capturedAt !== undefined ? dayFormat.value.format(doc.facets.capturedAt) : ''
  const path = doc.open ? '' : doc.path.join(' / ')
  const where = [spaceLabel(indexedScope(doc.scopeId)), path].filter(Boolean).join(' · ')
  return [where, day].filter(Boolean).join(' · ')
}
/** The values of the chips in force a row carries (its years, its place, the person), shown beside it. */
function matchedValues(doc: SearchDocument): string[] {
  const out: string[] = []
  for (const c of chips.value) {
    if (c.type === 'year') {
      const y = yearsOf(doc)
      if (y) out.push(y.from === y.to ? String(y.from) : `${y.from}–${y.to}`)
    } else if (c.type === 'month') out.push(chipLabel(c))
    else if (c.type === 'place') {
      const at = doc.foldedPlaces.indexOf(c.place)
      if (at >= 0) out.push(doc.facets.places![at])
    } else if (c.type === 'person') out.push(c.label)
  }
  return [...new Set(out)]
}

function choose(option: ResultOption) {
  if (option.more !== undefined) {
    shown.value += PAGE
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
  // An object a space's search found opens its node's page, which opens the object once it holds it (`openRequest.ts`).
  if (doc.id !== doc.nodeId) requestOpenItem({ scopeId: doc.scopeId, nodeId: doc.nodeId, itemId: doc.id })
  void router.push({ name: 'home', hash: hashForNode([...tree], doc.nodeId, tokenOf(doc.scopeId)) })
}
/** The fragment's scope token of a space: the one on screen keeps the address it was reached by. */
function tokenOf(scopeId: string): string {
  if (scopeId === activeSpace.scopeId && route.name === 'home') return parseHash(route.hash).scope
  const scope = indexedScope(scopeId)
  if (scope?.kind === ScopeKind.Personal) return '~me'
  if (scope?.kind === ScopeKind.Group) return `@${scopeId}`
  return TENANT_SCOPE
}

/** The text as matched, once per keystroke: every drawn row's bold part is found with it. */
const matched = computed(() => matchedText(text.value))
const partsOf = (doc: SearchDocument): NamePart[] => nameParts(doc.name, matched.value)
/** A row's second line in parts ({@link secondLine}), and - where a settings word, not the name, is why the row is listed -
 *  that word after it with its matched part bold ("Persönlich · **Ken**nwort"): a result never stands there for a reason
 *  the member cannot see (cr-search-visible-reasons). */
function metaParts(option: ResultOption): NamePart[] {
  const line = secondLine(option)
  const word = reasonKeyword(option.doc!, matched.value)
  if (word === null) return line ? [{ text: line, strong: false }] : []
  return [...(line ? [{ text: `${line} · `, strong: false }] : []), ...nameParts(word, matched.value)]
}

// --- what the field says ---------------------------------------------------------------------------------------------
const asked = computed(() => text.value.trim() !== '' || chips.value.length > 0)
/** The live region's words (see the header): the last chip added or removed, then how many results; nothing until asked. */
const announcement = computed(() => {
  if (!asked.value) return chipNote.value?.said ?? ''
  const count = found.value.total
  return [chipNote.value?.said, t('shell.search.announceResults', { count }, count)].filter(Boolean).join(' ')
})
const loadingCount = computed(() => searchState.loading.length)
const notSearched = computed(() => searchState.failed.map((s) => spaceLabel(s)).join(', '))

// --- a calm focus (see the header) -----------------------------------------------------------------------------------
// The combobox opens on any input or arrow key; whatever opened it, the list stays open only while something is asked.
watch([open, asked], ([isOpen, isAsked]) => {
  if (isOpen && !isAsked) open.value = false
})
/** The open list has waited {@link LOADING_DELAY_MS} on a loading space: now it says so. */
const loadingLong = ref(false)
let loadingTimer: ReturnType<typeof setTimeout> | undefined
watch(
  () => open.value && loadingCount.value > 0,
  (waiting) => {
    clearTimeout(loadingTimer)
    loadingLong.value = false
    if (waiting) loadingTimer = setTimeout(() => (loadingLong.value = true), LOADING_DELAY_MS)
  },
  { immediate: true },
)
onBeforeUnmount(() => clearTimeout(loadingTimer))
const loadingShown = computed(() => loadingLong.value && loadingCount.value > 0)
/** The query found nothing and nothing is loading any more: the empty state, which names what is searched (uc A2). */
const nothingFound = computed(() => asked.value && !found.value.total && !suggestions.value.length && loadingCount.value === 0)
/** The status line has words: only then is the slot passed, so an empty list shows no panel. */
const statusSaid = computed(() => loadingShown.value || !!notSearched.value || searchState.listFailed || nothingFound.value)
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
    :results="resultOptions"
    :results-label="t('shell.search.results')"
    :options-label="t('shell.search.filterBy')"
    :placeholder="t('shell.search.placeholder')"
    :aria-label="t('shell.search.label')"
    autocomplete="off"
    @focus="onFocus"
    @add="addChip"
    @remove="removeChip"
    @choose="choose"
  >
    <template #leading><VeyqIcon name="search" class="veyq-search__icon" /></template>
    <template #tag="{ tag }"><VeyqIcon :name="CHIP_ICON[tag.chip.type]" class="veyq-search__chip-icon" /></template>
    <template #option="{ option }">
      <VeyqIcon :name="CHIP_ICON[option.suggestion.chip.type]" />
      <span v-if="option.suggestion.chip.type === 'in'" class="veyq-search__result-main">
        <span class="veyq-search__option-label">{{ option.label }}</span>
        <span class="veyq-search__result-meta">{{ chipWhere(option.suggestion.chip) }}</span>
      </span>
      <span v-else class="veyq-search__option-label">{{ option.label }}</span>
      <span class="veyq-search__option-kind">{{ t(CHIP_WORD[option.suggestion.chip.type]) }}</span>
    </template>
    <template #result="{ option }">
      <span v-if="option.more !== undefined" class="veyq-search__more">{{ option.label }}</span>
      <template v-else-if="option.doc">
        <PersonAvatar v-if="option.doc.kind === PERSON_DOCUMENT" :user-id="option.doc.id" :name="option.doc.name" class="veyq-search__result-avatar" />
        <VeyqIcon v-else :name="iconOf(option.doc)" class="veyq-search__result-icon" />
        <span class="veyq-search__result-main">
          <span class="veyq-search__result-name"><template v-for="(part, i) in partsOf(option.doc)" :key="i"><strong v-if="part.strong" class="veyq-search__match">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template></span>
          <span v-if="metaParts(option).length" class="veyq-search__result-meta"><template v-for="(part, i) in metaParts(option)" :key="i"><strong v-if="part.strong" class="veyq-search__match">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template></span>
        </span>
        <span v-for="v in matchedValues(option.doc)" :key="v" class="veyq-badge muted veyq-search__result-chip">{{ v }}</span>
      </template>
    </template>
    <template v-if="statusSaid" #status>
      <span v-if="loadingShown" class="veyq-search__loading">{{ t('shell.search.loading', { count: loadingCount }, loadingCount) }}</span>
      <span v-if="notSearched" class="veyq-search__failed">{{ t('shell.search.notSearched', { spaces: notSearched }) }}</span>
      <span v-if="searchState.listFailed" class="veyq-search__failed">{{ t('shell.search.listFailed') }}</span>
      <template v-if="nothingFound">
        <span class="veyq-search__empty">{{ t('shell.search.empty') }}</span>
        <span class="veyq-search__hint">{{ t('shell.search.hint') }}</span>
      </template>
    </template>
  </VeyqTagsInput>
  <span class="visually-hidden" role="status" aria-live="polite" aria-atomic="true" data-testid="search-announcement">{{ announcement }}</span>
  </div>
</template>
