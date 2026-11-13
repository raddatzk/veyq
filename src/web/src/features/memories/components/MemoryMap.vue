<script setup lang="ts">
// A thin Leaflet slippy-map wrapper honoring the ADR 0018 tile seam: a raster `TileLayer` from a
// provider-neutral, **third-party** tile URL (client-direct — never the veyq backend/operator), with mandatory
// attribution. It is presentation only — no crypto, no consent logic (the parent gates it behind `mapsAvailable`), except
// that it carries the map's "Turn map services off" (`MapOffButton`, uc.memory.place A5): withdrawing flips `mapsAvailable`, the
// parent unmounts this component and `onBeforeUnmount` removes the Leaflet map with its tile layer.
//
// Interaction is **frame-based** (cr-0069): the user pans/zooms to frame a spot; the map reports its centre+zoom
// via `frame` (→ the geocode `nameAnchor`, and, for a `fixed` place, the position itself). A centre **crosshair**
// (`crosshair`) marks the framed point; an optional static **`marker`** shows a data-derived position (the medoid
// of an `auto` place). There is no click-to-drop / drag — the viewport centre IS the pin.
//
// A Memory's Map tab (uc.memory.tracks, ADR 0130 decision 4) hands it `tracks` too: each a line per segment in its colour,
// a start and an end marker, its name as a tooltip built from a text node (Trusted Types: never a string of markup). A
// click on a line asks for that track (`selectTrack`); the selected one (`selectedTrack`) is drawn heavier.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { LatLng } from '../memoryLocation'

/** One track as the map draws it: its lines (each already simplified), its colour and the words of its tooltips. */
export type MapTrack = { id: string; color: string; lines: LatLng[][]; label: string; startLabel: string; endLabel: string }
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import MapOffButton from './MapOffButton.vue'
import { createIconElement } from '../../../shared/ui/iconElement'
import { addAttribution } from '../mapAttribution'

const props = defineProps<{
  tileUrl: string
  attribution: string
  maxZoom: number
  center: LatLng
  zoom: number
  marker?: LatLng | null // a static data-derived pin (the medoid of an `auto` place); no marker for `fixed`
  crosshair?: boolean // show a centre crosshair — the framed point that is the `fixed` position
  // Draw the crosshair greyed out: the framed point is no position yet (uc.memory.place E3). The parent passes its own
  // "no point set" answer, the one its pill reads - never a second notion of whether the member moved the map.
  crosshairUnset?: boolean
  dots?: LatLng[] // faint context markers (the Memory's photo GPS) — "your photos are here"
  tracks?: MapTrack[] // the Memory's shown GPX tracks (uc.memory.tracks)
  selectedTrack?: string | null
}>()
// `user` marks a frame the MEMBER produced - a pan or a zoom by hand - vs the opening view and every programmatic move
// (`setView`, `panTo`, `fitPoints`). The parent uses it to flip an `auto` place to `fixed` the moment the member reframes it,
// and to set no point at all from a map the member never moved (uc.memory.place E3, inbox #834).
const emit = defineEmits<{ frame: [{ lat: number; lng: number; zoom: number; user: boolean }]; selectTrack: [id: string] }>()

const el = ref<HTMLElement | null>(null)
let map: L.Map | null = null
let pin: L.Marker | null = null
let dotLayer: L.LayerGroup | null = null
let trackLayer: L.LayerGroup | null = null
// Set by the member's input on the map (below), cleared once the next frame is reported and before every programmatic move.
// Leaflet tells a user pan by its `dragstart` but has no event that tells a member's zoom (wheel, double click, the zoom
// buttons, keys, a pinch) from a programmatic one, so the member's input itself is the signal: each of those gestures
// begins with a `pointerdown`, a `wheel` or a `keydown` inside the map's container, caught on the way down (capture), as
// the zoom buttons stop their events from bubbling.
let userInput = false
const USER_INPUT = ['pointerdown', 'wheel', 'keydown'] as const
const markUserInput = () => (userInput = true)

// A CSS pin (divIcon) instead of Leaflet's default PNG marker — avoids bundler image-path breakage.
const pinIcon = L.divIcon({ className: 'veyq-map-pin', html: createIconElement('map-marker'), iconSize: [30, 30], iconAnchor: [15, 30] })

function syncPin(at: LatLng | null | undefined) {
  if (!map) return
  if (at) {
    if (!pin) pin = L.marker([at.lat, at.lng], { icon: pinIcon, interactive: false, keyboard: false }).addTo(map)
    else pin.setLatLng([at.lat, at.lng])
  } else if (pin) {
    map.removeLayer(pin)
    pin = null
  }
}

function syncDots(points: LatLng[] | undefined) {
  if (!map) return
  if (!dotLayer) dotLayer = L.layerGroup().addTo(map)
  dotLayer.clearLayers()
  for (const p of points ?? []) {
    L.circleMarker([p.lat, p.lng], {
      radius: 4, weight: 0, fillColor: '#6d5ae6', fillOpacity: 0.5, interactive: false,
    }).addTo(dotLayer)
  }
}

/** A tooltip's content as a text node: Leaflet writes a string with `innerHTML`, which Trusted Types refuses. */
function tooltipText(text: string): HTMLElement {
  const span = document.createElement('span')
  span.textContent = text
  return span
}

