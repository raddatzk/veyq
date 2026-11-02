<script setup lang="ts">
// The Pot's `createDialog` (uc.expenses.create-pot, ADR 0108 decision 2): a name, the base currency — sealed into the
// node's meta at creation, fixed once the first entry exists — and its first people, appended as one batch after the
// node is created (ADR 0108 decision 1). Only a member creates a Pot (`../rights.ts`' `createPot`; Expenses declares no
// guest-creatable kind), so the people are the member's view of People, in ONE field (the layer's tags input): typing
// suggests the space's members whose name holds the text, by the same rules as People's picker (`../people.ts`'
// `addableMembers` — a member once at most — and `participantRecord`: the member's id with their display name sealed at
// adding), and last the text itself, added as a name (a name at most once, case aside). Each person is a chip until
// the Pot is created, and a chip can be taken out again. A failed read of the members leaves names only. A name that
// collides with a sibling is refused before sending (core's `siblingCollision.ts`).
import { computed, onMounted, ref, shallowRef, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqTagsInput from '../../../shared/ui/VeyqTagsInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { NodeCreateDialogEmits, NodeCreateDialogProps } from '../../../core/applications/registry'
import { memberCreateOptions } from '../../../core/node/rereadNode'
import { treeStore } from '../../../core/node/treeStore'
import { collidingSibling, nameTakenMessage } from '../../../core/node/siblingCollision'
import { httpStatus, HttpStatus } from '../../../shared/httpError'
import { knownCurrencies, minorUnitsOf } from '../amounts'
import { createPot, PotPeopleNotAdded } from '../createPot'
import { addableMembers, membersMatching, sameName, type NewParticipant } from '../people'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import { loadScopePeople, type Person } from '../../../core/people/directory'
import { POT_KIND } from '../kinds'

const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t, locale } = useI18n()

/** The reader's own currency as a first guess: the euro for German, else the dollar. */
const name = ref('')
const currency = ref(locale.value === 'de' ? 'EUR' : 'USD')
/** The people in the order added (their join order): a name typed, or a member chosen — what is created. */
const people = ref<NewParticipant[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const currencyOptions = computed(() => knownCurrencies().map((c) => ({ value: c, label: c })))
const canSubmit = computed(() => !loading.value && name.value.trim().length > 0 && !!currency.value && people.value.every((p) => !('name' in p) || p.name.length <= 100))

/** The space's current members, read as People reads them; none until (and unless) the read answers. */
const members = shallowRef<Person[] | null>(null)
onMounted(() => {
  void loadScopePeople(props.scopeId).then((m) => (members.value = m)).catch(() => {})
})
/** The members still to choose: the space's, minus those already in the list. */
const addable = computed(() =>
  members.value ? addableMembers({ participants: people.value.flatMap((p) => ('member' in p ? [{ erased: false, member: p.member.userId }] : [])) }, members.value) : [],
)

/** A chip's key: a member by their id, a name by its letters with case aside — so a name is in the field once. */
const keyOf = (p: NewParticipant) => ('member' in p ? `member:${p.member.userId}` : `name:${p.name.toLocaleLowerCase()}`)
const labelOf = (p: NewParticipant) => ('member' in p ? p.member.displayName : p.name)
type Choice = { key: string; label: string; person: NewParticipant }
const peopleId = useId()
const query = ref('')
const tags = computed(() => people.value.map((p) => ({ key: keyOf(p), label: labelOf(p), removeLabel: t('expenses.people.remove', { name: labelOf(p) }), person: p })))
/** What the field suggests for the text typed: the addable members whose name holds it, then the text as a name —
 *  unless that name is in already. Blank text is never a name. */
const suggestions = computed<Choice[]>(() => {
  const typed = query.value.trim()
  const offered: Choice[] = membersMatching(addable.value, typed).map((m) => ({ key: keyOf({ member: m }), label: m.displayName, person: { member: m } }))
  const asName: NewParticipant = { name: typed }
  if (typed && !people.value.some((p) => keyOf(p) === keyOf(asName))) offered.push({ key: keyOf(asName), label: t('expenses.people.addAsName', { name: typed }), person: asName })
  return offered
})
function add(choice: Choice) {
  if (!people.value.some((p) => keyOf(p) === choice.key)) people.value.push(choice.person)
}
/** Create takes the text still in the field too (owner 2026-11-01): a member whose whole name it is - case and accents
 *  aside, and only one - else the text as a name. Nothing for blank text or a name already in. */
function takeTyped() {
  const typed = query.value.trim()
  if (!typed) return
  const whole = membersMatching(addable.value, typed).filter((m) => sameName(m.displayName, typed))
  const person: NewParticipant = whole.length === 1 ? { member: whole[0]! } : { name: typed }
  if (!people.value.some((p) => keyOf(p) === keyOf(person))) people.value.push(person)
  query.value = ''
}
function remove(tag: { key: string }) {
  people.value = people.value.filter((p) => keyOf(p) !== tag.key)
}

async function submit() {
  if (loading.value) return
  takeTyped()
  if (!canSubmit.value) return
  error.value = null
  const sibling = collidingSibling(treeStore.nodes, props.parentId ?? null, POT_KIND, name.value.trim())
  if (sibling) {
    error.value = nameTakenMessage(t, sibling)
    return
  }
  loading.value = true
  try {
    const parent = props.parentId && props.parentKey && props.parentEpoch != null && props.parentScopeEpoch != null
      ? { id: props.parentId, key: props.parentKey, epoch: props.parentEpoch, scopeEpoch: props.parentScopeEpoch }
      : null
    await createPot(props.scopeId, props.epochs, parent, name.value.trim(), { currency: currency.value, minorUnits: minorUnitsOf(currency.value) }, people.value, memberCreateOptions(props.scopeId, parent))
    emit('done')
    emit('close')
  } catch (e) {
    if (e instanceof PotPeopleNotAdded) {
      // The Pot exists; its people can be added on its page.
      emit('done')
      error.value = t('expenses.create.peopleFailed')
    } else error.value = httpStatus(e) === HttpStatus.conflict ? t('node.slugTaken') : t('expenses.create.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('expenses.create.title')" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('node.nameLabel') }}
        <VeyqInput v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>
      <label>
        {{ t('expenses.baseCurrency') }}
        <VeyqSelect v-model="currency" :options="currencyOptions" :disabled="loading" />
        <span class="veyq-form-copy">{{ t('expenses.create.currencyHint') }}</span>
      </label>
      <!-- not a wrapping <label>: a label activates its first button, and a chip's × is one -->
      <div class="veyq-form-field expenses-create-people">
        <label :for="peopleId">{{ t('expenses.tabs.people') }}</label>
        <VeyqTagsInput
          v-model:query="query"
          :input-id="peopleId"
          :tags="tags"
          :options="suggestions"
          :placeholder="members?.length ? t('expenses.people.memberOrName') : t('expenses.people.nameLabel')"
          maxlength="100"
          :disabled="loading"
          @add="add"
          @remove="remove"
        >
          <template #tag="{ tag }">
            <PersonAvatar v-if="'member' in tag.person" :name="tag.person.member.displayName" :user-id="tag.person.member.userId" />
          </template>
          <template #option="{ option }">
            <PersonAvatar v-if="'member' in option.person" :name="option.person.member.displayName" :user-id="option.person.member.userId" />
            <span>{{ option.label }}</span>
          </template>
        </VeyqTagsInput>
      </div>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.create')" icon="check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </VeyqDialog>
</template>
