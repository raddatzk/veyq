<script setup lang="ts">
// Blocking incompatibility screen (uc.platform.capability-discovery major-hard-gate, cr-0103, ADR 0032). Shown
// by the AppShell BEFORE login whenever the instance's advertised `major` differs from the one this build was
// compiled against — a hard stop, never a silent partial operation. The copy is direction-specific: update the
// app when the server is newer, update the server when it is older.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCapabilities } from '../../shared/state/useCapabilities'

const { t } = useI18n()
const { incompatibility } = useCapabilities()
const kind = computed(() => incompatibility())
</script>

<template>
  <section class="incompatible" role="alert" aria-live="assertive">
    <h1 class="incompatible-title">{{ t('incompatible.title') }}</h1>
    <p class="incompatible-body">
      {{ kind === 'update-app' ? t('incompatible.updateApp') : t('incompatible.updateServer') }}
    </p>
    <p class="incompatible-hint">{{ t('incompatible.hint') }}</p>
  </section>
</template>

<style scoped>
.incompatible {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  text-align: center;
}
.incompatible-title {
  font-size: 1.25rem;
  font-weight: 600;
}
.incompatible-hint {
  opacity: 0.7;
  font-size: 0.9rem;
}
</style>
