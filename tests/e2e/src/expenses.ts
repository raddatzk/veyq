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
    accessEnded: 'You no longer have access to this space',
    // Settings → Capabilities, the tenant owner's register (src/web/src/i18n/index.ts `tenantCapabilities`, the catalog's title)
    capabilities: 'Capabilities', switchOff: 'Switch off', switchOn: 'Switch on', exchangeRates: 'Exchange rates',
    switchOffTitle: (title: string) => `Switch off “${title}”?`,
    pot: {
      newPot: 'New Pot', baseCurrency: /^Base currency/, addPerson: 'Add person', open: (n: string) => `Open ${n}`, kindPot: 'Pot',
      addAsName: (n: string) => `Add “${n}” as a name`,
      tabs: { entries: 'Entries', balances: 'Balances', overview: 'Overview', people: 'People', settings: 'Settings' },
      kind: { expense: 'Expense', refund: 'Refund', transfer: 'Transfer' },
      split: { equal: 'Equally', shares: 'By shares', percent: 'By percent' },
      sharesOf: (n: string) => `Shares of ${n}`, percentOf: (n: string) => `Percent of ${n}`,
      category: { food: 'Food & drink', groceries: 'Groceries', leisure: 'Leisure', transport: 'Transport', other: 'Other' } as Record<string, string>,
      addEntry: 'Add entry', editEntry: 'Edit entry', viewEntry: 'Entry',
      title: 'Title', note: 'Note (optional)', amount: 'Amount', currency: /^Currency/, category_: /^Category/,
      rate: (from: string, to: string) => `Rate: 1 ${from} in ${to}`, from: /^From\b/, to: /^To\b/, person: 'Person',
      rateFilled: (day: string) => `Computed from the ECB’s euro reference rates of ${day}.`,
      payerAmount: (n: string) => `Amount paid by ${n}`, receiverAmount: (n: string) => `Amount received by ${n}`, addPayer: 'Add a payer', item: 'Item', itemAmount: 'Item amount', addItem: 'Add item',
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
      whoAmI: 'You are', nobody: 'Not chosen', you: 'you',
      receipts: {
        title: 'Receipts', add: 'Add receipt', open: (n: string) => `Open ${n}`, close: (n: string) => `Close ${n}`,
        download: (n: string) => `Download ${n}`, remove: (n: string) => `Remove ${n}`, downloadOnly: 'Download',
        notShown: 'This receipt cannot be shown here. You can download it.',
        notReceipt: (n: string) => `Only images and PDFs can be receipts, not added: ${n}.`,
        // the word in any text that names receipts (the copy's losses must not)
        word: /receipt/i,
      },
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
    accessEnded: 'Du hast keinen Zugriff mehr auf diesen Bereich',
    capabilities: 'Funktionen', switchOff: 'Ausschalten', switchOn: 'Einschalten', exchangeRates: 'Wechselkurse',
    switchOffTitle: (title: string) => `„${title}“ ausschalten?`,
    pot: {
      newPot: 'Neue Kasse', baseCurrency: /^Basiswährung/, addPerson: 'Person hinzufügen', open: (n: string) => `${n} öffnen`, kindPot: 'Kasse',
      addAsName: (n: string) => `„${n}“ als Namen hinzufügen`,
      tabs: { entries: 'Einträge', balances: 'Salden', overview: 'Überblick', people: 'Personen', settings: 'Einstellungen' },
      kind: { expense: 'Ausgabe', refund: 'Erstattung', transfer: 'Überweisung' },
      split: { equal: 'Zu gleichen Teilen', shares: 'Nach Anteilen', percent: 'Nach Prozent' },
      sharesOf: (n: string) => `Anteile von ${n}`, percentOf: (n: string) => `Prozent von ${n}`,
      category: { food: 'Essen & Trinken', groceries: 'Einkäufe', leisure: 'Freizeit', transport: 'Transport', other: 'Sonstiges' } as Record<string, string>,
      addEntry: 'Eintrag hinzufügen', editEntry: 'Eintrag bearbeiten', viewEntry: 'Eintrag',
      title: 'Titel', note: 'Notiz (optional)', amount: 'Betrag', currency: /^Währung/, category_: /^Kategorie/,
      rate: (from: string, to: string) => `Kurs: 1 ${from} in ${to}`, from: /^Von\b/, to: /^An\b/, person: 'Person',
      rateFilled: (day: string) => `Berechnet aus den Euro-Referenzkursen der EZB vom ${day}.`,
      payerAmount: (n: string) => `Betrag, den ${n} bezahlt hat`, receiverAmount: (n: string) => `Betrag, den ${n} erhalten hat`, addPayer: 'Weitere Person hat bezahlt', item: 'Posten', itemAmount: 'Betrag des Postens', addItem: 'Posten hinzufügen',
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
      whoAmI: 'Du bist', nobody: 'Nicht gewählt', you: 'du',
      receipts: {
        title: 'Belege', add: 'Beleg hinzufügen', open: (n: string) => `${n} öffnen`, close: (n: string) => `${n} schließen`,
        download: (n: string) => `${n} herunterladen`, remove: (n: string) => `${n} entfernen`, downloadOnly: 'Herunterladen',
        notShown: 'Dieser Beleg kann hier nicht angezeigt werden. Du kannst ihn herunterladen.',
        notReceipt: (n: string) => `Nur Bilder und PDFs können Belege sein, nicht hinzugefügt: ${n}.`,
        word: /beleg/i,
      },
    },
  },
} as const

