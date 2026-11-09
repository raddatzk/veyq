import { defaultExclude, defineConfig } from 'vitest/config'

// The package runs its own suite: `v1.conformance.test.ts` is the KAT battery that pins this code to
// `contracts/crypto/vectors/v1.json`, and it must stay green wherever the code lives. happy-dom supplies the
// `btoa`/`atob` and `crypto` globals the browser-targeted suite assumes.
export default defineConfig({
  test: {
    environment: 'happy-dom',
    // `.stryker-tmp/` is out of the collection: an `inPlace` Stryker run (ADR 0045) keeps its backup of every file it
    // rewrote there, test files included, and vitest's default exclude does not cover it — so the backup's copies ran
    // too, and in src/web/ a copy failed the dry run on a fixture the backup does not hold (Stryker 10, measured
    // 2026-11-07: 1,753 files in that backup).
    exclude: [...defaultExclude, '**/.stryker-tmp/**'],
  },
})
