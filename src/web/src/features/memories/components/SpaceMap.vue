<script setup lang="ts">
// The read-only space map (cr-0071, uc.memory.place) — the "Karte" browse view. Drops every located node in the
// current subtree as a **clickable pin** on a Leaflet slippy map (ADR 0018 tiles, client-direct third-party —
// never the veyq operator). Operator-blind by construction: the pins come from `collectLocatedPins` unsealing the
// `wrappedMeta` that `listNodes` already returns; no coordinate ever crosses the blind server, there is no
// locations API. Presentation only — the crypto/collection lives in `spaceMap.ts`.
//
// Pins that fall too close together on screen are **clustered** into one badge (the same pixel-distance grouping
// the legacy media map used, `clusterPins`), re-clustered on every zoom/pan. Because a space-map pin is
// navigational (a click opens *one* Memory), a cluster can't just open — it **spiderfies**: on a desktop **hover**
// (or a touch **tap**) the members fan out on legs so you can pick the one you want; a leaf click opens that
// Memory. Collapse is delayed so the pointer can travel from the badge to a member without the fan snapping shut.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import { clusterPins, sortPinsByStartDate, type LocatedPin, type PinCluster } from '../spaceMap'
import { coverImageStyle, type LoadedCover } from '../memoryCover'
import { formatClosedRange, formatDay } from '../memoryDateRange'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import MapConsentPrompt from './MapConsentPrompt.vue'
import MapOffButton from './MapOffButton.vue'
import { createIconElement } from '../../../shared/ui/iconElement'
import { addAttribution } from '../mapAttribution'

// `covers` maps a node id → the node's chosen cover (a decrypted thumbnail URL + its crop, loaded by the host,
// cr-0072); a pin with one shows that cropped cover via the shared `coverImageStyle` formula, the rest fall back to
// the placeholder. Read-only here — the host owns the URLs' lifecycle.
// `headingLevel` is the level of the opt-in's and the empty state's heading: one below the head the map sits under, a
// folder's `h1` in the level view (so `h2`) or the `h2` of a person's Memories (so `h3`).
const props = withDefaults(defineProps<{ pins: LocatedPin[]; covers?: Map<string, LoadedCover>; headingLevel?: 2 | 3 }>(), {
  covers: undefined,
  headingLevel: 2,
})
const emit = defineEmits<{ open: [nodeId: string] }>()

const { t, locale } = useI18n()
const { mapsOffered, mapsAvailable, tileUrl, tileAttribution, tileMaxZoom } = useMapConfig()

// The map only renders with consent AND at least one pin — otherwise it's the opt-in prompt or the empty state.
const showMap = computed(() => mapsAvailable.value && props.pins.length > 0)

// Grouping radius (px) and the fan-out radius when a cluster spiderfies. Touch devices expand on tap, not hover.
const CLUSTER_PX = 38
const coarsePointer = typeof matchMedia !== 'undefined' && matchMedia('(hover: none)').matches

const el = ref<HTMLElement | null>(null)
let map: L.Map | null = null
let baseLayer: L.LayerGroup | null = null // cluster badges + lone pins
let spiderLayer: L.LayerGroup | null = null // the expanded fan (legs + member pins)
let activeKey: string | null = null // which cluster is currently spiderfied
let collapseTimer: ReturnType<typeof setTimeout> | null = null

/** A cluster badge showing the member count: an element, as every icon here - a string would be written as markup. */
function clusterIcon(count: number): L.DivIcon {
  const label = document.createElement('span')
  label.textContent = String(count)
  return L.divIcon({
    className: 'veyq-space-cluster',
    html: label,
    iconSize: [40, 40],
    iconAnchor: [20, 20],
  })
}

/** The Memory's date range as a label (mirrors the card): a closed range, or "since …" for an ongoing date. */
function dateLabel(pin: LocatedPin): string {
  const d = pin.date
  if (!d) return ''
  if (d.mode === 'ongoing') return d.from ? t('memory.date.since', { day: formatDay(d.from, locale.value) }) : ''
  return formatClosedRange(d.from, d.to, locale.value)
}

/** The pin face (variant B): a small always-on info card — the cover (or placeholder) beside the title + date
 *  range. Built via textContent + a background-image style so a user's name can't inject markup. */
