<script setup lang="ts">
// A track's Edit dialog (uc.memory.tracks A8, ADR 0130 decision 9), the old application's: its name and its activity. An
// emptied name gives the track its file's name back (shown as the field's placeholder); "No activity" draws it solid.
// Presentation only: the Map tab seals the label into the track's listed tier and says how the write went (`saving`,
// `error`).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqSelect, { type VeyqSelectOption } from '../../../shared/ui/VeyqSelect.vue'
import { TRACK_ACTIVITIES, type TrackActivity, type TrackLabel } from '../track/trackLabel'

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

/** The select's value for no activity: an option's value may not be empty. */
const NONE = 'none'
type Choice = TrackActivity | typeof NONE

const name = ref(props.label.name ?? '')
const activity = ref<Choice>(props.label.activity ?? NONE)

const options = computed<VeyqSelectOption<Choice>[]>(() => [
  { label: t('memory.tracks.activity.none'), value: NONE },
  ...TRACK_ACTIVITIES.map((a) => ({ label: t(`memory.tracks.activity.${a}`), value: a })),
])

function save() {
  if (props.saving) return
  const chosen = activity.value
  emit('save', { name: name.value.trim() || null, activity: chosen === NONE ? null : chosen })
}

/** Show a peer's label in the fields (after a conflict, the Map tab hands it in). */
function reset(label: TrackLabel) {
  name.value = label.name ?? ''
  activity.value = label.activity ?? NONE
}
defineExpose({ reset })
</script>

<template>
  <VeyqDialog :visible="true" :style="{ minWidth: 'min(28rem, calc(100vw - 2rem))' }" :header="t('memory.tracks.editTitle')" @update:visible="emit('close')">
    <form class="veyq-form" data-testid="track-edit" @submit.prevent="save">
      <label>
        {{ t('memory.tracks.nameLabel') }}
        <VeyqInput v-model="name" fluid data-testid="track-edit-name" :placeholder="fallbackName ?? ''" :disabled="saving" />
      </label>
      <p class="track-edit-hint">{{ t('memory.tracks.nameHint') }}</p>
      <label>
        {{ t('memory.tracks.activityLabel') }}
        <VeyqSelect v-model="activity" :options="options" :disabled="saving" data-testid="track-edit-activity" />
      </label>
      <VeyqMessage v-if="error" tone="error">{{ t(error === 'conflict' ? 'memory.tracks.editConflict' : 'memory.tracks.editFailed') }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="saving" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.save')" icon="check" :loading="saving" data-testid="track-edit-save" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.track-edit-hint {
  margin: -0.5rem 0 0;
  color: var(--app-muted);
  font-size: 0.875rem;
}
</style>
