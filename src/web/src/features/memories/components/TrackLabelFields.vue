<script setup lang="ts">
// A track's two label fields (ADR 0130 decision 9): its name and its activity, as the Edit dialog and the add's dialog both
// ask them - one copy, so both offer the same activities in the same order and hand on the same values. Presentation only:
// the fields are `v-model:name` (the text as typed) and `v-model:activity` (an activity, or `TRACK_NO_ACTIVITY`);
// `labelFrom` turns them into the label that is sealed.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect, { type VeyqSelectOption } from '../../../shared/ui/VeyqSelect.vue'
import { TRACK_ACTIVITIES, TRACK_NO_ACTIVITY, type TrackActivityChoice } from '../track/trackLabel'

const props = defineProps<{
  /** What the track is named without a name of its own: its file's track name, else its file name. */
  fallbackName: string | null
  disabled?: boolean
  /** Say under the name that an empty one names the track by its file. */
  hint?: boolean
  /** The prefix of the two fields' test ids (`<prefix>-name`, `<prefix>-activity`). */
  testId: string
}>()
const name = defineModel<string>('name', { required: true })
const activity = defineModel<TrackActivityChoice>('activity', { required: true })
const { t } = useI18n()

const options = computed<VeyqSelectOption<TrackActivityChoice>[]>(() => [
  { label: t('memory.tracks.activity.none'), value: TRACK_NO_ACTIVITY },
  ...TRACK_ACTIVITIES.map((a) => ({ label: t(`memory.tracks.activity.${a}`), value: a })),
])
</script>

<template>
  <label>
    {{ t('memory.tracks.nameLabel') }}
    <VeyqInput v-model="name" fluid :data-testid="`${props.testId}-name`" :placeholder="fallbackName ?? ''" :disabled="disabled" />
  </label>
  <p v-if="hint" class="track-label-hint">{{ t('memory.tracks.nameHint') }}</p>
  <label>
    {{ t('memory.tracks.activityLabel') }}
    <VeyqSelect v-model="activity" :options="options" :disabled="disabled" :data-testid="`${props.testId}-activity`" />
  </label>
</template>

<style scoped>
.track-label-hint {
  margin: -0.5rem 0 0;
  color: var(--app-muted);
  font-size: 0.875rem;
}
</style>
