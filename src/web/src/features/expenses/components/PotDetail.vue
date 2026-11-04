<script setup lang="ts">
// An opened Pot (ADR 0108, docs/apps/expenses.md): the body under core's header (core's page draws it, ADR 0110 decision 10;
// this contributes the Pot's share, move and delete, the rename of its name in place, and its currency as the subtitle), the ledger notice when the check finds a revision missing, and the tabs Entries /
// Overview (the balances, the settle-up and the costs) — and Settings (the people, the currency, the categories) for whoever
// may add or manage people or change the Pot's meta (owner 2026-11-04). Every control is `../rights.ts`' answer for the
// viewer: a member and a share guest of a `READ` or `READ_WRITE` link get this very page with functions removed (inbox
// #401) — a guest changes no currency or category, manages no people beyond adding and renaming, and reads no writer names (its read has
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
import type { NodeDetailProps } from '../../../core/applications/registry'
import { hostHandlers, hostRights, injectNodeHost, shareOfferOf } from '../../../core/node/nodeHost'
import { standardNodeActions, standardNodeRename } from '../../../core/node/nodeHeader'
import { contributeNodeHeader } from '../../../core/node/nodeHeaderContribution'
import { canReport, nodeTarget } from '../../../core/report/reportActions'
import { useReportOffered } from '../../../core/report/report'
import { loadScopePeople, loadTenantPeople, type Person } from '../../../core/people/directory'
import { targetOf } from '../../../core/access'
import { expensesRights } from '../rights'
import { usePotSession } from '../potSession'
import { personLabel, type MemberDirectory } from '../people'
import { reportEntry, reportReceipt } from '../report'
import { potReceiptSource, type HeldReceipt } from '../receipts'
import EntriesPanel from './EntriesPanel.vue'
import EntryEditor from './EntryEditor.vue'
import OverviewPanel from './OverviewPanel.vue'
import PeoplePanel from './PeoplePanel.vue'
import SettingsPanel from './SettingsPanel.vue'

const props = defineProps<NodeDetailProps>()
// The page's doors (`core/node/nodeHost.ts`): the share, rename, move and delete of the Pot through core's dialogs.
const host = injectNodeHost()
const { t } = useI18n()

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
// list no longer holds as a former member, and the Settings tab's people offer the others to add. A member's read only: a guest's
// page reads no member list, so it is handed none and shows each participant by the name sealed at adding.
const members = shallowRef<Person[] | null>(null)
const directory = computed<MemberDirectory>(() => (members.value ? new Map(members.value.map((m) => [m.userId, m] as const)) : null))
onMounted(() => {
  void session.refresh().catch(() => {})
  if (props.access.mode === 'member') {
    void loadTenantPeople().then((p) => (people.value = p)).catch(() => {})
    void loadScopePeople(props.scopeId).then((m) => (members.value = m)).catch(() => {})
  }
})

// Core's own actions of the Pot (the heart, "Copy to…", the download, Report) come with core's header for this node; this
// page offers Report for an entry and a receipt on its own, by the same rule.
const reportOn = useReportOffered()
const handlers = hostHandlers(host, () => props.node, () => shareOfferOf(rights.value))
const headerActions = computed(() => standardNodeActions(t, { ...hostRights(rights.value, host), deleteLabel: t('expenses.delete') }, handlers))
// The Pot's name, renamed in place in the header where core's rename right and the page's door allow it.
const headerRename = computed(() => standardNodeRename(hostRights(rights.value, host), handlers))
const subtitle = computed(() => (meta.value ? t('expenses.inCurrency', { currency: meta.value.base.currency }) : undefined))
// The header is core's (its order is the kind's `header.order`); the Pot adds its actions and its currency.
contributeNodeHeader(() => ({ subtitle: subtitle.value, actions: headerActions.value, rename: headerRename.value }))

const tab = ref('entries')
/** The Settings tab: the people for whoever may add or manage them, the Pot's meta for whoever may change it. */
const settingsTab = computed(() => rights.value.settings || rights.value.addPeople || rights.value.managePeople)
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
          <VeyqTab value="overview">{{ t('expenses.tabs.overview') }}</VeyqTab>
          <VeyqTab v-if="settingsTab" value="settings">{{ t('expenses.tabs.settings') }}</VeyqTab>
        </VeyqTabList>
        <VeyqTabPanels>
          <template v-if="meta && ledger">
            <VeyqTabPanel value="entries">
              <EntriesPanel :ledger="ledger" :meta="meta" :can-write="rights.writeEntries" :directory="directory" @open="(entryId) => (editing = { entryId })" />
            </VeyqTabPanel>
            <VeyqTabPanel value="overview">
              <OverviewPanel :session="session" :ledger="ledger" :meta="meta" :can-write="rights.writeEntries" :directory="directory" />
            </VeyqTabPanel>
          </template>
          <VeyqTabPanel v-if="settingsTab" value="settings">
            <SettingsPanel :session="session" :meta="meta" :ledger="ledger" :pot-settings="rights.settings">
              <template v-if="meta && ledger" #people>
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
              </template>
            </SettingsPanel>
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
