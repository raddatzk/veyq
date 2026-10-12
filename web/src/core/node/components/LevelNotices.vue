<script setup lang="ts">
// The messages an application shows above the unified level list (inbox #407): why a file was not uploaded, what a
// contributor's upload does. The application supplies the text and the buttons (`LevelNotice`, `../levelRow.ts`); core
// draws them, with a dismiss button where the notice can be dismissed.
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import type { LevelNotice } from '../levelRow'

defineProps<{ notices: readonly LevelNotice[] }>()
const { t } = useI18n()
</script>

<template>
  <Message
    v-for="n in notices"
    :key="n.key"
    :severity="n.severity === 'error' ? 'error' : 'info'"
    :closable="false"
    class="veyq-level-notice"
  >
    <span class="veyq-level-notice-body">
      <span class="veyq-level-notice-text">{{ n.text }}</span>
      <span v-if="n.actions?.length || n.dismiss" class="veyq-action-group">
        <Button
          v-for="a in n.actions"
          :key="a.id"
          type="button"
          :label="a.label"
          size="small"
          severity="secondary"
          outlined
          @click="a.onClick()"
        />
        <Button v-if="n.dismiss" type="button" :label="t('node.dismiss')" size="small" severity="secondary" text @click="n.dismiss()" />
      </span>
    </span>
  </Message>
</template>
