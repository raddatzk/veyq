/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

/** The installed pdfjs-dist version, injected by vite.config.ts (names the directory of its runtime data). */
declare const __PDFJS_VERSION__: string
