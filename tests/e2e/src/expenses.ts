// Expenses through a member's and a guest's screens (cr-expenses-ledger's browser pass, expenses-pot.spec.ts): the labels
// in both languages, the "trip" Pot of contracts/expenses/v1-vectors.json typed in through the entry editor, and the Pot's
// figures read back as the screen shows them. The labels are src/web/'s own — core's catalog (src/web/src/i18n/index.ts)
// and Expenses' (src/web/src/features/expenses/i18n.ts), EN and DE — so a renamed button is red here in both languages.
import { expect, type Locator, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { recordTyped } from './canaries'

export type Lang = 'en' | 'de'

/** Every label the pass reads, per language. A function where the catalog's text takes a value. */
export const LABELS = {
  en: {
    signIn: 'Sign in', email: 'Email', password: /^Password\b/, new: 'New', newFolder: 'New folder',
    createFolder: 'Create a folder', name: 'Name', create: 'Create', cancel: 'Cancel', save: 'Save', settings: 'Settings',
    share: 'Share', download: 'Download', copyTo: 'Copy to…', uploadFiles: 'Upload files', done: 'Done',
    termsAccept: 'Accept and continue',
    shareTitle: 'Share this Pot', newShare: 'New share', viewOnly: 'View only', contribute: 'View + contribute',
    createLink: 'Create link', publicLink: 'Public link',
    guestNameTitle: 'What is your name?', yourName: 'Your name', continue: 'Continue', guestName: (n: string) => `${n} (guest)`,
    exportTitle: (n: string) => `Download “${n}”`, saveZip: 'Save as ZIP…', downloadSaved: 'Download saved',
    importTitle: 'Import', importSubmit: 'Import', imported: (n: string) => `Imported “${n}”`,
    copyTitle: (n: string) => `Copy “${n}”`, copyHere: 'Copy here', copied: (n: string, to: string) => `“${n}” copied to “${to}”.`,
    report: 'Report', reportSend: 'Send report', reportSent: 'Your report was received.', close: 'Close',
    goodFaith: 'I confirm that what I say here is accurate and complete to the best of my knowledge.',
    // A category per language, so each pass's case is the one open ITEM case of that category on the tenant.
    reportCategory: 'A breach of the terms',
    moderationSignIn: 'Sign in', unlock: 'Open the moderation key', openCase: 'Open', caseOpen: 'Open',
    itemKind: 'A single item, such as a photo', review: 'Review what was reported', dismissCase: 'Dismiss the case',
    remove: 'Remove', removeConfirm: 'Remove and rotate the key?',
    rekeying: 'The new lock is going in. Adding things here waits until it’s done.',
    notifications: 'Notifications',
    pot: {
      newPot: 'New Pot', baseCurrency: /^Base currency/, addPerson: 'Add person', open: (n: string) => `Open ${n}`, kindPot: 'Pot',
      tabs: { entries: 'Entries', balances: 'Balances', overview: 'Overview', people: 'People', settings: 'Settings' },
      kind: { expense: 'Expense', refund: 'Refund', transfer: 'Transfer' },
      split: { equal: 'Equally', shares: 'By shares', exact: 'By exact amounts', items: 'As line items' },
      rest: { proportional: 'Rest by each person’s items', equal: 'Rest equally' },
      category: { food: 'Food & drink', groceries: 'Groceries', leisure: 'Leisure', transport: 'Transport', other: 'Other' } as Record<string, string>,
      addEntry: 'Add entry', editEntry: 'Edit entry', viewEntry: 'Entry',
      title: 'Title', note: 'Note (optional)', amount: 'Amount', currency: /^Currency/, category_: /^Category/,
      rate: (from: string, to: string) => `Rate: 1 ${from} in ${to}`, from: /^From\b/, to: /^To\b/, person: 'Person',
      payerAmount: (n: string) => `Amount paid by ${n}`, addPayer: 'Add a payer', item: 'Item', itemAmount: 'Item amount', addItem: 'Add item',
      delete: 'Delete', restore: 'Restore', history: 'History', deletedEntries: 'Deleted entries', deletedBadge: 'deleted',
      invalid: 'does not add up',
      stale: 'Someone else changed this entry. Here is their version; save again to put yours on top.',
      ledgerMissing: 'Changes to these entries are missing, so the balances may be wrong:',
      owed: (a: string) => `is owed ${a}`, owes: (a: string) => `owes ${a}`, settled: 'settled',
      pays: (f: string, t: string) => `${f} pays ${t}`, recordAll: 'Record all', allSettled: 'Everyone is settled.',
      byAt: (n: string) => `${n}, `,
      deactivate: 'Deactivate', activate: 'Activate', inactive: 'deactivated',
      deletePerson: (n: string) => `Delete ${n}`, deleteAccept: 'Delete', removePerson: (n: string) => `Remove ${n}`, removeAccept: 'Remove',
      deletedPeople: 'deleted people', oneDeleted: 'One deleted person.', paidBy: (n: string) => `paid by ${n}`,
      copyLoss: 'The history of each entry and the deleted entries: a copy starts every entry at its current version.',
      reviewTitle: 'The reported entry, every version',
    },
  },
  de: {
    signIn: 'Anmelden', email: 'E-Mail', password: /^Passwort\b/, new: 'Neu', newFolder: 'Neuer Ordner',
    createFolder: 'Ordner anlegen', name: 'Name', create: 'Erstellen', cancel: 'Abbrechen', save: 'Speichern', settings: 'Einstellungen',
    share: 'Teilen', download: 'Herunterladen', copyTo: 'Kopieren nach …', uploadFiles: 'Dateien hochladen', done: 'Fertig',
    termsAccept: 'Akzeptieren und fortfahren',
    shareTitle: 'Diese Kasse teilen', newShare: 'Neuer Link', viewOnly: 'Nur ansehen', contribute: 'Ansehen + mitwirken',
    createLink: 'Link erstellen', publicLink: 'Öffentlicher Link',
    guestNameTitle: 'Wie heißt du?', yourName: 'Dein Name', continue: 'Weiter', guestName: (n: string) => `${n} (Gast)`,
    exportTitle: (n: string) => `„${n}“ herunterladen`, saveZip: 'Als ZIP speichern…', downloadSaved: 'Download gespeichert',
    importTitle: 'Importieren', importSubmit: 'Importieren', imported: (n: string) => `„${n}“ importiert`,
    copyTitle: (n: string) => `„${n}“ kopieren`, copyHere: 'Hierher kopieren', copied: (n: string, to: string) => `„${n}“ nach „${to}“ kopiert.`,
    report: 'Melden', reportSend: 'Meldung senden', reportSent: 'Deine Meldung ist eingegangen.', close: 'Schließen',
    goodFaith: 'Ich bestätige, dass meine Angaben nach bestem Wissen richtig und vollständig sind.',
    reportCategory: 'Andere rechtswidrige Inhalte',
    moderationSignIn: 'Anmelden', unlock: 'Moderationsschlüssel öffnen', openCase: 'Öffnen', caseOpen: 'Offen',
    itemKind: 'Ein einzelnes Element, etwa ein Foto', review: 'Gemeldetes ansehen', dismissCase: 'Fall zurückweisen',
    remove: 'Entfernen', removeConfirm: 'Entfernen und Schlüssel rotieren?',
    rekeying: 'Das neue Schloss wird eingebaut. Hinzufügen wartet hier, bis das fertig ist.',
    notifications: 'Benachrichtigungen',
    pot: {
      newPot: 'Neue Kasse', baseCurrency: /^Basiswährung/, addPerson: 'Person hinzufügen', open: (n: string) => `${n} öffnen`, kindPot: 'Kasse',
      tabs: { entries: 'Einträge', balances: 'Salden', overview: 'Überblick', people: 'Personen', settings: 'Einstellungen' },
      kind: { expense: 'Ausgabe', refund: 'Erstattung', transfer: 'Überweisung' },
      split: { equal: 'Zu gleichen Teilen', shares: 'Nach Anteilen', exact: 'Nach genauen Beträgen', items: 'Nach Posten' },
      rest: { proportional: 'Rest nach den Posten jeder Person', equal: 'Rest zu gleichen Teilen' },
      category: { food: 'Essen & Trinken', groceries: 'Einkäufe', leisure: 'Freizeit', transport: 'Transport', other: 'Sonstiges' } as Record<string, string>,
      addEntry: 'Eintrag hinzufügen', editEntry: 'Eintrag bearbeiten', viewEntry: 'Eintrag',
      title: 'Titel', note: 'Notiz (optional)', amount: 'Betrag', currency: /^Währung/, category_: /^Kategorie/,
      rate: (from: string, to: string) => `Kurs: 1 ${from} in ${to}`, from: /^Von\b/, to: /^An\b/, person: 'Person',
      payerAmount: (n: string) => `Betrag, den ${n} bezahlt hat`, addPayer: 'Weitere Person hat bezahlt', item: 'Posten', itemAmount: 'Betrag des Postens', addItem: 'Posten hinzufügen',
      delete: 'Löschen', restore: 'Wiederherstellen', history: 'Verlauf', deletedEntries: 'Gelöschte Einträge', deletedBadge: 'gelöscht',
      invalid: 'geht nicht auf',
      stale: 'Jemand anderes hat diesen Eintrag geändert. Hier ist die neue Fassung; speichere erneut, um deine darüberzulegen.',
      ledgerMissing: 'Änderungen an diesen Einträgen fehlen, die Salden können also falsch sein:',
      owed: (a: string) => `bekommt ${a}`, owes: (a: string) => `schuldet ${a}`, settled: 'ausgeglichen',
      pays: (f: string, t: string) => `${f} zahlt an ${t}`, recordAll: 'Alle eintragen', allSettled: 'Alle sind ausgeglichen.',
      byAt: (n: string) => `${n}, `,
      deactivate: 'Deaktivieren', activate: 'Aktivieren', inactive: 'deaktiviert',
      deletePerson: (n: string) => `${n} löschen`, deleteAccept: 'Löschen', removePerson: (n: string) => `${n} entfernen`, removeAccept: 'Entfernen',
      deletedPeople: 'gelöschte Personen', oneDeleted: 'Eine gelöschte Person.', paidBy: (n: string) => `bezahlt von ${n}`,
      copyLoss: 'Den Verlauf jedes Eintrags und die gelöschten Einträge: Eine Kopie beginnt jeden Eintrag mit seiner aktuellen Fassung.',
      reviewTitle: 'Der gemeldete Eintrag, jede Fassung',
    },
  },
} as const

export type Labels = (typeof LABELS)[Lang]

// --- the vector -----------------------------------------------------------------------------------------------------------

type Money = { participant: string; amount: number }
export interface VectorEntry {
  id: string
  body: {
    kind: 'expense' | 'refund' | 'transfer'
    title: string
    category?: string
    currency: string
    minorUnits: number
    rate: string
    amount: number
    payers?: Money[]
    receivers?: Money[]
    from?: string
    to?: string
    allocation?:
      | { mode: 'equal'; participants: string[] }
      | { mode: 'shares'; shares: { participant: string; shares: number }[] }
      | { mode: 'exact'; amounts: Money[] }
      | { mode: 'items'; rest: 'proportional' | 'equal'; items: { label: string; amount: number; participants: string[] }[] }
  }
}
export interface TripPot {
  base: { currency: string; minorUnits: number }
  participants: { id: string; name: string }[]
  entries: VectorEntry[]
  expected: {
    entries: Record<string, { paid?: Record<string, number>; received?: Record<string, number>; shares?: Record<string, number> }>
    balances: Record<string, number>
    settleUp: { from: string; to: string; amount: number }[]
  }
}

const VECTORS = fileURLToPath(new URL('../../../contracts/expenses/v1-vectors.json', import.meta.url))

/** The "trip" Pot of the format's conformance vectors: the entries a second client must compute the same balances from. */
export function tripPot(): TripPot {
  const pots = (JSON.parse(readFileSync(VECTORS, 'utf8')) as { pots: (TripPot & { name: string })[] }).pots
  const trip = pots.find((p) => p.name === 'trip')
  if (!trip) throw new Error(`${VECTORS} holds no "trip" Pot`)
  return trip
}

/** Minor units as the reader types them: the decimal string with the language's separator ("40.01", "40,01"). */
export function typed(amount: number, minorUnits: number, lang: Lang): string {
  const digits = String(amount).padStart(minorUnits + 1, '0')
  const text = minorUnits === 0 ? digits : `${digits.slice(0, -minorUnits)}.${digits.slice(-minorUnits)}`
  return lang === 'de' ? text.replace('.', ',') : text
}

/** A rate as the reader types it ("1.1725", "1,1725"). */
export const typedRate = (rate: string, lang: Lang) => (lang === 'de' ? rate.replace('.', ',') : rate)

/** Base minor units as the Pot shows them (`amounts.ts` `formatMoney`: the language, the currency's digits). */
export function money(amount: number, currency: string, minorUnits: number, lang: Lang): string {
  return new Intl.NumberFormat(lang, { style: 'currency', currency, minimumFractionDigits: minorUnits, maximumFractionDigits: minorUnits })
    .format(amount / 10 ** minorUnits)
}

export const escapeRegExp = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** Whitespace as one space: `Intl` puts a no-break space between "6,59" and "€". */
export const squash = (s: string) => s.replace(/\s+/g, ' ').trim()

// --- the screens ------------------------------------------------------------------------------------------------------

/** An entry's row in the open Pot's Entries, by its exact title (the live list and the deleted one). */
export const entryRow = (page: Page, title: string): Locator =>
  page.locator('.expenses-entries .expenses-row').filter({ has: page.locator('.veyq-row-title').getByText(title, { exact: true }) })

export const potTab = (page: Page, name: string): Locator => page.getByRole('tab', { name, exact: true })

export async function openTab(page: Page, name: string): Promise<void> {
  await potTab(page, name).click()
  await expect(potTab(page, name)).toHaveAttribute('aria-selected', 'true')
}

/** The Balances tab as read: each shown person's row (name → text) and the settle-up's transfers in order. */
export async function readBalances(page: Page, L: Labels): Promise<{ rows: [string, string][]; transfers: [string, string][]; settled: boolean }> {
  await openTab(page, L.pot.tabs.balances)
  const section = page.locator('.expenses-balances')
  await expect(section).toBeVisible()
  return section.evaluate((el) => {
    const s = (x: Element | null | undefined) => (x?.textContent ?? '').replace(/\s+/g, ' ').trim()
    const lists = el.querySelectorAll(':scope > ul.veyq-list')
    const pairs = (ul: Element | undefined) =>
      [...(ul?.querySelectorAll(':scope > li') ?? [])].map((li) => [s(li.querySelector('.veyq-row-title')), s(li.querySelector('.expenses-amount'))] as [string, string])
    return { rows: pairs(lists[0]), transfers: pairs(lists[1]), settled: !!el.querySelector('.veyq-empty') }
  })
}

/** The Overview tab as read: each table's rows, cell texts in order (the category table's footer is its last row). */
export async function readOverview(page: Page, L: Labels): Promise<string[][][]> {
  await openTab(page, L.pot.tabs.overview)
  const section = page.locator('.expenses-overview')
  await expect(section).toBeVisible()
  return section.evaluate((el) =>
    [...el.querySelectorAll('table')].map((table) =>
      [...table.querySelectorAll('tbody tr, tfoot tr')].map((tr) =>
        [...tr.querySelectorAll('th, td')].map((c) => (c.textContent ?? '').replace(/\s+/g, ' ').trim()))),
  )
}

/** Minor units from an amount the screen shows, in either language ("€1,234.50", "1.234,50 €", "-€3.00"). */
export function minorOf(shown: string): number {
  const negative = /[-−]/.test(shown)
  const digits = shown.replace(/[^0-9]/g, '')
  return (negative ? -1 : 1) * Number(digits)
}

/** The ledger notice, which must not show on a Pot whose served list is whole. */
export const ledgerNotice = (page: Page) => page.locator('.expenses-ledger-notice')

/** A Reka select's option, picked: the trigger, then the portaled option by its exact label. */
export async function pick(page: Page, trigger: Locator, option: string): Promise<void> {
  await trigger.click()
  await page.getByRole('option', { name: option, exact: true }).click()
  await expect(page.getByRole('listbox')).toHaveCount(0)
}

/** The open entry dialog: the editor of a new entry, or of the one opened. */
export const entryDialog = (page: Page, header: string) => page.getByRole('dialog', { name: header, exact: true })

/**
 * Type [entry] of the vector into a new entry and save it: its kind, title, currency and typed rate, amount, category,
 * payers (or receivers, or a transfer's two ends) and split — every field as a person sets it in the editor. [names]
 * maps the vector's participant ids to the Pot's people. The date is left at the editor's default (today): no balance
 * reads it.
 */
export async function typeEntry(page: Page, L: Labels, lang: Lang, entry: VectorEntry, names: Map<string, string>, title: string): Promise<void> {
  const b = entry.body
  const name = (id: string) => names.get(id) ?? (() => { throw new Error(`the vector names ${id}, no person of the Pot`) })()
  await page.locator('.expenses-entries').getByRole('button', { name: L.pot.addEntry, exact: true }).click()
  const dialog = entryDialog(page, L.pot.addEntry)
  await expect(dialog).toBeVisible()
  if (b.kind !== 'expense') await dialog.getByRole('button', { name: L.pot.kind[b.kind], exact: true }).click()
  await expect(dialog.getByRole('button', { name: L.pot.kind[b.kind], exact: true })).toHaveAttribute('aria-pressed', 'true')
  await dialog.getByLabel(b.kind === 'transfer' ? L.pot.note : L.pot.title, { exact: true }).fill(recordTyped(title))
  if (b.currency !== 'EUR') {
    await pick(page, dialog.getByRole('combobox', { name: L.pot.currency }), b.currency)
    // The label holds the field and its hint ("The entry keeps this rate…"): matched by its start.
    await dialog.getByLabel(new RegExp(`^${escapeRegExp(L.pot.rate(b.currency, 'EUR'))}`)).fill(typedRate(b.rate, lang))
  }
  await dialog.getByLabel(L.pot.amount, { exact: true }).fill(typed(b.amount, b.minorUnits, lang))
  if (b.kind === 'transfer') {
    await pick(page, dialog.getByRole('combobox', { name: L.pot.from }), name(b.from!))
    await pick(page, dialog.getByRole('combobox', { name: L.pot.to }), name(b.to!))
  } else {
    await pick(page, dialog.getByRole('combobox', { name: L.pot.category_ }), L.pot.category[b.category!]!)
    const payers = (b.kind === 'expense' ? b.payers : b.receivers)!
    for (let i = 1; i < payers.length; i++) await dialog.getByRole('button', { name: L.pot.addPayer, exact: true }).click()
    const rows = dialog.getByRole('combobox', { name: L.pot.person, exact: true })
    await expect(rows).toHaveCount(payers.length)
    for (const [i, p] of payers.entries()) await pick(page, rows.nth(i), name(p.participant))
    if (payers.length > 1) {
      for (const p of payers) await dialog.getByRole('textbox', { name: L.pot.payerAmount(name(p.participant)), exact: true }).fill(typed(p.amount, b.minorUnits, lang))
    }
    await split(dialog, L, lang, b, names)
  }
  await dialog.getByRole('button', { name: L.save, exact: true }).click()
  await expect(dialog).toBeHidden({ timeout: 30_000 })
  await expect(entryRow(page, title)).toHaveCount(1)
}

async function split(dialog: Locator, L: Labels, lang: Lang, b: VectorEntry['body'], names: Map<string, string>): Promise<void> {
  const a = b.allocation!
  const everyone = [...names.values()]
  const on = (who: string[]) => new Set(who.map((id) => names.get(id)!))
  if (a.mode !== 'equal') await dialog.getByRole('button', { name: L.pot.split[a.mode], exact: true }).click()
  await expect(dialog.getByRole('button', { name: L.pot.split[a.mode], exact: true })).toHaveAttribute('aria-pressed', 'true')
  if (a.mode === 'equal') {
    const sharing = on(a.participants)
    for (const n of everyone) await dialog.getByRole('checkbox', { name: n, exact: true }).setChecked(sharing.has(n))
  } else if (a.mode === 'shares') {
    for (const s of a.shares) await dialog.getByRole('textbox', { name: names.get(s.participant)!, exact: true }).fill(String(s.shares))
  } else if (a.mode === 'exact') {
    for (const s of a.amounts) await dialog.getByRole('textbox', { name: names.get(s.participant)!, exact: true }).fill(typed(s.amount, b.minorUnits, lang))
  } else {
    for (const [i, item] of a.items.entries()) {
      await dialog.getByRole('button', { name: L.pot.addItem, exact: true }).click()
      const row = dialog.locator('.expenses-item').nth(i)
      await row.getByRole('textbox', { name: L.pot.item, exact: true }).fill(recordTyped(itemLabel(item.label)))
      await row.getByRole('textbox', { name: L.pot.itemAmount, exact: true }).fill(typed(item.amount, b.minorUnits, lang))
      const sharing = on(item.participants)
      for (const n of everyone) await row.getByRole('checkbox', { name: n, exact: true }).setChecked(sharing.has(n))
    }
    await dialog.getByRole('button', { name: L.pot.rest[a.rest], exact: true }).click()
    await expect(dialog.getByRole('button', { name: L.pot.rest[a.rest], exact: true })).toHaveAttribute('aria-pressed', 'true')
  }
}

/** A line item's label as typed: the vector's word and a spelling the sweep cannot find in ciphertext by chance. */
export const itemLabel = (label: string) => `${label} am Tisch`