function cardEl(pin: LocatedPin): HTMLElement {
  const card = document.createElement('div')
  card.className = 'veyq-space-card'

  const thumb = document.createElement('span')
  thumb.className = 'veyq-space-card-thumb'
  const cover = props.covers?.get(pin.node.id)
  if (cover) {
    // The chosen cover, cropped by the SAME formula every other surface uses — an `<img>` expanded + offset
    // inside the overflow-hidden thumb box (no markup from user data: only a URL + numeric styles are set).
    const img = document.createElement('img')
    img.src = cover.url
    img.draggable = false
    Object.assign(img.style, coverImageStyle(cover.crop))
    thumb.appendChild(img)
  } else {
    thumb.classList.add('veyq-space-card-thumb-ph')
    thumb.appendChild(createIconElement('images'))
  }
  card.appendChild(thumb)

  const body = document.createElement('div')
  body.className = 'veyq-space-card-body'
  const name = document.createElement('strong')
  name.textContent = pin.node.name
  body.appendChild(name)
  const dateText = dateLabel(pin)
  if (dateText) {
    const date = document.createElement('div')
    date.className = 'veyq-space-card-date'
    date.textContent = dateText
    body.appendChild(date)
  }
  card.appendChild(body)
  return card
}

// A card marker: a zero-size wrapper Leaflet positions on the exact point. `anchored` = a lone pin that truly sits
// there, so the card is lifted above the point on a **tail** pointing to it; a spiderfied member sits on a fanned
// **display** position (its real place is the cluster centre, reached by the leg), so it gets **no tail** — a
// downward tail would falsely claim that spot — and is simply centred on its fan point.
function cardIcon(pin: LocatedPin, anchored: boolean): L.DivIcon {
  const el = cardEl(pin)
  el.classList.add(anchored ? 'veyq-space-card--pin' : 'veyq-space-card--member')
  return L.divIcon({ className: 'veyq-space-cardwrap', html: el, iconSize: [0, 0], iconAnchor: [0, 0] })
}

const clusterKey = (c: PinCluster) => c.pins.map((p) => p.node.id).join('|')

const leafMarkers = new Map<string, { marker: L.Marker; pin: LocatedPin; anchored: boolean }>() // for cover upgrades

/** Add a leaf pin: a lone node (`anchored`, with a tail) or a spiderfied member (no tail). Click opens that Memory. */
function leafMarker(pin: LocatedPin, at: L.LatLngExpression, layer: L.LayerGroup, anchored: boolean) {
  const marker = L.marker(at, { icon: cardIcon(pin, anchored) })
  marker.on('click', (e) => {
    L.DomEvent.stop(e as unknown as Event) // don't let it bubble to the map (which collapses the fan)
    emit('open', pin.node.id)
  })
  if (!coarsePointer) {
    marker.on('mouseover', cancelCollapse)
    marker.on('mouseout', scheduleCollapse)
  }
  marker.addTo(layer)
  leafMarkers.set(pin.node.id, { marker, pin, anchored })
}

/** Rebuild the base layer (cluster badges + lone pins) from the current pins at the current zoom. */
function renderClusters() {
  if (!map) return
  collapse()
  if (!baseLayer) baseLayer = L.layerGroup().addTo(map)
  baseLayer.clearLayers()
  leafMarkers.clear()
  const clusters = clusterPins(props.pins, (lat, lng) => map!.latLngToLayerPoint([lat, lng]), CLUSTER_PX)
  for (const cluster of clusters) {
    if (cluster.pins.length === 1) {
      const { lat, lng } = cluster.pins[0].location.derived
      leafMarker(cluster.pins[0], [lat, lng], baseLayer, true) // a lone pin sits at its real place → keep the tail
      continue
    }
    const key = clusterKey(cluster)
    const badge = L.marker([cluster.lat, cluster.lng], {
      icon: clusterIcon(cluster.pins.length),
      title: t('memory.map.places', { count: cluster.pins.length }),
    })
    badge.on('click', (e) => {
      L.DomEvent.stop(e as unknown as Event)
      toggle(cluster, key)
    })
    if (!coarsePointer) {
      badge.on('mouseover', () => expand(cluster, key))
      badge.on('mouseout', scheduleCollapse)
    }
    badge.addTo(baseLayer)
  }
}

