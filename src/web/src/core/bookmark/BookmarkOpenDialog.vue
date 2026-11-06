<script setup lang="ts">
// The one dialog a bookmark of ANOTHER instance with a password opens before its tab (ADR 0120 decision 5), mounted once in
// `App.vue` and driven by `openBookmark.ts`' `bookmarkOpenRequest`. A password never travels in a URL and no other origin
// can be handed anything, so the member copies it here and the link opens as a real link in a new tab (`noopener`,
// `noreferrer`) - its own click, so no browser holds it back as a pop-up. The same dialog serves a report hand-off.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import { bookmarkOpenRequest } from './openBookmark'

const { t } = useI18n()
const request = computed(() => bookmarkOpenRequest.value)
const copied = ref(false)
watch(request, () => (copied.value = false))

function close() {
  bookmarkOpenRequest.value = null
}
async function copy() {
  const password = request.value?.password
  if (!password) return
  try {
    await navigator.clipboard.writeText(password)
    copied.value = true
  } catch {
    /* clipboard denied: the password stays to be typed */
  }
}
</script>

<template>
  <VeyqDialog
    v-if="request"
    :visible="true"
    :header="request.report ? t('node.bookmark.reportAwayTitle') : t('node.bookmark.openAwayTitle')"
    @update:visible="close"
  >
    <div class="veyq-form" data-testid="bookmark-open-dialog">
      <p class="veyq-form-copy">{{ t('node.bookmark.openAwayIntro', { host: request.host }) }}</p>
      <div class="veyq-dialog-action-row">
        <VeyqButton
          data-testid="bookmark-open-copy"
          :label="copied ? t('node.bookmark.passwordCopied') : t('node.bookmark.copyPassword')"
          icon="copy"
          variant="secondary"
          appearance="outlined"
          @click="copy"
        />
        <VeyqButton
          data-testid="bookmark-open-away"
          :label="request.report ? t('node.bookmark.reportAway') : t('node.bookmark.openLink')"
          icon="external-link"
          :href="request.url"
          target="_blank"
          rel="noopener noreferrer"
          @click="close"
        />
      </div>
    </div>
  </VeyqDialog>
</template>
