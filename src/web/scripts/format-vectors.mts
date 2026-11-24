// Writes src/mobile/words/format-vectors.json from the web's own formatters (ADR 0154 decision 5): the sizes, days,
// moments, a Memory's days and ranges, lists and track lengths the apps' core (`words/Shown.kt`,
// `ShownVectorsTest`) must write exactly as the web does. `pnpm exec tsx scripts/format-vectors.mts` from src/web, after a
// web formatter changed on purpose; `src/test/formatVectors.test.ts` is red until the file is written anew. The
// output depends on the ICU of the Node that runs it (Intl's month names and spaces): the file names it. Money and numbers
// are the core's own since ADR 0155 moved them (`src/mobile/words/money-vectors.json`, written by `ShownVectorsTest`).
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { formatVectors } from '../src/test/formatVectors.ts'

const target = fileURLToPath(new URL('../../mobile/words/format-vectors.json', import.meta.url))
const file = {
  '//': 'Written by src/web/scripts/format-vectors.mts from the web\'s formatters (ADR 0154 decision 5); never by hand. '
    + 'src/web\'s formatVectors.test.ts re-renders it; the core\'s ShownVectorsTest holds words/Shown.kt to it.',
  icu: process.versions.icu,
  ...formatVectors(),
}
writeFileSync(target, `${JSON.stringify(file, null, 1)}\n`)
console.log(`wrote ${target}`)
