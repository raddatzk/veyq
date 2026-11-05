<script setup lang="ts">
// A thin Leaflet slippy-map wrapper honoring the ADR 0018 tile seam: a raster `TileLayer` from a
// provider-neutral, **third-party** tile URL (client-direct — never the veyq backend/operator), with mandatory
// attribution. It is presentation only — no crypto, no consent logic (the parent gates it behind `mapsAvailable`), except
// that it carries the map's "Turn map off" (`MapOffButton`, uc.memory.place A5): withdrawing flips `mapsAvailable`, the
// parent unmounts this component and `onBeforeUnmount` removes the Leaflet map with its tile layer.
//
// Interaction is **frame-based** (cr-0069): the user pans/zooms to frame a spot; the map reports its centre+zoom
// via `frame` (→ the geocode `nameAnchor`, and, for a `fixed` place, the position itself). A centre **crosshair**
// (`crosshair`) marks the framed point; an optional static **`marker`** shows a data-derived position (the medoid
// of an `auto` place). There is no click-to-drop / drag — the viewport centre IS the pin.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { LatLng } from '../memoryLocation'
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
  dots?: LatLng[] // faint context markers (the Memory's photo GPS) — "your photos are here"
}>()
// `user` marks a frame that a **user drag** produced (vs a programmatic recenter) — the parent uses it to flip an
// `auto` place to `fixed` the moment the user reframes it by hand.
const emit = defineEmits<{ frame: [{ lat: number; lng: number; zoom: number; user: boolean }] }>()

const el = ref<HTMLElement | null>(null)
let map: L.Map | null = null
let pin: L.Marker | null = null
let dotLayer: L.LayerGroup | null = null
let userDragged = false // set by Leaflet's `dragstart` (user pan only), cleared after the next frame is reported

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

function reportFrame() {
  if (!map) return
  const c = map.getCenter()
  emit('frame', { lat: c.lat, lng: c.lng, zoom: map.getZoom(), user: userDragged })
  userDragged = false
}

onMounted(() => {
  if (!el.value) return
  // Leaflet's attribution control writes its line as a string of markup, which Trusted Types refuses: ours builds it.
  map = L.map(el.value, { attributionControl: false }).setView([props.center.lat, props.center.lng], props.zoom)
  L.tileLayer(props.tileUrl, { maxZoom: props.maxZoom }).addTo(map)
  addAttribution(map, props.attribution)
  syncDots(props.dots)
  syncPin(props.marker)
  map.on('dragstart', () => (userDragged = true)) // fires only on a real user pan (not programmatic recenters)
  map.on('moveend', reportFrame)
  map.whenReady(reportFrame) // an initial frame so the parent can geocode the opening view
})

// Keep the static pin + photo dots in sync when the parent recomputes them (e.g. auto re-medoid / mode toggle).
watch(() => props.marker, (next) => syncPin(next))
watch(() => props.dots, (next) => syncDots(next))

onBeforeUnmount(() => {
  map?.remove()
  map = null
  pin = null
  dotLayer = null
})

defineExpose({
  /** Recenter the map on a point (used when the position jumps, e.g. a re-medoid). */
  panTo(at: LatLng) {
    map?.panTo([at.lat, at.lng])
  },
  /** Programmatically set the view (centre + zoom) — a non-user move, so it won't flip `auto` → `fixed`. */
  setView(at: LatLng, zoom: number) {
    map?.setView([at.lat, at.lng], zoom)
  },
  /** Frame the view to fit all points — the **auto** view, its zoom reflecting how spread the photos are. */
  fitPoints(points: LatLng[]) {
    if (!map || points.length === 0) return
    if (points.length === 1) map.setView([points[0].lat, points[0].lng], 14)
    else map.fitBounds(points.map((p) => [p.lat, p.lng] as [number, number]), { padding: [30, 30], maxZoom: 16 })
  },
})
</script>

<template>
  <div class="memory-map-wrap">
    <div ref="el" class="memory-map" role="application" />
    <!-- centre crosshair: the framed point (the `fixed` position + the naming anchor) -->
    <VeyqIcon v-if="crosshair" name="map-marker" class="memory-map-crosshair" />
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
:deep(.veyq-map-pin) {
  color: var(--app-primary);
  font-size: 1.75rem;
  line-height: 1;
  text-align: center;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.4));
}
</style>
