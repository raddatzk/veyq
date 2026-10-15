/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

/** The installed pdfjs-dist version, injected by vite.config.ts (names the directory of its runtime data). */
declare const __PDFJS_VERSION__: string

/** The release this build was made for (`VEYQ_RELEASE`, else `dev`), injected by vite.config.ts; named in an export's manifest. */
declare const __APP_VERSION__: string

/** The platform's one major, read from the repository's VERSION by vite.config.ts (ADR 0085 decision 1). */
declare const __VEYQ_MAJOR__: number