/** Spiderfy a cluster: fan its members onto a ring around the centroid, each on a thin leg, and open the layer. */
function expand(cluster: PinCluster, key: string) {
  if (!map) return
  cancelCollapse()
  if (activeKey === key && spiderLayer) return
  collapse()
  activeKey = key
  spiderLayer = L.layerGroup().addTo(map)
  const center = map.latLngToLayerPoint([cluster.lat, cluster.lng])
  const members = sortPinsByStartDate(cluster.pins) // fan ordered newest-first, like the card grid
  const n = members.length
  const radius = Math.min(160, 70 + n * 16) // wider than a bare pin's fan so the info cards don't stack
  members.forEach((pin, i) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * i) / n // start at 12 o'clock, go clockwise
    const at = map!.layerPointToLatLng([center.x + radius * Math.cos(angle), center.y + radius * Math.sin(angle)])
    const leg = L.polyline([[cluster.lat, cluster.lng], at], { color: 'var(--app-primary)', weight: 1.5, opacity: 0.6 })
    if (!coarsePointer) {
      leg.on('mouseover', cancelCollapse)
      leg.on('mouseout', scheduleCollapse)
    }
    leg.addTo(spiderLayer!)
    leafMarker(pin, at, spiderLayer!, false) // a fanned member sits on a display position → no (misleading) tail
  })
}

/** Touch toggle: tap a badge to fan it out, tap again (or the map) to collapse. */
function toggle(cluster: PinCluster, key: string) {
  if (activeKey === key) collapse()
  else expand(cluster, key)
}

function collapse() {
  cancelCollapse()
  spiderLayer?.remove()
  spiderLayer = null
  activeKey = null
}
function scheduleCollapse() {
  cancelCollapse()
  collapseTimer = setTimeout(collapse, 260) // long enough to travel from the badge to a member
}
function cancelCollapse() {
  if (collapseTimer) clearTimeout(collapseTimer)
  collapseTimer = null
}

/** Frame the view to fit every pin (single pin ⇒ a sensible zoom). */
function fitPins() {
  if (!map || props.pins.length === 0) return
  const latlngs = props.pins.map((p) => [p.location.derived.lat, p.location.derived.lng] as [number, number])
  if (latlngs.length === 1) map.setView(latlngs[0], 14)
  else map.fitBounds(latlngs, { padding: [50, 50], maxZoom: 16 })
}

function initMap() {
  if (map || !el.value) return
  // Leaflet's attribution control writes its line as a string of markup, which Trusted Types refuses: ours builds it.
  map = L.map(el.value, { attributionControl: false }).setView([20, 0], 2)
  L.tileLayer(tileUrl.value, { maxZoom: tileMaxZoom }).addTo(map)
  addAttribution(map, tileAttribution.value)
  map.on('zoomend moveend', renderClusters) // re-cluster + collapse when the projection changes
  map.on('click', collapse) // a tap on empty map closes an open fan (touch + desktop)
  fitPins()
  renderClusters()
}

function teardown() {
  cancelCollapse()
  map?.remove()
  map = null
  baseLayer = null
  spiderLayer = null
  activeKey = null
}

onMounted(() => {
  if (showMap.value) initMap()
})

// The map container is `v-if`-gated on `showMap`; when it flips on (consent granted / first pin) mount Leaflet on
// the freshly-rendered element, and tear it down when it flips off (consent withdrawn — the map's own "Turn map off",
// the settings, another tab — or pins emptied): `map.remove()` drops the tile layer, so no further tile is requested.
watch(showMap, async (on) => {
  if (on) {
    await nextTick()
    initMap()
  } else {
    teardown()
  }
})

// Re-frame + re-cluster when the subtree's pins change (e.g. switching folders while staying in Karte mode).
watch(
  () => props.pins,
  () => {
    if (map) {
      fitPins()
      renderClusters()
    }
  },
)

// Covers stream in asynchronously — swap each loaded pin's icon **in place** (no re-cluster) so a photo appearing
// never collapses an open fan or reshuffles the map. Deep-watched since the host mutates the Map in place.
watch(
  () => props.covers,
  () => {
    for (const [nodeId, { marker, pin, anchored }] of leafMarkers) {
      if (props.covers?.get(nodeId)) marker.setIcon(cardIcon(pin, anchored))
    }
  },
  { deep: true },
)

onBeforeUnmount(teardown)
</script>

