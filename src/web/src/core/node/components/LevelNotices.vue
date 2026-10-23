<script setup lang="ts">
// The messages an application shows above the unified level list (inbox #407): why a file was not uploaded, what a
// contributor's upload does. The application supplies the text and the buttons (`LevelNotice`, `../levelRow.ts`); core
// draws them, with a dismiss button where the notice can be dismissed.
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { LevelNotice } from '../levelRow'

defineProps<{ notices: readonly LevelNotice[] }>()
const { t } = useI18n()
</script>

<template>
  <VeyqMessage
    v-for="n in notices"
    :key="n.key"
    :tone="n.severity === 'error' ? 'error' : 'info'"
    class="veyq-level-notice"
  >
    <span class="veyq-level-notice-body">
      <span class="veyq-level-notice-text">{{ n.text }}</span>
      <span v-if="n.actions?.length || n.dismiss" class="veyq-action-group">
        <VeyqButton
          v-for="a in n.actions"
          :key="a.id"
          :label="a.label"
          size="small"
          variant="secondary"
          appearance="outlined"
          @click="a.onClick()"
        />
        <VeyqButton v-if="n.dismiss" :label="t('node.dismiss')" size="small" variant="secondary" appearance="text" @click="n.dismiss()" />
      </span>
    </span>
  </VeyqMessage>
</template>
