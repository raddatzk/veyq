<script setup lang="ts">
// Root. No app shell to pick between: this surface has exactly one authenticated screen, so the router view
// renders directly and only the unreachable-instance state pre-empts it. The <Toast> is the same transient
// acknowledgement surface the moved panels already expect (ADR 0039).
import Toast from 'primevue/toast'
import Message from 'primevue/message'
import { useI18n } from 'vue-i18n'
import { serverState } from './shared/state/serverState'

const { t } = useI18n()
</script>

<template>
  <main class="admin-root">
    <Message v-if="serverState.unreachable" severity="error" :closable="false" class="admin-root__error">
      {{ t('admin.unreachable') }}
    </Message>
    <RouterView v-else />
  </main>
  <Toast />
</template>

<style scoped>
.admin-root {
  min-height: 100vh;
  background: var(--app-bg);
  color: var(--app-text);
}
.admin-root__error {
  max-width: 32rem;
  margin: 4rem auto;
}
</style>
