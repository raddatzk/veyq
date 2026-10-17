<script setup lang="ts">
// The guest-write record's first-layer notice (ADR 0093 § 10, en.moderation.guest-write-record): wherever a link's guest
// can write, before its first write — the naming step, the line that says whose session the device holds, the drop
// pages (Q13 of cr-guest-write-record). Two sentences: what is kept and for how long, with the N the resolve served
// (`PublicShare.guestWriteRecordDays`, the N current at the write), and the right to object (GDPR Art. 21) as a sentence
// of its own (Art. 21(4)). `days` null — the switch is off, or a READ link, where nothing is recorded — renders nothing:
// the notice follows the switch, never the operating mode.
//
// The wording waits on counsel (ADR 0093 § Legal basis, Still open 4: what the port is worth behind an operator's own
// NAT); the full Art. 13 text is the operator section of the terms, whose link joins here when that section exists
// (cr-operator-section-of-the-terms, anchor `#guest-write-record`).
import { useI18n } from 'vue-i18n'

defineProps<{ days?: number | null }>()
const { t } = useI18n()
</script>

<template>
  <p v-if="days" class="guest-write-record-notice" data-testid="guest-write-record-notice">
    <span class="guest-write-record-kept">{{ t('share.guest.recordKept', { days }, days) }}</span>
    {{ ' ' }}
    <span class="guest-write-record-objection">{{ t('share.guest.recordObjection') }}</span>
  </p>
</template>

<style scoped>
.guest-write-record-notice {
  margin: 0.25rem 0 0;
  font-size: 0.85rem;
  color: var(--app-muted);
}
</style>
