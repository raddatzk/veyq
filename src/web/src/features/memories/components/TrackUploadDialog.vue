<script setup lang="ts">
// The add's dialog (uc.memory.tracks, ADR 0130 decision 9): once the picked GPX files passed the device's checks, each is
// named and given an activity before anything is uploaded - the Edit's two fields, one group per file, the file's own
// track name as the name's placeholder. Both are optional: Add uploads with what is filled in, sealed into each track's
// listed tier at birth; Cancel (or closing) uploads nothing. Presentation only: the Map tab hands the files in and sends
// the labelled files on.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import TrackLabelFields from './TrackLabelFields.vue'
import { labelFrom, TRACK_NO_ACTIVITY, type TrackActivityChoice, type TrackLabel } from '../track/trackLabel'

const props = defineProps<{
  /** The checked files, each with what it is named without a name of its own (its file's track name, else its file name). */
  files: readonly { file: File; fallbackName: string | null }[]
}>()
const emit = defineEmits<{ add: [labels: TrackLabel[]]; close: [] }>()
const { t } = useI18n()

const fields = ref(props.files.map(() => ({ name: '', activity: TRACK_NO_ACTIVITY as TrackActivityChoice })))

function add() {
  emit('add', fields.value.map((f) => labelFrom(f.name, f.activity)))
}
</script>

<template>
  <VeyqDialog
    :visible="true"
    :style="{ minWidth: 'min(28rem, calc(100vw - 2rem))' }"
    :header="t('memory.tracks.addTitle', files.length)"
    @update:visible="emit('close')"
  >
    <form class="veyq-form" data-testid="track-upload" @submit.prevent="add">
      <p class="track-upload-hint">{{ t('memory.tracks.addHint') }}</p>
      <fieldset v-for="(entry, index) in files" :key="index" class="track-upload-file" data-testid="track-upload-file">
        <legend class="track-upload-file-name">{{ entry.file.name }}</legend>
        <TrackLabelFields
          v-model:name="fields[index].name"
          v-model:activity="fields[index].activity"
          :fallback-name="entry.fallbackName"
          :test-id="`track-upload-${index}`"
        />
      </fieldset>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" data-testid="track-upload-cancel" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('memory.tracks.addConfirm', files.length)" icon="upload" data-testid="track-upload-add" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.track-upload-hint {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.875rem;
}
.track-upload-file {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin: 0;
  padding: 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
  min-width: 0;
}
.track-upload-file-name {
  padding: 0 0.25rem;
  font-weight: 600;
  overflow-wrap: anywhere;
}
</style>
