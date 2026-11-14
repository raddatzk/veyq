<script setup lang="ts">
// A track's Edit dialog (uc.memory.tracks A8, ADR 0130 decision 9), the old application's: its name and its activity. An
// emptied name gives the track its file's name back (shown as the field's placeholder); "No activity" draws it solid.
// Presentation only: the Map tab seals the label into the track's listed tier and says how the write went (`saving`,
// `error`).
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import TrackLabelFields from './TrackLabelFields.vue'
import { labelChoices, labelFrom, type TrackActivityChoice, type TrackLabel } from '../track/trackLabel'

const props = defineProps<{
  /** The name a member gave it (null: named by its file) and its activity. */
  label: TrackLabel
  /** What the track is named without a name of its own: its file's track name, else its file name. */
  fallbackName: string | null
  saving?: boolean
  /** Why the last save did not land: a peer changed the label meanwhile (the fields show theirs), or it failed. */
  error?: 'conflict' | 'failed' | null
}>()
const emit = defineEmits<{ save: [TrackLabel]; close: [] }>()
const { t } = useI18n()

const start = labelChoices(props.label)
const name = ref(start.name)
const activity = ref<TrackActivityChoice>(start.activity)

function save() {
  if (props.saving) return
  emit('save', labelFrom(name.value, activity.value))
}

/** Show a peer's label in the fields (after a conflict, the Map tab hands it in). */
function reset(label: TrackLabel) {
  const next = labelChoices(label)
  name.value = next.name
  activity.value = next.activity
}
defineExpose({ reset })
</script>

<template>
  <VeyqDialog :visible="true" :style="{ minWidth: 'min(28rem, calc(100vw - 2rem))' }" :header="t('memory.tracks.editTitle')" @update:visible="emit('close')">
    <form class="veyq-form" data-testid="track-edit" @submit.prevent="save">
      <TrackLabelFields v-model:name="name" v-model:activity="activity" :fallback-name="fallbackName" :disabled="saving" hint test-id="track-edit" />
      <VeyqMessage v-if="error" tone="error">{{ t(error === 'conflict' ? 'memory.tracks.editConflict' : 'memory.tracks.editFailed') }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="saving" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.save')" icon="check" :loading="saving" data-testid="track-edit-save" />
      </div>
    </form>
  </VeyqDialog>
</template>
