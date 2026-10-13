<script setup lang="ts">
// The OPTIONAL profile picture at join, tenant OWNER path only (ADR 0084 decision 7): the owner cold-births `K_tenant` at accept, so
// the picture is sealed there with the name; a MEMBER holds no key yet and is offered the picture after the first unlock
// instead (`FinishProfilePrompt`), so this is never mounted for a member. It only collects the encoded bytes (the parent
// seals them at accept) and keeps nothing: the chosen bytes live in the parent's ref and the preview URL is revoked on unmount.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import ProfilePictureDialog from './ProfilePictureDialog.vue'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'

const props = defineProps<{ modelValue: Uint8Array | null; name: string }>()
const emit = defineEmits<{ 'update:modelValue': [Uint8Array | null] }>()
const { t } = useI18n()

const open = ref(false)
const preview = useObjectUrl()
const has = computed(() => props.modelValue !== null)

function choose(bytes: Uint8Array) {
  preview.set(new Blob([bytes as BlobPart], { type: 'image/webp' }))
  emit('update:modelValue', bytes)
  open.value = false
}
function remove() {
  preview.clear()
  emit('update:modelValue', null)
  open.value = false
}
</script>

<template>
  <div class="join-picture" data-testid="join-picture">
    <PersonAvatar :name="name || '?'" :image="has ? preview.url.value : null" size="large" />
    <div class="join-picture-text">
      <div class="join-picture-buttons">
        <Button type="button" :label="has ? t('profilePicture.change') : t('profilePicture.choose')" icon="pi pi-image" severity="secondary" outlined size="small" data-testid="join-picture-open" @click="open = true" />
        <Button v-if="has" type="button" :label="t('profilePicture.remove')" icon="pi pi-trash" severity="danger" text size="small" @click="remove" />
      </div>
      <small class="field-hint">{{ t('profilePicture.joinHint') }}</small>
    </div>
    <ProfilePictureDialog v-if="open" :has-picture="has" @use="choose" @remove="remove" @close="open = false" />
  </div>
</template>

<style scoped>
.join-picture {
  display: flex;
  align-items: center;
  gap: 0.9rem;
}
.join-picture-text {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  min-width: 0;
}
.join-picture-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}
</style>