export type Labels = (typeof LABELS)[Lang]

// --- the vector -----------------------------------------------------------------------------------------------------------

type Money = { participant: string; amount: number }
type VectorSplit =
  | { mode: 'equal'; participants: string[] }
  | { mode: 'shares'; shares: { participant: string; shares: number }[] }
  | { mode: 'percent'; percents: { participant: string; percent: number }[] }
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
    allocation?: { items: { label: string; amount: number; split: VectorSplit }[]; rest?: VectorSplit }
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

/** The Pot create dialog's people, each typed into its one people field (the layer's tags input) and added by its
 *  "as a name" suggestion, in order; the field then holds exactly [names] as chips. */
export async function addPotNames(page: Page, L: Labels, dialog: Locator, names: readonly string[]): Promise<void> {
  const field = dialog.getByRole('combobox', { name: L.pot.tabs.people, exact: true })
  for (const name of names) {
    await field.fill(recordTyped(name))
    await page.getByRole('option', { name: L.pot.addAsName(name), exact: true }).click()
    await expect(field).toHaveValue('')
  }
  await expect(dialog.locator('.veyq-tags-input__tag')).toHaveText([...names])
}

/** The open entry dialog: the editor of a new entry, or of the one opened. */
export const entryDialog = (page: Page, header: string) => page.getByRole('dialog', { name: header, exact: true })

/**
 * Type [entry] of the vector into a new entry and save it: its kind, title, currency and typed rate, category, payers (or
 * receivers) each with their amount — the entry's amount is their sum, never typed — and its items and rest, each with its
 * split; a transfer's amount and two ends — every field as a person sets it in the editor. [names]
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
  if (b.kind === 'transfer') {
    await dialog.getByLabel(L.pot.amount, { exact: true }).fill(typed(b.amount, b.minorUnits, lang))
    await pick(page, dialog.getByRole('combobox', { name: L.pot.from }), name(b.from!))
    await pick(page, dialog.getByRole('combobox', { name: L.pot.to }), name(b.to!))
  } else {
    await pick(page, dialog.getByRole('combobox', { name: L.pot.category_ }), L.pot.category[b.category!]!)
    const payers = (b.kind === 'expense' ? b.payers : b.receivers)!
    for (let i = 1; i < payers.length; i++) await dialog.getByRole('button', { name: L.pot.addPayer, exact: true }).click()
    const rows = dialog.getByRole('combobox', { name: L.pot.person, exact: true })
    await expect(rows).toHaveCount(payers.length)
    for (const [i, p] of payers.entries()) await pick(page, rows.nth(i), name(p.participant))
    const amountOf = b.kind === 'expense' ? L.pot.payerAmount : L.pot.receiverAmount
    for (const p of payers) await dialog.getByRole('textbox', { name: amountOf(name(p.participant)), exact: true }).fill(typed(p.amount, b.minorUnits, lang))
    await allocate(dialog, L, lang, b, names)
  }
  await dialog.getByRole('button', { name: L.save, exact: true }).click()
  await expect(dialog).toBeHidden({ timeout: 30_000 })
  await expect(entryRow(page, title)).toHaveCount(1)
}

/** The entry's items, each with its label, amount and split, then the rest's split — or, with no rest, no rest item. */
async function allocate(dialog: Locator, L: Labels, lang: Lang, b: VectorEntry['body'], names: Map<string, string>): Promise<void> {
  const a = b.allocation!
  for (const [i, item] of a.items.entries()) {
    await dialog.getByRole('button', { name: L.pot.addItem, exact: true }).click()
    const row = dialog.locator('.expenses-item:not(.expenses-rest)').nth(i)
    await row.getByRole('textbox', { name: L.pot.item, exact: true }).fill(recordTyped(itemLabel(item.label)))
    await row.getByRole('textbox', { name: L.pot.itemAmount, exact: true }).fill(typed(item.amount, b.minorUnits, lang))
    await split(row, L, lang, item.split, names)
  }
  const rest = dialog.locator('.expenses-rest')
  if (a.rest) await split(rest, L, lang, a.rest, names)
  else await expect(rest, 'the items fill the amount: no rest').toHaveCount(0)
}