<template>
  <div class="space-map">
    <!-- consent off: the same coupled tiles+geocoding opt-in copy as the editor, not a broken map -->
    <!-- no map service on this server (memories.map): nothing to consent to, so nothing is offered -->
    <div v-if="!mapsOffered" class="space-map-panel">
      <VeyqIcon name="map" class="space-map-panel-icon" />
      <p>{{ t('memory.place.mapsNotOffered') }}</p>
    </div>
    <MapConsentPrompt v-else-if="!mapsAvailable" :heading-level="headingLevel" />

    <!-- consented but nothing located in this subtree yet -->
    <div v-else-if="pins.length === 0" class="space-map-panel">
      <VeyqIcon name="map-marker" class="space-map-panel-icon" />
      <component :is="`h${headingLevel}`" class="space-map-panel-title">{{ t('memory.map.empty') }}</component>
      <p>{{ t('memory.map.emptyHint') }}</p>
    </div>

    <div v-else class="space-map-mapwrap">
      <div ref="el" class="space-map-canvas" role="application" :aria-label="t('memory.viewMap')" />
      <MapOffButton />
    </div>
  </div>
</template>

<style scoped>
.space-map {
  width: 100%;
}
.space-map-mapwrap {
  position: relative;
}
.space-map-canvas {
  width: 100%;
  height: min(60vh, 520px);
  border-radius: var(--app-radius);
  overflow: hidden;
  border: 1px solid var(--app-line);
}
.space-map-panel {
  display: grid;
  justify-items: center;
  gap: 0.6rem;
  text-align: center;
  padding: 2.5rem 1.5rem;
  border: 1px dashed var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-control-bg);
  color: var(--app-muted);
}
.space-map-panel-title {
  margin: 0;
  color: var(--app-text);
  font-size: 1.05rem;
}
.space-map-panel p {
  margin: 0;
  max-width: 42ch;
  font-size: 0.9rem;
  line-height: 1.5;
}
.space-map-panel-icon {
  font-size: 2rem;
  color: var(--app-accent);
}
/* A round count badge for a group of too-close pins. */
:deep(.veyq-space-cluster) {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 999px;
  background: var(--app-primary);
  color: var(--app-on-fill);
  font-weight: 800;
  font-size: 0.95rem;
  border: 3px solid #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
  cursor: pointer;
}
/* Variant B: the pin IS an always-on info card. Leaflet positions a zero-size wrapper on the exact point; the card
   is placed by CSS relative to it, so its width can grow with the title without shifting the anchor. Two modes:
   `--pin` (a lone node at its real place) lifts above the point on a tail; `--member` (a spiderfied card on a
   fanned display position) is centred on its point with NO tail — a tail there would point to a wrong spot. */
:deep(.veyq-space-cardwrap) {
  line-height: normal;
}
:deep(.veyq-space-card) {
  position: absolute;
  left: 0;
  top: 0;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.3rem 0.6rem 0.3rem 0.3rem;
  background: var(--app-surface);
  border: 1px solid var(--app-line);
  border-radius: 12px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
  white-space: nowrap;
  cursor: pointer;
  transition: transform 0.08s ease;
}
:deep(.veyq-space-card--pin) {
  transform: translate(-50%, calc(-100% - 10px));
}
:deep(.veyq-space-card--pin::after) {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -6px;
  width: 13px;
  height: 13px;
  background: var(--app-surface);
  border-right: 1px solid var(--app-line);
  border-bottom: 1px solid var(--app-line);
  transform: translateX(-50%) rotate(45deg);
}
:deep(.veyq-space-card--pin:hover) {
  transform: translate(-50%, calc(-100% - 12px));
}
:deep(.veyq-space-card--member) {
  transform: translate(-50%, -50%);
}
:deep(.veyq-space-card--member:hover) {
  transform: translate(-50%, -50%) scale(1.04);
}
:deep(.veyq-space-card-thumb) {
  position: relative;
  width: 34px;
  height: 34px;
  flex: none;
  overflow: hidden;
  border-radius: 8px;
}
/* The cover image, positioned + sized by coverImageStyle inside the overflow-hidden thumb (mirrors MemoryCoverImage). */
:deep(.veyq-space-card-thumb img) {
  position: absolute;
  display: block;
  max-width: none;
}
:deep(.veyq-space-card-thumb-ph) {
  display: grid;
  place-items: center;
  background: var(--app-accent-soft);
  color: var(--app-accent);
}
:deep(.veyq-space-card-thumb-ph .veyq-icon) {
  font-size: 0.95rem;
}
:deep(.veyq-space-card-body) {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
:deep(.veyq-space-card-body strong) {
  color: var(--app-text);
  font-size: 0.85rem;
  font-weight: 600;
  line-height: 1.25;
}
:deep(.veyq-space-card-date) {
  color: var(--app-muted);
  font-size: 0.76rem;
  line-height: 1.2;
}
</style>
