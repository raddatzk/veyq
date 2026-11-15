<script setup lang="ts">
// A music track's Rename dialog (uc.memory.background-music A7, ADR 0135 decision 4): its own name. An emptied name gives
// the track its file's name back (shown as the field's placeholder). Presentation only: the Music tab seals the name into
// the track's listed tier and says how the write went (`saving`, `error`).
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'

const props = defineProps<{
  /** The name a member gave it; null: it is named by its file. */
  name: string | null
  /** What the track is named without a name of its own: its file's name without the extension. */
  fallbackName: string | null
  saving?: boolean
  /** Why the last save did not land: a peer renamed it meanwhile (the field shows theirs), or it failed. */
  error?: 'conflict' | 'failed' | null
}>()
const emit = defineEmits<{ save: [name: string | null]; close: [] }>()
const { t } = useI18n()

const value = ref(props.name ?? '')

function save() {
  if (props.saving) return
  const trimmed = value.value.trim()
  emit('save', trimmed ? trimmed : null)
}

/** Show a peer's name in the field (after a conflict, the Music tab hands it in). */
function reset(name: string | null) {
  value.value = name ?? ''
}
defineExpose({ reset })
</script>

<template>
  <VeyqDialog :visible="true" :style="{ minWidth: 'min(28rem, calc(100vw - 2rem))' }" :header="t('memory.music.renameTitle')" @update:visible="emit('close')">
    <form class="veyq-form" data-testid="music-rename" @submit.prevent="save">
      <label>
        {{ t('memory.music.nameLabel') }}
        <VeyqInput v-model="value" fluid data-testid="music-rename-name" :placeholder="fallbackName ?? ''" :disabled="saving" />
      </label>
      <p class="music-rename-hint">{{ t('memory.music.nameHint') }}</p>
      <VeyqMessage v-if="error" tone="error">{{ t(error === 'conflict' ? 'memory.music.renameConflict' : 'memory.music.renameFailed') }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="saving" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.save')" icon="check" :loading="saving" data-testid="music-rename-save" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.music-rename-hint {
  margin: -0.5rem 0 0;
  color: var(--app-muted);
  font-size: 0.875rem;
}
</style>
