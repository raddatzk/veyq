<script setup lang="ts">
// An opened Pot (ADR 0108, docs/apps/expenses.md): core's `NodeHeader` with the Pot's share, rename, move and delete
// (ADR 0110's priority), the ledger notice when the check finds a revision missing, and the tabs Entries / Balances /
// Overview / People — and Settings for whoever may change the Pot's meta. Every control is `../rights.ts`' answer for the
// viewer: a member and a share guest of a `READ` or `READ_WRITE` link get this very page with functions removed (inbox
// #401) — a guest sees no Settings, manages no people beyond adding and renaming, and reads no writer names (its read has
// none, decision 3). Core's page of a link mounts this `detail` at the link's root and below it.
//
// The Pot's revisions live beside the tree: `../potSession.ts` reads them, keeps them live and writes them.
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqTabs from '../../../shared/ui/VeyqTabs.vue'
import VeyqTabList from '../../../shared/ui/VeyqTabList.vue'
import VeyqTab from '../../../shared/ui/VeyqTab.vue'
import VeyqTabPanels from '../../../shared/ui/VeyqTabPanels.vue'
import VeyqTabPanel from '../../../shared/ui/VeyqTabPanel.vue'
import type { NodeDetailEmits, NodeDetailProps } from '../../../core/applications/registry'
import NodeHeader from '../../../core/node/components/NodeHeader.vue'
import { standardNodeActions } from '../../../core/node/nodeHeader'
import { favoriteHeaderActions } from '../../../core/favorites/favoriteActions'
import { downloadHeaderActions } from '../../../core/export/downloadActions'
import { copyHeaderActions } from '../../../core/export/copyActions'
import { canReport, nodeTarget, reportHeaderActions, reportNodes } from '../../../core/report/reportActions'
import { useReportOffered } from '../../../core/report/report'
import { loadScopePeople, loadTenantPeople, type Person } from '../../../core/people/directory'
import { useToast } from '../../../shared/state/useToast'
import { account } from '../../../app/account'
import { targetOf } from '../../../core/access'
import { expensesRights } from '../rights'
import { usePotSession } from '../potSession'
import { personLabel, type MemberDirectory } from '../people'
import { reportEntry, reportReceipt } from '../report'
import { potReceiptSource, type HeldReceipt } from '../receipts'
import EntriesPanel from './EntriesPanel.vue'
import EntryEditor from './EntryEditor.vue'
import BalancesPanel from './BalancesPanel.vue'
import OverviewPanel from './OverviewPanel.vue'
import PeoplePanel from './PeoplePanel.vue'
import SettingsPanel from './SettingsPanel.vue'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()
const { t } = useI18n()
const toast = useToast()

const rights = computed(() => expensesRights(props.access, targetOf(props.tree, props.node)))
const session = usePotSession({
  access: () => props.access,
  scopeId: () => props.scopeId,
  node: () => props.node,
  potKey: () => props.contentKey,
  epochs: () => props.epochs,
  tree: () => props.tree,
})
const { meta, ledger, check, loaded, failed } = session

// A member's history names each writer; the people come from the tenant's directory (a guest's page reads none).
const people = shallowRef<ReadonlyMap<string, Person> | null>(null)
// The space's current members (ADR 0108 decision 2): a member participant reads by their current name and picture, one the
// list no longer holds as a former member, and the People tab offers the others to add. A member's read only: a guest's
// page reads no member list, so it is handed none and shows each participant by the name sealed at adding.
const members = shallowRef<Person[] | null>(null)
const directory = computed<MemberDirectory>(() => (members.value ? new Map(members.value.map((m) => [m.userId, m] as const)) : null))
/** Who looks, as a member of the space: "Who am I" defaults to their own member participant. A link guest has no member
 *  identity (even where an account is signed in on the same browser), so none. */
const selfId = computed(() => (props.access.mode === 'member' && account.userId ? account.userId : null))
onMounted(() => {
  void session.refresh().catch(() => {})
  if (props.access.mode === 'member') {
    void loadTenantPeople().then((p) => (people.value = p)).catch(() => {})
    void loadScopePeople(props.scopeId).then((m) => (members.value = m)).catch(() => {})
  }
})

const reportOn = useReportOffered()
const coreActions = computed(() => [
  ...favoriteHeaderActions(t, toast.error, props.access, props.node),
  ...copyHeaderActions(t, props.access, props.node, () => emit('copy')),
  ...downloadHeaderActions(t, props.access, props.node, () => emit('download')),
  ...reportHeaderActions(t, reportOn.value, props.access, props.node, () => reportNodes(props.access, props.tree, [props.node], null)),
])
/** The Pot header's priority (ADR 0110): favorite, sharing (a Pot is kept together), its download, then rename, move, copy;
 *  delete and report are the ⋯ menu's last entries. */
const POT_HEADER_ORDER = ['favorite', 'share', 'download', 'rename', 'move', 'copy'] as const
const headerActions = computed(() =>
  standardNodeActions(
    t,
    { ...rights.value, deleteLabel: t('expenses.delete') },
    { share: () => emit('share', { contribute: rights.value.shareContribute }), rename: () => emit('rename'), move: () => emit('move', props.node), remove: () => emit('delete') },
  ),
)
const subtitle = computed(() => (meta.value ? t('expenses.inCurrency', { currency: meta.value.base.currency }) : undefined))

