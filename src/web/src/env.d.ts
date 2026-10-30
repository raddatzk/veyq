/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

/** ExifReader's ES module source, which `shared/image/exifTags.ts` imports (its header says why): the package publishes
 *  types for its main entry only, and they describe the same `load`. */
declare module 'exifreader/src/exif-reader.js' {
  export * from 'exifreader'
}

/** The installed pdfjs-dist version, injected by vite.config.ts (names the directory of its runtime data). */
declare const __PDFJS_VERSION__: string

/** The platform's one major, read from the repository's VERSION by vite.config.ts (ADR 0085 decision 1). */
declare const __VEYQ_MAJOR__: number
