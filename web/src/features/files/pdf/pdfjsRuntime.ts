// pdf.js itself - the ONE module that imports it, and so the root of the lazy chunk: `pdfEngine.ts` reaches this file only
// through a dynamic `import()`, so neither pdf.js (~0.5 MB) nor its worker (~1.3 MB) is in the entry bundle or in any page
// that never opens a PDF (`npm run verify:pdf-worker` pins both). Do not import this file statically from anywhere.
//
// The LEGACY build on purpose: the modern one assumes very recent engine features (`Map.getOrInsertComputed`,
// `Promise.try`, `Float16Array` ...) that a self-hosted family's older phone or tablet may lack; the legacy build carries its
// own polyfills and the same code. Its worker is bundled by Vite as a hashed same-origin ASSET (`?url`), which is what
// `worker-src 'self'` admits - pdf.js starts it with `new Worker(url, { type: 'module' })` itself. `vite.config.ts` gives
// it a `.js` name, so the backend's static handler serves a script MIME type for it (a module worker refuses any other).
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs'
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'
import { pdfDataBase, pdfOpenParameters } from './pdfOptions'

// In a browser the worker is that asset; in node (the real-library test) pdf.js runs its "fake worker" from its own file.
if (typeof Worker !== 'undefined') pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

/** The directory of the same-origin CMaps, standard fonts and ICC profiles the build emitted; none outside a browser page. */
const dataBase = typeof document === 'undefined' ? null : pdfDataBase(import.meta.env.BASE_URL, __PDFJS_VERSION__)

export const getDocument = (data: Uint8Array) => pdfjs.getDocument(pdfOpenParameters(data, dataBase))

/** pdf.js' text layer (transparent, positioned text over a page's canvas so it can be selected and copied; no links, no events). */
export const TextLayer = pdfjs.TextLayer
