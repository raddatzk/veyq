import { defineConfig } from 'vitest/config'

// Vitest prints "Sourcemap … points to missing source files" once per module: the compiled core's maps name the Kotlin
// sources of the standard library and its libraries, which are not on disk. Harmless; the core's own `.kt` files resolve.
// Node, not a DOM: the compiled core reaches the platform only through @noble/* and `String.prototype.normalize`.
export default defineConfig({
  test: { environment: 'node' },
})
