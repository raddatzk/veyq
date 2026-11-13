<script setup lang="ts">
// The Background music dialog (uc.memory.music main flow 1, ADR 0131 decision 1): one field per service, filled with the
// stored playlist's link; a member pastes a playlist's share link. A link that is not a playlist of that service is
// refused here (E1) and nothing is saved; an emptied field drops that service's playlist (A5). Presentation only: the
// page seals the list under the node's key.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { parsePlaylistLink, type MusicService, type Playlist } from './musicModel'
import { playlistLink } from '../../../music-frame/protocol'

const props = defineProps<{ music: readonly Playlist[] }>()
const emit = defineEmits<{ save: [Playlist[]]; close: [] }>()
const { t } = useI18n()

const stored = (service: MusicService) => props.music.find((p) => p.service === service)
const spotify = ref(stored('spotify') ? playlistLink(stored('spotify')!) : '')
const apple = ref(stored('apple-music') ? playlistLink(stored('apple-music')!) : '')

/** A field's playlist: undefined while empty, null while it names none of its service. */
function read(text: string, service: MusicService): Playlist | null | undefined {
  if (!text.trim()) return undefined
  const p = parsePlaylistLink(text)
  return p && p.service === service ? p : null
}
const spotifyRead = computed(() => read(spotify.value, 'spotify'))
const appleRead = computed(() => read(apple.value, 'apple-music'))
const canSave = computed(() => spotifyRead.value !== null && appleRead.value !== null)

function save() {
  if (!canSave.value) return
  emit('save', [spotifyRead.value, appleRead.value].filter((p): p is Playlist => !!p))
  emit('close')
}
</script>

<template>
  <VeyqDialog :visible="true" :style="{ minWidth: 'min(28rem, calc(100vw - 2rem))' }" :header="t('memory.music.title')" @update:visible="emit('close')">
    <form class="veyq-form memory-music-dialog" @submit.prevent="save">
      <p class="memory-music-dialog__hint">{{ t('memory.music.dialogHint') }}</p>
      <label>
        {{ t('memory.music.spotifyLabel') }}
        <VeyqInput
          v-model="spotify" fluid type="url" data-testid="music-spotify" :placeholder="t('memory.music.spotifyPlaceholder')"
          :invalid="spotifyRead === null"
        />
      </label>
      <VeyqMessage v-if="spotifyRead === null" tone="error">{{ t('memory.music.notAPlaylist') }}</VeyqMessage>
      <label>
        {{ t('memory.music.appleLabel') }}
        <VeyqInput
          v-model="apple" fluid type="url" data-testid="music-apple" :placeholder="t('memory.music.applePlaceholder')"
          :invalid="appleRead === null"
        />
      </label>
      <VeyqMessage v-if="appleRead === null" tone="error">{{ t('memory.music.notAPlaylist') }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('memory.music.save')" icon="check" :disabled="!canSave" data-testid="music-save" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.memory-music-dialog__hint {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
</style>
