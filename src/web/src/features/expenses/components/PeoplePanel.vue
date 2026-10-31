<script setup lang="ts">
// A Pot's people (uc.expenses.manage-participants, docs/apps/expenses.md § The Pot, ADR 0108 decision 6): whoever may
// write adds and renames one; a member deactivates or activates one, and deletes a settled one (its name erased, entries
// show "deleted people") — or removes one no revision names, which then appears nowhere. Every erased person is
// summarised in one line. What is offered is `../rights.ts`' answer; the settled rule is the client's, asked again by the
// write path (`sealPotWrites`, `eraseParticipant`) before anything is sealed.
//
// A participant is a member of the space or a name (ADR 0108 decision 2). A member's view adds either: a name typed, or
// one of the space's members ([members], the ones not yet in the Pot), whose display name is sealed with their id. A link
// guest's view is this panel without the member choice (`addMembers` false, no [members]). A member participant is drawn
// with their current name and picture where the viewer holds the space's directory, as a former member by the sealed
// name once it no longer lists them, and by the sealed name with the letter alone for a guest — never their picture. It
// is never renamed (its name is the member's own) and never changes kind: every later revision carries its member.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import type { Person } from '../../../core/people/directory'
import { addableMembers, shownName, standingOf, type MemberDirectory } from '../people'
import type { ParticipantBody } from '../domain/format'
import { useConfirm } from '../../../shared/state/useConfirm'
import { isMoney } from '../domain/format'
import { isSettled, namedParticipants, type Participant, type PotLedger } from '../domain/ledger'
import type { PotMeta } from '../domain/potMeta'
import type { PotSession } from '../potSession'
import { formatMoney } from '../amounts'

const props = defineProps<{
  session: PotSession
  ledger: PotLedger
  meta: PotMeta
  addPeople: boolean
  managePeople: boolean
  /** Add a member of the space, beside a name (a member's view; `../rights.ts`' `addMembers`). */
  addMembers?: boolean
  /** The space's current members to choose from: a member's read, none for a guest. */
  members?: readonly Person[] | null
  /** The space's members by user id, for the current names and pictures; none for a guest. */
  directory?: MemberDirectory
}>()
const { t, locale } = useI18n()
const { ask } = useConfirm()

const shown = computed(() => props.ledger.participants.filter((p) => !p.erased))
/** Every person any revision the Pot holds names, live or deleted: one of them is deleted, never removed. */
const named = computed(() => {
  const ids = new Set<string>()
  for (const r of props.session.held.value) if (r.body && isMoney(r.body)) for (const id of namedParticipants(r.body)) ids.add(id)
  return ids
})
/** The deleted people, as the entries still show them: an erased person no revision names was removed and appears
 *  nowhere, so the summary does not count them either (uc.expenses.manage-participants). */
const erasedCount = computed(() => props.ledger.participants.filter((p) => p.erased && named.value.has(p.id)).length)
const balance = (id: string) => props.ledger.balances.get(id) ?? 0n
const money = (v: bigint) => formatMoney(v, props.meta.base.currency, props.meta.base.minorUnits, locale.value)

const dir = computed<MemberDirectory>(() => props.directory ?? null)
const nameOf = (p: Participant) => shownName(p, dir.value) ?? ''
/** The members a writer may still add: the space's, minus those already in the Pot. */
const addable = computed(() => (props.addMembers && props.members ? addableMembers(props.ledger, props.members) : []))
const memberOptions = computed(() => addable.value.map((m) => ({ value: m.userId, label: m.displayName })))
const newMember = ref<string | null>(null)

/** A participant's next revision, carrying what never changes: its join order and, for a member, the member. */
const revisionOf = (p: Participant, name: string, active: boolean): ParticipantBody =>
  p.member !== null ? { kind: 'participant', name, active, joined: p.joined, member: { userId: p.member } } : { kind: 'participant', name, active, joined: p.joined }

const newName = ref('')
const editing = ref<string | null>(null)
const editName = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

async function run(write: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await write()
    return true
  } catch (e) {
    if (!(e instanceof Error && e.name === 'GuestDeclinedError')) error.value = t('expenses.people.failed')
    return false
  } finally {
    busy.value = false
  }
}

