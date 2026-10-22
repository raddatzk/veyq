<script setup lang="ts">
// The settings hub's way into the moderation surface (uc.moderation.serve-as-moderator step 1): shown only to an account
// with a pending or accepted appointment, which the hub reads and hands in. It is a door, not the surface: the hub runs
// with the member's vault open, and the page that renders reported content holds the moderation key and nothing of the
// vault (ADR 0099 § 6). So the door is a real link that loads `/moderation` afresh in a new tab — `boot.ts` skips the
// remembered-vault restore there — and never a router push, which would carry this tab's open vault onto that page and
// have its unlock (`clearVault`) lock the shell the member came from.
//
// Markup mirrors TrustResetPanel (veyq-surface + settings-form + settings-section-header).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { MODERATION_PATH } from '../../app/moderationPath'
import { ModeratorAppointmentStateEnum, type ModeratorAppointment } from '../../api/generated/moderation'

const props = defineProps<{ appointment: ModeratorAppointment }>()
const { t } = useI18n()

const pending = computed(() => props.appointment.state === ModeratorAppointmentStateEnum.Pending)
const pageUrl = `${globalThis.location?.origin ?? ''}${MODERATION_PATH}`
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('moderation.entry.title') }}</h2>
          <p>{{ pending ? t('moderation.entry.pending') : t('moderation.entry.accepted') }}</p>
        </div>
      </div>

      <p class="moderation-entry-copy">{{ t('moderation.entry.separate') }}</p>
      <Button
        as="a"
        class="moderation-entry-open"
        data-test="moderation-entry-open"
        :href="MODERATION_PATH"
        target="_blank"
        rel="noopener"
        icon="pi pi-external-link"
        :label="pending ? t('moderation.entry.openPending') : t('moderation.entry.open')"
      />
      <p class="moderation-entry-address">{{ t('moderation.entry.address', { url: pageUrl, path: MODERATION_PATH }) }}</p>
    </section>
  </section>
</template>

<style scoped>
.moderation-entry-copy {
  margin: 1rem 0;
}
.moderation-entry-open {
  text-decoration: none;
}
.moderation-entry-address {
  margin: 1rem 0 0;
  color: var(--app-muted);
  font-size: 0.9rem;
  overflow-wrap: anywhere;
}
</style>
