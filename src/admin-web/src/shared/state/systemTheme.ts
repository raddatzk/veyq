// The admin surface follows the operating system's colour scheme, and nothing else decides it.
//
// A PORT of web's `useThemeState.ts` (ADR 0065 — a copy, never an import), reduced to the half this surface
// can use: web offers system / light / dark because it has a settings screen to drive the choice and a place
// to persist it; this app has three screens and none of them is settings, so there is no preference, no
// localStorage key and no switcher — only the media query.
//
// Both halves of the port are load-bearing. `.veyq-dark` on <html> is what `styles/dark.css` wraps every rule
// in AND what PrimeVue's `darkModeSelector` keys off (`main.ts`), so without this module both were configured
// and unreachable: the surface stayed light on a dark-preferring OS. The favicon is the same `data:` SVG swap
// web does — allowed here because `vite.config.ts`'s CSP carries `img-src 'self' data:`, one policy string
// serving both the dev header and the built <meta>.
//
// Web also writes `.veyq-light` and `data-theme`; nothing in either package reads them, so they did not travel.
//
// Keep this module reachable from `main.ts` only. It imports SVGs with Vite's `?raw`, which the CLI's bare
// esbuild bundle (`npm run build:cli`) cannot resolve — that bundle entering here would be a build failure,
// which is exactly why `build:cli` sits inside `npm run check`.
import logoDarkSvg from '../../assets/brand/logo-dark.svg?raw'
import logoLightSvg from '../../assets/brand/logo-light.svg?raw'

const FAVICON_ID = 'veyq-favicon'
const FAVICON_LIGHT_HREF = svgDataUrl(logoLightSvg)
const FAVICON_DARK_HREF = svgDataUrl(logoDarkSvg)

/** Apply the OS colour scheme and keep following it. Call once at startup, before mount — a theme applied
 *  after the first paint is a flash of the wrong one. */
export function initializeTheme(): void {
  const query = typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null
  query?.addEventListener('change', (event) => applyTheme(event.matches))
  applyTheme(query?.matches ?? false)
}

function applyTheme(dark: boolean): void {
  if (typeof document === 'undefined') return
  document.documentElement.classList.toggle('veyq-dark', dark)
  applyFavicon(dark)
}

function applyFavicon(dark: boolean): void {
  document.getElementById(FAVICON_ID)?.remove()
  const link = document.createElement('link')
  link.id = FAVICON_ID
  link.rel = 'icon'
  link.type = 'image/svg+xml'
  link.href = dark ? FAVICON_DARK_HREF : FAVICON_LIGHT_HREF
  document.head.append(link)
}

function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}
