// The Expenses rights matrix (docs/apps/expenses.md § Rights, ADR 0108 decision 6), one row at a time — and fail
// closed: every case that grants something stands beside the one that differs in a single input and grants it not.
import { describe, it, expect } from 'vitest'
import { ShareRole } from '../../api/generated/sharing'
import { FOLDER_KIND } from '../../core/node/kind'
import type { RightsTarget } from '../../core/access'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import { EXPENSES } from '../platform/capabilities'
import { expensesRights, NO_EXPENSES_RIGHTS } from './rights'
import { POT_KIND } from './kinds'

const pot: RightsTarget = { kind: POT_KIND, inIdentityFolder: false, shareable: true }
const folder: RightsTarget = { kind: FOLDER_KIND, inIdentityFolder: false, shareable: true }
const root: RightsTarget = { kind: null, inIdentityFolder: false, shareable: false }

describe('expensesRights', () => {
  it('a member does everything on a Pot, and sees each revision\'s writer', () => {
    expect(expensesRights(memberAccessOf(), pot)).toEqual({
      view: true, writeEntries: true, addPeople: true, addMembers: true, managePeople: true, settings: true, writers: true, createPot: false,
      share: true, shareContribute: true, renameNode: true, moveNode: true, deleteNode: true,
    })
  })

  it('a READ_WRITE guest writes entries and adds or renames people — never manages them, the settings or a writer\'s name', () => {
    const r = expensesRights(guestAccessOf(ShareRole.ReadWrite), pot)
    expect(r).toEqual({ ...NO_EXPENSES_RIGHTS, view: true, writeEntries: true, addPeople: true })
    // the member-only steps, each false here and true for a member (above)
    expect([r.managePeople, r.settings, r.writers, r.share, r.renameNode, r.moveNode, r.deleteNode]).toEqual([false, false, false, false, false, false, false])
    // it adds names only: the space's members are a member's to see (ADR 0108 decision 2; control: the member above)
    expect(r.addMembers).toBe(false)
  })

  it('a READ guest only views (control: the READ_WRITE one above writes)', () => {
    expect(expensesRights(guestAccessOf(ShareRole.Read), pot)).toEqual({ ...NO_EXPENSES_RIGHTS, view: true })
  })

  it('a WRITE_ONLY link, an unknown role and another kind grant nothing', () => {
    expect(expensesRights(guestAccessOf(ShareRole.WriteOnly), pot)).toEqual(NO_EXPENSES_RIGHTS)
    expect(expensesRights(guestAccessOf('OWNER'), pot)).toEqual(NO_EXPENSES_RIGHTS)
    expect(expensesRights(memberAccessOf(), { ...pot, kind: 'MEMORY' })).toEqual(NO_EXPENSES_RIGHTS)
    expect(expensesRights(guestAccessOf(ShareRole.ReadWrite), folder)).toEqual(NO_EXPENSES_RIGHTS)
  })

  it('Expenses switched off: read-only — the trash stays (control: on, the same member writes)', () => {
    const off = expensesRights(memberAccessOf({ applicationsOff: new Set([EXPENSES]) }), pot)
    expect(off).toEqual({ ...NO_EXPENSES_RIGHTS, view: true, writers: true, deleteNode: true })
    expect(expensesRights(memberAccessOf({ applicationsOff: new Set(['depot']) }), pot).writeEntries).toBe(true)
  })

  it('a scope pending deletion takes no write, not even the trash', () => {
    expect(expensesRights(memberAccessOf({ readonly: true }), pot)).toEqual({ ...NO_EXPENSES_RIGHTS, view: true, writers: true })
  })

  it('a share needs the instance\'s links and a shareable Pot; READ_WRITE needs the contribute leaf', () => {
    expect(expensesRights(memberAccessOf({ publicShares: false }), pot).share).toBe(false)
    expect(expensesRights(memberAccessOf(), { ...pot, shareable: false }).share).toBe(false)
    const noContribute = expensesRights(memberAccessOf({ publicSharesContribute: false }), pot)
    expect([noContribute.share, noContribute.shareContribute]).toEqual([true, false])
  })

  it('only a member creates a Pot, at a folder or the root, never in a guest\'s identity folder or while switched off', () => {
    expect(expensesRights(memberAccessOf(), folder).createPot).toBe(true)
    expect(expensesRights(memberAccessOf(), root).createPot).toBe(true)
    expect(expensesRights(memberAccessOf(), { ...folder, inIdentityFolder: true }).createPot).toBe(false)
    expect(expensesRights(memberAccessOf({ applicationsOff: new Set([EXPENSES]) }), folder).createPot).toBe(false)
    expect(expensesRights(guestAccessOf(ShareRole.ReadWrite), folder).createPot).toBe(false)
  })
})
