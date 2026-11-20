import { defineConfig } from 'vitest/config'

// Node, not a DOM: the compiled core reaches the platform only through @noble/* and `String.prototype.normalize`.
export default defineConfig({
  test: { environment: 'node' },
})