async function add() {
  const name = newName.value.trim()
  if (!name || name.length > 100) return
  const ok = await run(() => props.session.write([{ entryId: crypto.randomUUID(), content: { kind: 'participant', name, active: true, joined: Date.now() } }]))
  if (ok) newName.value = ''
}
async function addMember() {
  const m = addable.value.find((x) => x.userId === newMember.value)
  if (!m) return
  // The display name as it is now, sealed with the id: what a reader without the directory (a link guest) shows.
  const name = [...m.displayName].slice(0, 100).join('')
  const ok = await run(() =>
    props.session.write([{ entryId: crypto.randomUUID(), content: { kind: 'participant', name, active: true, joined: Date.now(), member: { userId: m.userId } } }]),
  )
  if (ok) newMember.value = null
}
function startRename(p: Participant) {
  editing.value = p.id
  editName.value = p.name ?? ''
}
async function rename(p: Participant) {
  const name = editName.value.trim()
  if (!name || name.length > 100) return
  const ok = await run(() => props.session.write([{ entryId: p.id, content: revisionOf(p, name, p.active) }]))
  if (ok) editing.value = null
}
function setActive(p: Participant, active: boolean) {
  void run(() => props.session.write([{ entryId: p.id, content: revisionOf(p, p.name ?? '', active) }]))
}
async function erase(p: Participant) {
  const remove = !named.value.has(p.id)
  const accepted = await ask({
    header: t(remove ? 'expenses.people.remove' : 'expenses.people.delete', { name: nameOf(p) }),
    message: t(remove ? 'expenses.people.removeConfirm' : 'expenses.people.deleteConfirm'),
    acceptLabel: t(remove ? 'expenses.people.removeAccept' : 'expenses.people.deleteAccept'),
    acceptIcon: 'trash',
  })
  if (accepted) void run(() => props.session.erase(p.id))
}
</script>

<template>
  <section class="expenses-people">
    <h2>{{ t('expenses.tabs.people') }}</h2>
    <ul class="veyq-list">
      <li v-for="p in shown" :key="p.id" class="veyq-list-row expenses-static-row" :data-person="p.id">
        <span v-if="editing === p.id" class="expenses-inline-form">
          <VeyqInput v-model="editName" autocomplete="off" :aria-label="t('expenses.people.nameLabel')" @keydown.enter.prevent="rename(p)" />
          <VeyqButton :label="t('common.save')" size="small" :loading="busy" @click="rename(p)" />
          <VeyqButton :label="t('common.cancel')" variant="secondary" appearance="outlined" size="small" :disabled="busy" @click="editing = null" />
        </span>
        <template v-else>
          <!-- a listed member's picture; the letter for a name, a former member and on a guest's page (no user id handed) -->
          <PersonAvatar :name="nameOf(p)" :user-id="standingOf(p, dir) === 'member' ? p.member : null" />
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ nameOf(p) }}</span>
            <span class="veyq-row-subtitle">
              {{ isSettled(ledger, p.id) ? t('expenses.balances.settled') : money(balance(p.id)) }}
              <span v-if="!p.active" class="veyq-badge muted">{{ t('expenses.people.inactive') }}</span>
              <span v-if="standingOf(p, dir) === 'former'" class="veyq-badge muted" data-former>{{ t('expenses.people.formerMember') }}</span>
            </span>
          </span>
        </template>
        <span v-if="editing !== p.id" class="veyq-action-group">
          <VeyqButton
            v-if="addPeople && p.member === null"
            class="veyq-icon-button"
            icon="pencil"
            variant="secondary"
            appearance="outlined"
            size="small"
            :aria-label="t('expenses.people.rename', { name: p.name ?? '' })"
            :disabled="busy"
            @click="startRename(p)"
          />
          <template v-if="managePeople && isSettled(ledger, p.id)">
            <VeyqButton
              :label="p.active ? t('expenses.people.deactivate') : t('expenses.people.activate')"
              variant="secondary"
              appearance="outlined"
              size="small"
              :disabled="busy"
              @click="setActive(p, !p.active)"
            />
            <VeyqButton
              class="veyq-icon-button"
              icon="trash"
              variant="danger"
              appearance="outlined"
              size="small"
              :data-action="named.has(p.id) ? 'delete' : 'remove'"
              :aria-label="t(named.has(p.id) ? 'expenses.people.delete' : 'expenses.people.remove', { name: nameOf(p) })"
              :disabled="busy"
              @click="erase(p)"
            />
          </template>
          <VeyqButton v-else-if="managePeople && !p.active" :label="t('expenses.people.activate')" variant="secondary" appearance="outlined" size="small" :disabled="busy" @click="setActive(p, true)" />
        </span>
      </li>
    </ul>
    <p v-if="erasedCount" class="veyq-form-copy">{{ t('expenses.people.deletedSummary', erasedCount) }}</p>
    <p v-if="managePeople && shown.some((p) => !isSettled(ledger, p.id))" class="veyq-form-copy">{{ t('expenses.people.settleFirst') }}</p>
    <form v-if="addPeople" class="expenses-inline-form" @submit.prevent="add">
      <VeyqInput v-model="newName" autocomplete="off" :aria-label="t('expenses.people.nameLabel')" :placeholder="t('expenses.people.nameLabel')" />
      <VeyqButton type="submit" :label="t('expenses.people.add')" icon="user-plus" size="small" :loading="busy" :disabled="!newName.trim()" />
    </form>
    <form v-if="addPeople && addMembers && memberOptions.length" class="expenses-inline-form expenses-add-member" @submit.prevent="addMember">
      <VeyqSelect v-model="newMember" :options="memberOptions" :placeholder="t('expenses.people.chooseMember')" :aria-label="t('expenses.people.chooseMember')" />
      <VeyqButton type="submit" :label="t('expenses.people.addMember')" icon="user-plus" size="small" :loading="busy" :disabled="!newMember" />
    </form>
    <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
  </section>
</template>
