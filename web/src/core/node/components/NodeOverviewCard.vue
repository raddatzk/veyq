<script setup lang="ts">
// The header card of an opened container node (inbox #385 (b)): its icon, its name, and its share, rename and delete as
// icon buttons. Core's level view heads a folder with it; an application's detail of a container kind heads its node with
// the same card (Files' drop box), so both pages open the same way. What it offers is the caller's capability answer —
// the card decides nothing. The markup was the level view's own (`LevelView.vue`, whose golden pins it).
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'

withDefaults(
  defineProps<{
    icon: string
    name: string
    share?: boolean
    rename?: boolean
    remove?: boolean
    /** Accessible names of the actions, where the kind says more than core's neutral words. */
    shareLabel?: string
    deleteLabel?: string
  }>(),
  { share: false, rename: false, remove: false },
)
const emit = defineEmits<{ share: []; rename: []; delete: [] }>()
const { t } = useI18n()
</script>

<template>
  <section class="veyq-section">
    <div class="veyq-section-header">
      <div class="veyq-card veyq-folder-card veyq-folder-overview-card">
        <span :class="icon" />
        <h2>{{ name }}</h2>
        <!-- Visible at EVERY width: the card has no overflow menu, so a width-hidden button is a lost action. They
             carried the Memory header's collapse classes, which hid all three at 721–1120 px and ≤ 720 px (found in the
             browser, 2026-10-07); three small icon buttons fit, and ≤ 520 px they take their own row (ui.css). -->
        <div v-if="share || rename || remove" class="veyq-folder-overview-actions">
          <!-- The same affordance as a Memory's share button (icon, label, tooltip); listing and copying links is a
               read, so it stays during a pending deletion, where a new link is refused server-side anyway. -->
          <Button
            v-if="share"
            v-tooltip.bottom="shareLabel ?? t('share.share')"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button veyq-folder-overview-action"
            icon="pi pi-share-alt"
            :aria-label="shareLabel ?? t('share.share')"
            severity="secondary"
            outlined
            size="small"
            @click="emit('share')"
          />
          <Button
            v-if="rename"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button veyq-folder-overview-action"
            icon="pi pi-pencil"
            :aria-label="t('node.rename')"
            severity="secondary"
            outlined
            size="small"
            @click="emit('rename')"
          />
          <Button
            v-if="remove"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button veyq-folder-overview-action veyq-danger-action"
            icon="pi pi-trash"
            :aria-label="deleteLabel ?? t('node.delete')"
            severity="danger"
            outlined
            size="small"
            @click="emit('delete')"
          />
        </div>
      </div>
    </div>
  </section>
</template>
