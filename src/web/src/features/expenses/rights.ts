// What an Expenses view may offer to whoever looks (docs/apps/expenses.md § Rights, ADR 0108 decision 6): one pure,
// FAIL-CLOSED function over core's `Access` and the node on screen, so the Pot's rows (`levelRows.ts`) and its page
// (`components/PotDetail.vue`) show a control only where this says yes — a member and a share guest see the same view with
// functions removed (inbox #401), never a guest-only screen. Every field defaults to false: an unknown mode, role or kind
// grants nothing.
//
// The matrix, once:
//
// |                                                         | member | READ guest | READ_WRITE guest |
// |---------------------------------------------------------|--------|------------|------------------|
// | see entries, history, balances, overviews; download     | ✓      | ✓          | ✓                |
// |   (an entry's receipts opened and downloaded among them) |        |            |                  |
// | create, edit, delete and restore entries; add and       | ✓      | ✗          | ✓                |
// |   remove their receipts (`writeEntries`)                 |        |            |                  |
// | add and rename participants                             | ✓      | ✗          | ✓                |
// | add a member of the space as a participant (`addMembers`) | ✓    | ✗          | ✗                |
// | deactivate, activate, delete and remove participants    | ✓      | ✗          | ✗                |
// | the Pot's meta (own categories, base currency)          | ✓      | ✗          | ✗                |
// | the writer of each revision (the history's "who")       | ✓      | ✗          | ✗                |
// | create a Pot                                            | ✓      | ✗          | ✗                |
// | share, rename, move, delete the Pot (core's answer)     | ✓      | ✗          | ✗                |
//
// A link guest adds names only: it cannot see the space's members (ADR 0108 decision 2) — the member's view with one
// function fewer, not a guest's own.
//
// A member's write needs the Expenses switch on (uc.platform.switch-application-off: off, a Pot is read-only — but its
// delete to the trash and restore stay, as the server's exemptions) and the scope not pending deletion. A guest is never
// told the switch (the server refuses its write). **The Pot's share, rename, move and delete are core's** (`nodeRights`,
// core/access.ts), taken as they stand — so a pending deletion keeps the share dialog to see the Pot's links, as on every
// other kind — and narrowed for a guest, who moves no Pot; `rights.test.ts` asserts that no answer here is wider.
import { FOLDER_KIND } from '../../core/node/kind'
import { applicationWritable, nodeRights, type Access, type MemberAccess, type NodeRights, type RightsTarget } from '../../core/access'
import { ShareRole } from '../../api/generated/sharing'
import { POT_KIND } from './kinds'

export interface ExpensesRights extends Omit<NodeRights, 'shareUpload'> {
  /** See the Pot: its entries, history, balances, overviews; download it. */
  view: boolean
  /** Create, edit, delete and restore entries, and add and remove their receipts; record a settle-up transfer. */
  writeEntries: boolean
  /** Add a participant and rename one. */
  addPeople: boolean
  /** Add a member of the space as a participant, beside typing a name (reads the space's member list). */
  addMembers: boolean
  /** Deactivate, activate, delete and remove a participant (the member's steps, ADR 0108 decision 6). */
  managePeople: boolean
  /** Change the Pot's meta: its base currency (while it holds no entry) and its own categories. */
  settings: boolean
  /** See who wrote each revision (a member's read carries the writer; a guest's has no such field, decision 3). */
  writers: boolean
  /** Create a Pot at this level. */
  createPot: boolean
}

export const NO_EXPENSES_RIGHTS: Readonly<ExpensesRights> = Object.freeze({
  view: false,
  writeEntries: false,
  addPeople: false,
  addMembers: false,
  managePeople: false,
  settings: false,
  writers: false,
  createPot: false,
  share: false,
  shareContribute: false,
  rename: false,
  move: false,
  delete: false,
})

/** The Expenses matrix (see the header). `at` is the node on screen: a folder level (`FOLDER`, or null at a member's scope
 *  root) — where a Pot may be created — or an opened Pot. */
export function expensesRights(access: Access, at: RightsTarget): ExpensesRights {
  if (access.mode === 'member') return memberRights(access, at)
  if (access.mode === 'guest') return guestRights(access.role, at)
  return NO_EXPENSES_RIGHTS
}

function memberRights(access: MemberAccess, at: RightsTarget): ExpensesRights {
  const write = !access.readonly && applicationWritable(access, POT_KIND)
  // Nothing is placed in a guest's identity folder (a server placement rule).
  if (at.kind === null || at.kind === FOLDER_KIND) return { ...NO_EXPENSES_RIGHTS, createPot: write && !at.inIdentityFolder }
  if (at.kind !== POT_KIND) return NO_EXPENSES_RIGHTS
  const node = nodeRights(access, at)
  return {
    ...NO_EXPENSES_RIGHTS,
    view: true,
    writers: true,
    writeEntries: write,
    addPeople: write,
    addMembers: write,
    managePeople: write,
    settings: write,
    share: node.share,
    shareContribute: node.shareContribute,
    rename: node.rename,
    move: node.move,
    delete: node.delete,
  }
}

function guestRights(role: string, at: RightsTarget): ExpensesRights {
  if (at.kind !== POT_KIND) return NO_EXPENSES_RIGHTS
  switch (role) {
    case ShareRole.Read:
      return { ...NO_EXPENSES_RIGHTS, view: true }
    case ShareRole.ReadWrite:
      // Entries and adding or renaming people are the contributor's; the rest is a member's (ADR 0108 decision 6) — a Pot
      // too, which core's answer would let a folder link's contributor move: Expenses offers it no move.
      return { ...NO_EXPENSES_RIGHTS, view: true, writeEntries: true, addPeople: true }
    default:
      // A WRITE_ONLY link on a Pot does not exist (the backend's share roots refuse it): nothing.
      return NO_EXPENSES_RIGHTS
  }
}
