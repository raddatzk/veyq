<script setup lang="ts">
// The link every trust-state refusal notice carries to the settings hub's one reset (ADR 0028 Decision 7's way
// back, inbox #192): the pin-store notices (home, both member panels) and the register's
// (devices and sessions). One component, so the hub gains ONE more door rather than five — it is listed in
// `veyq/one-settings-door`'s ignores with that reason. It only navigates; starting a document over is the confirm
// dialog's alone.
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <RouterLink class="trust-reset-link" to="/settings/trust-reset" data-test="trust-reset-link">
    {{ t('trustReset.link') }}
  </RouterLink>
</template>

<style scoped>
/* ChangePasswordPanel's `.settings-link-action` — a quiet underlined link under the notice it belongs to. */
.trust-reset-link {
  display: inline-block;
  margin-top: 0.35rem;
  color: inherit;
  font-size: 0.85rem;
  text-decoration: underline;
}
</style>
