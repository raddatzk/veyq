// Expenses' message catalog (ADR 0079, inbox #513): its own top-level namespace, `expenses`, in EN and DE. Core never
// imports it (`scripts/validate_web_core_imports_no_applications.py`); the composition root's catalog half
// (`src/web/src/applicationMessages.ts`) adds it, at build time, to the one vue-i18n instance core creates
// (`src/web/src/i18n`), which refuses a namespace someone already holds. This file names only its own namespace (the
// same guard), and `const de: typeof en` holds DE to EN's keys, as core's catalog does. The product words are ADR 0108's:
// *Pot* / *Kasse*, never group or tab.
const en = {
  expenses: {
    // The default categories (docs/apps/expenses.md § The Pot), keyed by the stable id an entry seals (`DEFAULT_CATEGORIES`,
    // held equal by domain/format.test.ts) and looked up by `domain/categories.ts`; a Pot's own carry their names in its
    // sealed meta.
    category: {
      food: 'Food & drink',
      groceries: 'Groceries',
      accommodation: 'Accommodation',
      transport: 'Transport',
      leisure: 'Leisure',
      household: 'Household',
      other: 'Other',
    },
  },
}

const de: typeof en = {
  expenses: {
    category: {
      food: 'Essen & Trinken',
      groceries: 'Einkäufe',
      accommodation: 'Unterkunft',
      transport: 'Transport',
      leisure: 'Freizeit',
      household: 'Haushalt',
      other: 'Sonstiges',
    },
  },
}

export const expensesMessages = { en, de }
