# Cross-cutting: German inclusive language (neutral-first)

- **ID:** cc.inclusive-language
- **Applies to:** every **German user-facing** string — the `de` blocks of web's catalogs (core's `src/web/src/i18n/index.ts` and each application's
  `src/web/src/features/<owner>/i18n.ts`), the `de` block of admin-web's (`src/admin-web/src/i18n/index.ts`) and the
  German pages of the docs site (`site/src/content/docs/de/`). **English strings and code/identifiers are unaffected** — this is a
  language convention for the German product voice only.

## The rule — neutral-first

German user-facing text must **not** use the **generic masculine** (or generic feminine) for a person who could
be any gender. Prefer, in order:

1. **Geschlechtsneutrale Umformulierung** — rephrase so no gendered person-noun is needed. This is the default and
   almost always possible:
   - **„die Person" / „jemanden" / „das Mitglied"** — „…für die Person gespeichert, der die Erinnerung gehört".
   - **„wer …" / „alle, die …" / „andere"** — „Nur **wer** den Bereich besitzt, kann …", „für **alle, die** unsere
     Datenbank stehlen", „Was dürfen **andere**?".
   - **Plural / Partizip** — „Geduldige **Angreifende**" statt „ein geduldiger Angreifer"; „**Eingeladene
     Personen**".
   - **Rephrase away from a pronoun** when a possessive reads gendered — „…dass der Schlüssel wirklich **zu dieser
     Person gehört**" statt „…wirklich **ihrer** ist".
2. **Gender-Doppelpunkt `:innen` / `:r` / `:n`** — the accepted fallback **only** where a neutral rewrite is
   genuinely awkward. Already established in the product (`jede:r im Browser`), so stay consistent with it; do not
   introduce Binnen-I, Gendersternchen, or Unterstrich.

**Never:** „der Nutzer", „ein anderer Nutzer", „der Besitzer", „die Besitzerin", „jeder, der …", „der Betreiber"
for a generic person.

## Specific decisions (this project)

- **„Owner" is avoided entirely** in German. The role is expressed neutrally: **„wer … besitzt"**, **„die besitzende
  Person"**, **„Besitz-…"** (e.g. „Besitz-Einladung"), **„Mitbesitz"** (co-owner). (English keeps `owner` as the
  wire/role term; only the German voice avoids it.)
- **No coined person-noun for it either.** „Besitzperson" was one (24 strings, removed 2026-11-01): grammatical, and
  read as forced. Say what holds instead: „Nur **wer den Server besitzt**, kann …", „Melde dich mit dem Konto an,
  **dem dieser Server gehört**", „**alle, denen** der Tenant gehört", „Der Tenant **gehört niemandem**". Where the
  reader is the owner, address them: „Sie geht nur an **deine** Adresse".
- **„Betreiber" (operator) → „die Person, die … betreibt" / „die betreibende Person" / „wer … betreibt".**
- **„Benutzername" is kept** — it is a fixed **credential label** (mirrors EN „Username"), not a gendered
  person-reference. Do not swap it for „Nutzername" (keeps the gendered root) either; leave it.
- **Grammatical neuter agreement with „Mitglied"** („es", „das Mitglied") is fine; but a possessive that reads
  masculine („seine", „ihm") is rephrased to **„dieser Person" / „der Person"**.

## User impact

The German version of veyq speaks to and about everyone the same way, avoiding wording that assumes a gender, in
the app and on the docs site.

## Related

- Working rule: [docs/ai/README.md](../ai/README.md) § *Language* (converse in the user's language; document +
  implement in English; the docs site's Use area is the EN+DE exception).
- Built: cr-0112 (the first neutral-first sweep of the DE i18n catalog + the DE wiki pages).
