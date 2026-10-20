<script setup lang="ts">
// The shared name + picture fields of the member's OWN profile (ADR 0084 decision 6): first and last name, and the picture with
// its choose / change / remove control. Presentation over one {@link useNameAndPicture} model - the host (Settings >
// Your profile, or the member's own profile page) owns the Save. Never mounted for another member's profile: rights only
// remove functions, there is no second UI.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import ProfilePictureDialog from './ProfilePictureDialog.vue'
import type { NameAndPicture } from './useNameAndPicture'

const props = defineProps<{ model: NameAndPicture; displayName: string }>()
const { t } = useI18n()
const m = computed(() => props.model)
</script>

<template>
  <div class="name-picture" data-testid="name-picture-fields">
    <div class="picture-row">
      <PersonAvatar :name="displayName" :image="m.pictureUrl" size="xlarge" />
      <div class="picture-actions">
        <span class="picture-label">{{ t('profilePicture.label') }}</span>
        <div class="picture-buttons">
          <Button
            type="button"
            :label="m.hasPicture ? t('profilePicture.change') : t('profilePicture.choose')"
            icon="pi pi-image"
            severity="secondary"
            outlined
            size="small"
            :disabled="!m.hasKey"
            data-testid="profile-picture-open"
            @click="m.dialogOpen = true"
          />
          <Button
            v-if="m.hasPicture"
            type="button"
            :label="t('profilePicture.remove')"
            icon="pi pi-trash"
            severity="danger"
            text
            size="small"
            :disabled="!m.hasKey"
            data-testid="profile-picture-remove-inline"
            @click="m.remove()"
          />
        </div>
        <small v-if="!m.hasKey" class="field-hint">{{ t('profilePicture.noKeyYet') }}</small>
        <small v-else-if="m.draftKind === 'set'" class="field-hint" role="status">{{ t('profilePicture.pendingSet') }}</small>
        <small v-else-if="m.draftKind === 'clear'" class="field-hint" role="status">{{ t('profilePicture.pendingClear') }}</small>
        <small v-else class="field-hint">{{ t('profilePicture.hint') }}</small>
      </div>
    </div>

    <div class="name-row">
      <label>{{ t('profile.firstName') }}<InputText v-model="m.firstName" autocomplete="given-name" :disabled="!m.hasKey" /></label>
      <label>{{ t('profile.lastName') }}<InputText v-model="m.lastName" autocomplete="family-name" :disabled="!m.hasKey" /></label>
    </div>
    <small v-if="!m.hasKey" class="field-hint">{{ t('profile.noNameYet') }}</small>

    <ProfilePictureDialog v-if="m.dialogOpen" :has-picture="m.hasPicture" @use="m.choose($event)" @remove="m.remove()" @close="m.dialogOpen = false" />
  </div>
</template>

<style scoped>
.name-picture {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
}
.picture-row {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.picture-actions {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  min-width: 0;
}
.picture-label {
  font-weight: 600;
}
.picture-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}
</style>
