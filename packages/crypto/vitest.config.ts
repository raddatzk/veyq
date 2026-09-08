import { defineConfig } from 'vitest/config'

// The package runs its own suite: `v1.conformance.test.ts` is the KAT battery that pins this code to
// `contracts/crypto/vectors/v1.json`, and it must stay green wherever the code lives. happy-dom supplies the
// `btoa`/`atob` and `crypto` globals the browser-targeted suite assumes.
export default defineConfig({
  test: {
    environment: 'happy-dom',
  },
})
