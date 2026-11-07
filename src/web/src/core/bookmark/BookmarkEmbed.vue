<script setup lang="ts">
// A bookmark's link shown in place, under the bookmark's header (ADR 0121 decisions 1, 4-8): the share of this or any veyq
// instance, read and — where the link allows — written as that link's guest.
//
// **First, may it be shown here** (decision 4): the share's instance is asked what it serves (`/api/setup`, `readShareInstance`)
// and `embeddability` decides; a refusal — and a resolve that got no answer at all, which is what a browser makes of an
// instance that does not answer this origin — is handed back as `fallback`, and the bookmark's page opens the link as before
// with one line saying why (`BookmarkDetail.vue`). This instance's own switch, `core.share-embedding`, is the page's to read:
// off, this component is never mounted.
//
// **Then the share page's gates and views** (decisions 5-6), core's `useShareGates` drawn by `ShareGates` (embedded): the
// password — the bookmark's, entered once, else the form —, the share's tenant's terms, the note's acceptance, the guest's
// naming; then core's page of the link's tree (navigating inside itself) or a `WRITE_ONLY` link's drop page. Every read and
// write goes to the share's instance (`apiBase`, `''` for this origin's link), as the link's guest (decision 7), and so does a
// report from below, sealed to that instance's key (decision 8; the drop page's report of the link itself is this page's).
//
// **A guest inside a member's page** (decision 5): this region provides a host with NO door, no core actions and no header
// sink, so nothing below it reaches the member's doors, actions or header — core's page of the link provides the guest's own
// below that. Nothing flows up: the guest's access and tree live in this component and below it, never in the member's tree.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import { EXPECTED_MAJOR } from '../../shared/state/capabilities'
import { NO_DOORS, provideNodeHost } from '../node/nodeHost'
import { provideNoCoreNodeActions } from '../node/coreNodeActions'
import { provideNestedNodeHeading, provideNoNodeHeader } from '../node/nodeHeaderContribution'
import ShareGates from '../sharing/ShareGates.vue'
import { useShareGates } from '../sharing/shareGates'
import { apiBaseFor } from '../sharing/shareAt'
import { embeddability, readShareInstance, type EmbedRefusal } from '../sharing/shareInstance'
import { ShareRole } from '../../api/generated/sharing'
import { openReport, useReportOffered } from '../report/report'
import { linkReportKeys } from '../report/reportKeys'
import { hostOf } from './bookmarkLink'
import type { Bookmark } from './bookmarkMeta'

const props = defineProps<{ bookmark: Bookmark }>()
const emit = defineEmits<{ fallback: [reason: EmbedRefusal] }>()
const { t, locale } = useI18n()

provideNodeHost(NO_DOORS)
provideNoCoreNodeActions()
provideNoNodeHeader()
provideNestedNodeHeading() // the bookmark's header is the page's h1; the share's node is named under it, as a section

const { link } = props.bookmark
const apiBase = apiBaseFor(link.origin, window.location.origin)
const fragment = link.link.slice(link.link.indexOf('#k=') + 3)
let password = props.bookmark.password ?? null
const gates = useShareGates({
  at: { shareId: link.shareId, apiBase },
  fragmentKey: () => fragment,
  // the bookmark's password, entered once: a wrong one leaves the form to the member
  handedPassword: () => {
    const handed = password
    password = null
    return handed
  },
  locale,
})

/** The share's instance said it may be shown here; until then the page says it is opening. */
const shown = ref(false)
onMounted(async () => {
  const verdict = embeddability({ embedding: true, setup: await readShareInstance(apiBase), major: EXPECTED_MAJOR })
  if (!verdict.embeds) {
    emit('fallback', verdict.reason)
    return
  }
  shown.value = true
  await gates.load()
  if (gates.status.value === 'unavailable' && gates.unreachable.value) emit('fallback', 'unreachable')
})

// An upload link's guest sees no node: it reports the link itself, to the share's instance (the share page's footer action).
const reportOn = useReportOffered(apiBase)
const reportsLink = computed(() => reportOn.value && gates.status.value === 'ready' && gates.head.value?.role === ShareRole.WriteOnly)
function reportLink() {
  const a = gates.access.value
  if (a) openReport({ access: a, scopeId: a.head.scopeId, targets: [{ kind: 'LINK', keys: () => linkReportKeys(a) }] })
}
</script>

<template>
  <section class="bookmark-embed" :aria-label="t('node.bookmark.embedRegion', { host: hostOf(link) })" data-testid="bookmark-embed">
    <p v-if="!shown" class="veyq-empty">
      <VeyqIcon name="spinner" spin />
      <span>{{ t('share.loading') }}</span>
    </p>
    <template v-else>
      <ShareGates :gates="gates" embedded />
      <button v-if="reportsLink" type="button" class="bookmark-embed-report" data-testid="bookmark-embed-report" @click="reportLink">
        {{ t('report.reportLink') }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.bookmark-embed {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-height: 12rem;
}
.bookmark-embed-report {
  align-self: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--app-muted);
  font-size: 0.82rem;
  text-decoration: underline;
  cursor: pointer;
}
</style>
