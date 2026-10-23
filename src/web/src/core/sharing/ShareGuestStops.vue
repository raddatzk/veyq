<script setup lang="ts">
// A share row's "guests cannot open this link, because …" (inbox #457): the server's stops (`guestStops.ts`) as a
// warning badge, one line per reason and the fix the hosting dialog already has. Every share list draws it — Memories'
// `ShareDialog`, core's `FolderShareDialog` (folders and files), Depot's drop box — so the three cannot word it apart.
// A span laid out as a flex row, so a row whose main part is a span (the drop box's) may hold it too. The host says
// which fixes it offers for the row (`canEdit`, `canRenew`); a fix it lacks is not shown.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import type { ShareGuestStop } from '../../api/generated/sharing'
import { guestStopLines, type GuestStopFix, type GuestStopLine } from './guestStops'
import StatementNotice from '../../features/moderation/statement/StatementNotice.vue'
import { ShareGuestStopReasonEnum } from '../../api/generated/sharing'

/** A link a moderator blocked: its creator reads the statement of reasons below the stop (uc.moderation.object-to-a-measure). */
const operatorBlocked = computed(() => props.stops.some((s) => s.reason === ShareGuestStopReasonEnum.OperatorBlocked))

const props = defineProps<{ stops: ShareGuestStop[]; createdAt: Date; canEdit: boolean; canRenew: boolean; shareId?: string }>()
const emit = defineEmits<{ edit: []; renew: [] }>()
const { t } = useI18n()

const lines = computed(() => guestStopLines(props.stops, props.createdAt))

function text(line: GuestStopLine): string {
  return t(line.key, { what: line.entryKey ? t(line.entryKey) : '', days: line.days ?? 0 })
}

/** One button per fix the host offers: named for its reason when one reason asks for it, else plainly. */
const fixes = computed(() => {
  const offered = (fix: GuestStopFix | null) => (fix === 'edit' && props.canEdit) || (fix === 'renew' && props.canRenew)
  const byFix = new Map<GuestStopFix, GuestStopLine[]>()
  for (const line of lines.value) {
    if (line.fix && offered(line.fix)) byFix.set(line.fix, [...(byFix.get(line.fix) ?? []), line])
  }
  return [...byFix].map(([fix, ls]) => ({
    fix,
    label: ls.length === 1 ? t(ls[0]!.fixKey!) : t('share.guestStops.editFix'),
  }))
})
</script>

<template>
  <span v-if="lines.length" class="share-guest-stops">
    <span class="veyq-badge warning share-guest-stops-badge">{{ t('share.guestStops.badge') }}</span>
    <span v-for="(line, i) in lines" :key="i" class="share-guest-stop">{{ text(line) }}</span>
    <VeyqButton
      v-for="f in fixes"
      :key="f.fix"
      :class="['share-guest-stops-fix', `share-guest-stops-${f.fix}`]"
      :label="f.label"
      variant="secondary"
      appearance="text"
      size="small"
      @click="f.fix === 'edit' ? emit('edit') : emit('renew')"
    />
    <StatementNotice v-if="shareId && operatorBlocked" class="share-guest-stops-statement" :share-id="shareId" />
  </span>
</template>

<style scoped>
.share-guest-stops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  margin-top: 0.25rem;
  font-size: 0.875rem;
}
</style>