const tab = ref('entries')
const editing = ref<{ entryId: string | null } | null>(null)
const reportsEntries = computed(() => reportOn.value && canReport(props.access) && !!props.contentKey)

/** What the ledger notice lists: each touched record by its title or name, else as an entry this Pot cannot show. */
const missingEntries = computed(() =>
  check.value.entries.map((id) => {
    const l = ledger.value
    const e = l?.entries.find((x) => x.id === id)
    if (e?.content) return e.content.kind === 'transfer' ? (e.content.title ?? t('expenses.kind.transfer')) : e.content.title
    if (l?.participants.some((p) => p.id === id)) return personLabel(l, id, t, directory.value)
    return t('expenses.ledger.unknownEntry')
  }),
)

/** Report an entry from its dialog, which stays open beneath core's report dialog. */
function onReport(entryId: string) {
  if (!props.contentKey) return
  reportEntry(props.access, props.node.id, props.contentKey, session.held.value, session.receipts.value, entryId, nodeTarget(props.access, props.tree, props.node))
}
/** Report one receipt on its own, from the entry dialog, as for an entry. */
function onReportReceipt(receipt: HeldReceipt) {
  if (!props.contentKey) return
  reportReceipt(props.access, props.node.id, props.contentKey, receipt)
}
/** A listed receipt as the entry dialog opens it, for whoever looks (a member's scope read, a guest's link). */
function receiptSource(receipt: HeldReceipt) {
  return potReceiptSource(props.access, props.scopeId, props.node.id, props.contentKey!, receipt)
}
</script>

<template>
  <div class="veyq-level-view expenses-pot">
    <NodeHeader icon="wallet" :title="node.name" :subtitle="subtitle" :actions="headerActions" :core-actions="coreActions" :order="POT_HEADER_ORDER" />

    <VeyqMessage v-if="loaded && !check.complete" tone="warn" class="expenses-ledger-notice">
      {{ t('expenses.ledger.missing') }}
      <ul>
        <li v-for="(name, i) in missingEntries" :key="i">{{ name }}</li>
      </ul>
    </VeyqMessage>
    <VeyqMessage v-if="failed" tone="error">{{ t('expenses.loadFailed') }}</VeyqMessage>
    <p v-else-if="!loaded" class="veyq-loading">{{ t('expenses.loading') }}</p>
    <template v-else>
      <VeyqMessage v-if="!meta" tone="warn">{{ rights.settings ? t('expenses.settings.missing') : t('expenses.settings.missingReadOnly') }}</VeyqMessage>
      <VeyqTabs v-model:value="tab">
        <VeyqTabList :aria-label="node.name">
          <VeyqTab value="entries">{{ t('expenses.tabs.entries') }}</VeyqTab>
          <VeyqTab value="balances">{{ t('expenses.tabs.balances') }}</VeyqTab>
          <VeyqTab value="overview">{{ t('expenses.tabs.overview') }}</VeyqTab>
          <VeyqTab value="people">{{ t('expenses.tabs.people') }}</VeyqTab>
          <VeyqTab v-if="rights.settings" value="settings">{{ t('expenses.tabs.settings') }}</VeyqTab>
        </VeyqTabList>
        <VeyqTabPanels>
          <template v-if="meta && ledger">
            <VeyqTabPanel value="entries">
              <EntriesPanel :ledger="ledger" :meta="meta" :can-write="rights.writeEntries" :directory="directory" @open="(entryId) => (editing = { entryId })" />
            </VeyqTabPanel>
            <VeyqTabPanel value="balances">
              <BalancesPanel :session="session" :ledger="ledger" :meta="meta" :can-write="rights.writeEntries" :pot-id="node.id" :directory="directory" :self-id="selfId" />
            </VeyqTabPanel>
            <VeyqTabPanel value="overview">
              <OverviewPanel :ledger="ledger" :meta="meta" :pot-id="node.id" :directory="directory" :self-id="selfId" />
            </VeyqTabPanel>
            <VeyqTabPanel value="people">
              <PeoplePanel
                :session="session"
                :ledger="ledger"
                :meta="meta"
                :add-people="rights.addPeople"
                :add-members="rights.addMembers"
                :manage-people="rights.managePeople"
                :members="rights.addMembers ? members : null"
                :directory="directory"
              />
            </VeyqTabPanel>
          </template>
          <VeyqTabPanel v-if="rights.settings" value="settings">
            <SettingsPanel :session="session" :meta="meta" :ledger="ledger" />
          </VeyqTabPanel>
        </VeyqTabPanels>
      </VeyqTabs>
    </template>

    <EntryEditor
      v-if="editing && meta && ledger"
      :session="session"
      :ledger="ledger"
      :meta="meta"
      :entry-id="editing.entryId"
      :can-write="rights.writeEntries"
      :writers="rights.writers"
      :people="people"
      :directory="directory"
      :on-report="reportsEntries ? onReport : undefined"
      :receipt-source="receiptSource"
      :on-report-receipt="reportsEntries ? onReportReceipt : undefined"
      @close="editing = null"
    />
  </div>
</template>
