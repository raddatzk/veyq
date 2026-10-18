// What Vite gives the web client and tsx does not, for the volume harness (cr-volume-measurement-local), which loads the
// client's own modules under Node. Imported FIRST by `seed-volume.mts` and `measure-volume.mts`: ESM evaluates imports
// in order, and `shared/state/capabilities.ts` reads the define at module load. Temporary, with the rest of the harness.
import { readFileSync } from 'node:fs'

const major = readFileSync(new URL('../../VERSION', import.meta.url), 'utf8').trim()
if (!/^[0-9]+$/.test(major)) throw new Error(`VERSION must hold the major alone, got: ${major}`)
// vite.config.ts's `define` (platformMajor), the same value read the same way.
;(globalThis as { __VEYQ_MAJOR__?: number }).__VEYQ_MAJOR__ = Number(major)