// The ends of a track: a ring in green where it starts and in red where it ends, as the old application drew them.
const START_COLOR = '#15803d'
const END_COLOR = '#b91c1c'

function syncTracks(tracks: MapTrack[] | undefined, selected: string | null | undefined) {
  if (!map) return
  if (!trackLayer) trackLayer = L.layerGroup().addTo(map)
  trackLayer.clearLayers()
  for (const track of tracks ?? []) {
    const isSelected = track.id === selected
    for (const line of track.lines) {
      if (line.length < 2) continue
      L.polyline(line.map((p) => [p.lat, p.lng] as [number, number]), {
        color: track.color, weight: isSelected ? 6 : 4, opacity: isSelected ? 1 : 0.85,
      })
        .bindTooltip(tooltipText(track.label), { sticky: true })
        .on('click', () => emit('selectTrack', track.id))
        .addTo(trackLayer)
    }
    const first = track.lines.find((l) => l.length > 0)?.[0]
    const lastLine = [...track.lines].reverse().find((l) => l.length > 0)
    const last = lastLine?.[lastLine.length - 1]
    const end = (at: LatLng, color: string, label: string) =>
      L.circleMarker([at.lat, at.lng], { radius: 6, weight: 3, color, fillColor: '#ffffff', fillOpacity: 1 })
        .bindTooltip(tooltipText(label))
        .addTo(trackLayer!)
    if (first) end(first, START_COLOR, track.startLabel)
    if (last) end(last, END_COLOR, track.endLabel)
  }
}

function reportFrame() {
  if (!map) return
  const c = map.getCenter()
  emit('frame', { lat: c.lat, lng: c.lng, zoom: map.getZoom(), user: userInput })
  userInput = false
}

onMounted(() => {
  if (!el.value) return
  // Leaflet's attribution control writes its line as a string of markup, which Trusted Types refuses: ours builds it.
  map = L.map(el.value, { attributionControl: false }).setView([props.center.lat, props.center.lng], props.zoom)
  L.tileLayer(props.tileUrl, { maxZoom: props.maxZoom }).addTo(map)
  addAttribution(map, props.attribution)
  syncDots(props.dots)
  syncTracks(props.tracks, props.selectedTrack)
  syncPin(props.marker)
  for (const ev of USER_INPUT) el.value.addEventListener(ev, markUserInput, { capture: true, passive: true })
  map.on('moveend', reportFrame)
  map.whenReady(reportFrame) // the opening frame (`user: false`): the parent may name a position it opened on, never set one
})

// Keep the static pin + photo dots in sync when the parent recomputes them (e.g. auto re-medoid / mode toggle).
watch(() => props.marker, (next) => syncPin(next))
watch(() => props.dots, (next) => syncDots(next))
watch([() => props.tracks, () => props.selectedTrack] as const, ([tracks, selected]) => syncTracks(tracks, selected))

onBeforeUnmount(() => {
  for (const ev of USER_INPUT) el.value?.removeEventListener(ev, markUserInput, { capture: true })
  map?.remove()
  map = null
  pin = null
  dotLayer = null
  trackLayer = null
})

defineExpose({
  /** Recenter the map on a point (used when the position jumps, e.g. a re-medoid). */
  panTo(at: LatLng) {
    userInput = false
    map?.panTo([at.lat, at.lng])
  },
  /** Programmatically set the view (centre + zoom) — a non-user move, so it won't flip `auto` → `fixed`. */
  setView(at: LatLng, zoom: number) {
    userInput = false
    map?.setView([at.lat, at.lng], zoom)
  },
  /** Frame the view to fit all points — the **auto** view, its zoom reflecting how spread the photos are. */
  fitPoints(points: LatLng[]) {
    if (!map || points.length === 0) return
    userInput = false
    if (points.length === 1) map.setView([points[0].lat, points[0].lng], 14)
    else map.fitBounds(points.map((p) => [p.lat, p.lng] as [number, number]), { padding: [30, 30], maxZoom: 16 })
  },
})
</script>

<template>
  <div class="memory-map-wrap">
    <div ref="el" class="memory-map" role="application" />
    <!-- centre crosshair: the framed point (the `fixed` position + the naming anchor) -->
    <VeyqIcon v-if="crosshair" name="map-marker" class="memory-map-crosshair" :class="{ 'is-unset': crosshairUnset }" />
    <MapOffButton />
  </div>
</template>

<style scoped>
.memory-map-wrap {
  position: relative;
}
.memory-map {
  width: 100%;
  height: 320px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--app-line);
}
.memory-map-crosshair {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -100%); /* the pin tip sits on the exact centre */
  font-size: 1.9rem;
  color: var(--app-accent);
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.45));
  pointer-events: none;
  z-index: 500;
}
/* No point set yet (uc.memory.place E3): the pin is greyed out until the member moves the map. Tokens only, so the dark
   mode follows `:root.veyq-dark`'s twins with no rule of its own; a greyed pin marks an inactive state, like a disabled
   control, and is not held to 3:1. */
.memory-map-crosshair.is-unset {
  color: var(--app-muted);
  opacity: 0.6;
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.25));
}
@media (prefers-reduced-motion: no-preference) {
  .memory-map-crosshair {
    transition: color 0.2s ease, opacity 0.2s ease;
  }
}
:deep(.veyq-map-pin) {
  color: var(--app-primary);
  font-size: 1.75rem;
  line-height: 1;
  text-align: center;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.4));
}
</style>
