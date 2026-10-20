// What Vite gives the web client and tsx does not, for a seeder that loads the client's own modules under Node
// (`fuzz-seed.mts`). Imported FIRST: ESM evaluates imports in order, and `shared/state/capabilities.ts` reads the define
// at module load. The volume harness carries its own copy (`volumePrelude.mts`), deleted with that harness.
import { readFileSync } from 'node:fs'

const major = readFileSync(new URL('../../../VERSION', import.meta.url), 'utf8').trim()
if (!/^[0-9]+$/.test(major)) throw new Error(`VERSION must hold the major alone, got: ${major}`)
// vite.config.ts's `define` (platformMajor), the same value read the same way.
;(globalThis as { __VEYQ_MAJOR__?: number }).__VEYQ_MAJOR__ = Number(major)
