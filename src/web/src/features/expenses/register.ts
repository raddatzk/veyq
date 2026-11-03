// The Expenses application's registration with the platform registry (ADR 0079, ADR 0108). Called once, from the
// composition root (`src/web/src/applications.ts`). The registry is a parameter, so a test registers into a fresh one.
//
// The `EXPENSES_POT` kind: its rows in the unified level list (`levelRows.ts`, the base currency as the row's detail, CR
// decision 8), a "New Pot" entry in the level's menu for a member (`createDialog`: a name, the base currency, the first
// people), its `detail` (the Pot page — entries, balances, overview, people, settings — for a member and, with functions
// removed, for a `READ` or `READ_WRITE` guest: core's page of a link mounts it, inbox #401), its `shareDialog` (`READ` and
// `READ_WRITE` links only), its download, import and copy (`exportImport.ts`), its re-key (`rekey.ts`: every revision's
// `K_rev` and every receipt's `K_blob` re-wrapped, no body touched) and the moderator's review of a reported entry or receipt
// (`PotEntryReview`). No share body: a Pot is never a `WRITE_ONLY` root, so every guest gets core's page.
import { FOLDER_KIND } from '../../core/node/kind'
import type { LevelActionContext, Registry } from '../../core/applications/registry'
import { targetOf } from '../../core/access'
import { EXPENSES } from '../platform/capabilities'
import { POT_KIND } from './kinds'
import { expensesRights } from './rights'
import { potRows } from './levelRows'
import { rekeyPotContent } from './rekey'
import { EXPENSES_CONTENT_VERSION, exportPot, importPot, validatePotContent } from './exportImport'
import PotCreateDialog from './components/PotCreateDialog.vue'
import PotDetail from './components/PotDetail.vue'
import PotShareDialog from './components/PotShareDialog.vue'
import PotEntryReview from './components/PotEntryReview.vue'
// Expenses' global stylesheet — LAST, after the components above (Memories' `memories.css` header has the reason).
import './expenses.css'

/** Where "New Pot" sits in the level's "New" menu: after core's folder (0), Memories' Memory (10), Depot's drop box (20)
 *  and its uploads (30, 31). */
const NEW_POT_ORDER = 40

/** Where a Pot lives (the backend's `ExpensesNodeKindRules`): at the root or directly in a folder, never inside another
 *  node's content. */
const POT_PLACEMENT = { root: true, parents: [FOLDER_KIND] } as const

function emptyText(ctx: LevelActionContext): string {
  if (expensesRights(ctx.access, targetOf(ctx.tree, ctx.folder)).createPot) return 'expenses.empty'
  return 'expenses.emptyReadOnly'
}

export function registerExpenses(registry: Registry): void {
  // Expenses names itself once (its entry of the capability register, which switches its kind,
  // uc.platform.switch-application-off) and registers everything else through its registrar.
  const app = registry.registerApplication({ id: EXPENSES, name: 'expenses.title' })
  app.kind(POT_KIND, {
    icon: 'wallet',
    // The header's priority (ADR 0110): favorite, sharing (a Pot is kept together), its download, then rename, move, copy.
    header: { order: ['favorite', 'share', 'download', 'rename', 'move', 'copy'] },
    detail: PotDetail,
    shareDialog: PotShareDialog,
    deleteTitle: 'expenses.delete',
    deleteCopy: 'expenses.deleteConfirm',
    nounWithArticle: 'expenses.aPot',
    list: { rows: potRows, tabLabel: 'expenses.title', emptyText },
    createDialog: PotCreateDialog,
    createEntry: {
      label: 'expenses.create.entry', icon: 'wallet', order: NEW_POT_ORDER,
      allowed: (ctx: LevelActionContext) => expensesRights(ctx.access, targetOf(ctx.tree, ctx.folder)).createPot,
      enabled: (ctx: LevelActionContext) => ctx.epochs !== null,
    },
    placement: POT_PLACEMENT,
    rekeyContent: rekeyPotContent,
    exportContent: exportPot,
    importContent: importPot,
    contentSchema: { version: EXPENSES_CONTENT_VERSION },
    validateContent: validatePotContent,
    // The import reads a receipt's payload only through the upload pump's frame-sized slices (the CSV is for a spreadsheet
    // only, never read), so a copy hands it the original read by frame and nothing is buffered whole.
    importStreams: true,
    copyLosses: ['expenses.copyLoss'],
  })
  app.route('expenses')
  app.itemReview(PotEntryReview)
}

