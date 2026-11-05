// A map's attribution line, built from nodes. Leaflet's own control joins its line into a string and writes it with
// `innerHTML` (Control.Attribution `_update`), which the page's Trusted Types enforcement refuses (ADR 0117), so both maps
// switch it off (`attributionControl: false`) and add this one: the same line in the same place and classes - Leaflet's
// link with its flag, a separator, the tile provider's attribution - with the Server Owner's text as TEXT, never markup.
import L from 'leaflet'

const SVG = 'http://www.w3.org/2000/svg'
// Leaflet's flag, as its `ukrainianFlag` draws it (leaflet/src/control/Control.Attribution.js).
const FLAG_STRIPES: ReadonlyArray<readonly [fill: string, d: string]> = [
  ['#4C7BE1', 'M0 0h12v4H0z'],
  ['#FFD500', 'M0 4h12v3H0z'],
  ['#E0BC00', 'M0 7h12v1H0z'],
]

function flag(): SVGSVGElement {
  const svg = document.createElementNS(SVG, 'svg')
  for (const [name, value] of [['aria-hidden', 'true'], ['width', '12'], ['height', '8'], ['viewBox', '0 0 12 8'], ['class', 'leaflet-attribution-flag']])
    svg.setAttribute(name, value)
  for (const [fill, d] of FLAG_STRIPES) {
    const path = document.createElementNS(SVG, 'path')
    path.setAttribute('fill', fill)
    path.setAttribute('d', d)
    svg.appendChild(path)
  }
  return svg
}

/** The line: Leaflet's link, then `|` and [text] where there is one. */
export function attributionLine(text: string): HTMLElement {
  const box = document.createElement('div')
  box.className = 'leaflet-control-attribution'
  const link = document.createElement('a')
  link.href = 'https://leafletjs.com'
  link.title = 'A JavaScript library for interactive maps'
  link.append(flag(), ' Leaflet')
  box.append(link)
  if (text) {
    const separator = document.createElement('span')
    separator.setAttribute('aria-hidden', 'true')
    separator.textContent = '|'
    box.append(' ', separator, ' ', text)
  }
  return box
}

/** Put [text]'s attribution line into [map]'s bottom-right corner, where Leaflet's own control sits. */
export function addAttribution(map: L.Map, text: string): void {
  const control = new L.Control({ position: 'bottomright' })
  control.onAdd = () => {
    const line = attributionLine(text)
    L.DomEvent.disableClickPropagation(line)
    return line
  }
  control.addTo(map)
}
