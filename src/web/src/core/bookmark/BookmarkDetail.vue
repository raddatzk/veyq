<script setup lang="ts">
// A bookmark's page (ADR 0120), under core's header: where the link points (its host), the way to open it, and - where the
// bookmark keeps one - the share's password to copy. A plain click on its row opens the link directly; this page is where a
// favorite, a URL or a bookmark whose meta does not read lands. Its header carries core's rename in place, move and delete,
// each where `./rights.ts` grants it and the page has the door, and core's own actions, Report among them (for a bookmark:
// the share's report, handed to its share page; `core/node/coreNodeActions.ts`). A guest is never handed a bookmark, and is
// offered nothing here if one were.
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

const props = defineProps<NodeDetailProps>()
const host = injectNodeHost()
const { t } = useI18n()
const doors = useBookmarkDoors()

const rights = computed(() => bookmarkRights(props.access, targetOf(props.tree, props.node)))
const kept = computed(() => (rights.value.open ? bookmarkOfNode(props.access, props.tree, props.node) : null))
const handlers = hostHandlers(host, () => props.node, () => shareOfferOf({}))
const headerActions = computed(() => standardNodeActions(t, { ...hostRights(rights.value, host), deleteLabel: t('node.bookmark.delete') }, handlers))
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
const sameOrigin = computed(() => kept.value?.link.origin === doors.origin)
</script>

<template>
  <section class="veyq-surface bookmark-detail" data-testid="bookmark-detail">
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