/** One split in [scope] (an item or the rest): its mode, then who is on it — a checkbox each, or a field each. */
async function split(scope: Locator, L: Labels, lang: Lang, s: VectorSplit, names: Map<string, string>): Promise<void> {
  if (s.mode !== 'equal') await scope.getByRole('button', { name: L.pot.split[s.mode], exact: true }).click()
  await expect(scope.getByRole('button', { name: L.pot.split[s.mode], exact: true })).toHaveAttribute('aria-pressed', 'true')
  const person = (id: string) => names.get(id)!
  if (s.mode === 'equal') {
    const sharing = new Set(s.participants.map(person))
    for (const n of names.values()) await scope.getByRole('checkbox', { name: n, exact: true }).setChecked(sharing.has(n))
  } else if (s.mode === 'shares') {
    for (const x of s.shares) await scope.getByRole('textbox', { name: L.pot.sharesOf(person(x.participant)), exact: true }).fill(String(x.shares))
  } else {
    for (const x of s.percents) await scope.getByRole('textbox', { name: L.pot.percentOf(person(x.participant)), exact: true }).fill(typed(x.percent, 2, lang))
  }
}

/** An item's label as typed: the vector's word and a spelling the sweep cannot find in ciphertext by chance. */
export const itemLabel = (label: string) => `${label} am Tisch`

// --- receipts -------------------------------------------------------------------------------------------------------------

/** A file a receipt pass picks: its name, its type as the browser would report it, its bytes. */
export type PickedFile = { name: string; mimeType: string; buffer: Buffer }

/** Pick [files] in an open entry dialog's receipt picker (a hidden file input, opened by "Add receipt"). */
export async function addReceipts(editor: Locator, files: PickedFile[]): Promise<void> {
  await editor.locator('.expenses-receipts input[type="file"]').setInputFiles(files)
}

/** One receipt's row in an open entry dialog, by the name it shows. */
export const receiptRow = (editor: Locator, name: string): Locator => editor.locator('.expenses-receipt').filter({ hasText: name })

/**
 * Open the receipt [name] in [editor] and prove what it is drawn as: an `image` decoded by the browser (a real, non-empty
 * bitmap), a `pdf` page drawn by core's renderer, or `other` — no image, no page, only the download offered. Closes it again.
 */
export async function expectReceiptOpens(editor: Locator, L: Labels, name: string, as: 'image' | 'pdf' | 'other'): Promise<void> {
  const row = receiptRow(editor, name)
  await row.getByRole('button', { name: L.pot.receipts.open(name), exact: true }).click()
  const view = row.locator('.expenses-receipt-view')
  if (as === 'image') {
    const img = view.locator('img.expenses-receipt-image')
    await expect(img).toBeVisible({ timeout: 60_000 })
    await expect.poll(() => img.evaluate((el) => (el as HTMLImageElement).naturalWidth), { timeout: 30_000 }).toBeGreaterThan(0)
  } else if (as === 'pdf') {
    await expect(view.locator('.pdf-page canvas').first()).toBeVisible({ timeout: 60_000 })
    await expect(view.locator('img')).toHaveCount(0)
  } else {
    await expect(view.getByText(L.pot.receipts.notShown)).toBeVisible({ timeout: 60_000 })
    await expect(view.getByRole('button', { name: L.pot.receipts.downloadOnly, exact: true })).toBeVisible()
    await expect(view.locator('img, canvas')).toHaveCount(0)
  }
  await row.getByRole('button', { name: L.pot.receipts.close(name), exact: true }).click()
  await expect(view).toHaveCount(0)
}

