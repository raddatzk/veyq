<script setup lang="ts">
// A bookmark's page (ADR 0120, ADR 0121), under core's header. While this instance embeds (`core.share-embedding`, effective
// for the member's tenant) it shows the link's share in place (`./BookmarkEmbed.vue`): read and, where the link allows,
// written, as that link's guest, through the share's own instance - and a plain click on the bookmark's row lands here
// (`./bookmarkRows.ts`). Where the share may not be shown here, the page is ADR 0120's - where the link points (its host),
// the way to open it and, where the bookmark keeps one, the share's password to copy - with one line saying why; with
// embedding switched off it is that page as it always was, and the row opens the link directly. The header carries "Open
// on its own page" (ADR 0120 decision 5's open), core's rename in place, move and delete, each where `./rights.ts` grants it
// and the page has the door, and core's own actions, Report among them (for a bookmark: the share's report, handed to its
// share page; `core/node/coreNodeActions.ts`). A guest is never handed a bookmark, and is offered nothing here if one were.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import type { NodeDetailProps } from '../applications/registry'
import { hostHandlers, hostRights, injectNodeHost, shareOfferOf } from '../node/nodeHost'
import { standardNodeActions, standardNodeRename } from '../node/nodeHeader'
import { contributeNodeHeader } from '../node/nodeHeaderContribution'
import { targetOf } from '../access'
import { bookmarkRights } from './rights'
import { bookmarkOfNode, openBookmark, useBookmarkDoors } from './openBookmark'
import { hostOf } from './bookmarkLink'
import BookmarkEmbed from './BookmarkEmbed.vue'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { CORE_SHARE_EMBEDDING } from '../../features/platform/capabilities'
import type { EmbedRefusal } from '../sharing/shareInstance'

const props = defineProps<NodeDetailProps>()
const host = injectNodeHost()
const { t } = useI18n()
const doors = useBookmarkDoors()

const rights = computed(() => bookmarkRights(props.access, targetOf(props.tree, props.node)))
const kept = computed(() => (rights.value.open ? bookmarkOfNode(props.access, props.tree, props.node) : null))
const handlers = hostHandlers(host, () => props.node, () => shareOfferOf({}))
const sameOrigin = computed(() => kept.value?.link.origin === doors.origin)
/** ADR 0120 decision 5's open, kept in the header whether or not the share is shown here (ADR 0121 decision 1). */
const openOwnPage = computed(() => ({
  id: 'open-own-page', label: t('node.bookmark.openOwnPage'), icon: sameOrigin.value ? ('link' as const) : ('external-link' as const),
  shown: kept.value !== null, onClick: () => kept.value && openBookmark(kept.value, false, doors),
}))
const headerActions = computed(() => [
  openOwnPage.value,
  ...standardNodeActions(t, { ...hostRights(rights.value, host), deleteLabel: t('node.bookmark.delete') }, handlers),
])
const headerRename = computed(() => standardNodeRename(hostRights(rights.value, host), handlers))
const subtitle = computed(() => (kept.value ? hostOf(kept.value.link) : undefined))
contributeNodeHeader(() => ({ subtitle: subtitle.value, actions: headerActions.value, rename: headerRename.value }))

const copied = ref(false)
async function copyPassword() {
  const password = kept.value?.password
  if (!password) return
  try {
    await navigator.clipboard.writeText(password)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard denied: the member opens the link and types it */
  }
}

// Shown in place while this instance embeds and the share's instance lets it; else ADR 0120's page, saying why.
const { hasCapability } = useCapabilities()
const fallback = ref<EmbedRefusal | null>(null)
const embeds = computed(() => kept.value !== null && hasCapability(CORE_SHARE_EMBEDDING) && fallback.value === null)
</script>

<template>
  <BookmarkEmbed v-if="embeds && kept" :key="kept.link.link" :bookmark="kept" @fallback="(reason) => (fallback = reason)" />
  <section v-else class="veyq-surface bookmark-detail" data-testid="bookmark-detail">
    <p v-if="kept && fallback && fallback !== 'switchedOff'" class="bookmark-detail-fallback" data-testid="bookmark-fallback">
      {{ t(`node.bookmark.fallback.${fallback}`, { host: hostOf(kept.link) }) }}
    </p>
    <template v-if="kept">
      <p class="bookmark-detail-where">
        {{ sameOrigin ? t('node.bookmark.whereHere') : t('node.bookmark.whereAway', { host: hostOf(kept.link) }) }}
      </p>
      <div class="bookmark-detail-actions">
        <VeyqButton
          data-testid="bookmark-open"
          :label="t('node.bookmark.openLink')"
          :icon="sameOrigin ? 'link' : 'external-link'"
          @click="openBookmark(kept, false, doors)"
        />
        <VeyqButton
          v-if="kept.password"
          data-testid="bookmark-copy-password"
          :label="copied ? t('node.bookmark.passwordCopied') : t('node.bookmark.copyPassword')"
          icon="copy"
          variant="secondary"
          appearance="outlined"
          @click="copyPassword"
        />
      </div>
      <p v-if="kept.password" class="veyq-form-copy">{{ t('node.bookmark.hasPassword') }}</p>
    </template>
    <VeyqMessage v-else-if="rights.open" tone="error" data-testid="bookmark-unreadable">{{ t('node.bookmark.unreadable') }}</VeyqMessage>
  </section>
</template>

<style scoped>
.bookmark-detail {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1.25rem;
}
.bookmark-detail-fallback {
  margin: 0;
  color: var(--app-muted);
  overflow-wrap: anywhere;
}
.bookmark-detail-where {
  margin: 0;
  overflow-wrap: anywhere;
}
.bookmark-detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
