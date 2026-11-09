<script setup lang="ts">
// The Memory set-location editor (cr-0069, uc.memory.place) — intent-first. A slippy map (Leaflet, ADR 0018)
// with a **fixed centre crosshair**: you move the *map* under it, the centre is the place ("Karte verschieben",
// like Uber/Maps). It opens centred on the **medoid** of the Memory's photo GPS (shown as faint dots) with a pill
// "Folgt deinen Fotos" (`auto`); moving the map by hand takes over → "Eigener Ort" (`fixed`), with a one-tap
// "wieder folgen" back to `auto`. The **name** comes from the reverse-geocoded **address hierarchy** (ADR 0016) —
// you pick the granularity (Straße…Land) or type your own; position and name are separate levers. A quiet
// "Ort entfernen" clears it. Crypto/consent live in the parent; this is presentation + the geocode call.
//
// **Without the map** (`memories.map` off — inbox #458, ADR 0088 § The register — or the member's map services off,
// uc.memory.place A6) the editor is a name field only: no map, no pin, no geocoder. The position is the medoid of the
// photos' GPS (`auto`), a pin set earlier on a map (kept as it is), or absent — a place that is a name only, which the
// map views skip. The name is always typed here, even where the geocoder is on: the one consent it rides on is given
// only with the map. Where the server offers a map, the offer to turn the map services on (`MapConsentPrompt`, naming
// the hosts) stands where the map would be; turning them on there draws the map in this editor and keeps the typed name.
//
// **Withdrawn while open** (the map's "Turn map services off", uc.memory.place A5): the map unmounts at once, no further
// tile or geocoder request leaves, and the offer stands where the map was; the editor goes on as the name field, keeping
// what was framed and resolved so far, and turning the map services back on there brings the map back.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import MemoryMap from './MemoryMap.vue'
import MapConsentPrompt from './MapConsentPrompt.vue'
import { reverseGeocode, defaultLevel, nameAtLevel, type GeoLevel } from '../geocode'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import { MAX_PLACE_NAME, type LatLng, type MemoryLocation } from '../memoryLocation'

const props = defineProps<{
  location: MemoryLocation | null // the current stored location (null = a fresh set)
  medoid: LatLng | null // the medoid of the Memory's media GPS (the `auto` position), or null
  points?: LatLng[] // all media GPS points — the map's photo dots + the auto framing
}>()
const emit = defineEmits<{ save: [MemoryLocation | null]; close: [] }>()

const { t, locale } = useI18n()
const { tileUrl, tileAttribution, tileMaxZoom, geocoderUrl, geocodingAvailable, mapsAvailable, mapsOffered } = useMapConfig()
const mapRef = ref<{ fitPoints: (p: LatLng[]) => void; setView: (c: LatLng, z: number) => void } | null>(null)

const DEFAULT_ZOOM = 13
const WORLD: LatLng = { lat: 20, lng: 0 }

const hasMedoid = computed(() => Boolean(props.medoid))
// The map is drawn only where the server serves one and the member turned the map services on: no tile and no geocoder
// request leaves the device without both. Without them the editor is the name field (uc.memory.place A4, A6).
const withMap = computed(() => mapsAvailable.value)
const canGeocode = computed(() => withMap.value && geocodingAvailable.value)
// A Memory with photo GPS defaults to `auto` (follows photos); one without can only place a `fixed` point on the
// map — or, without a map, has no position yet and stays `auto`, so the first geotagged photo gives it one.
const derivedMode = ref<'auto' | 'fixed'>(props.location?.derivedMode ?? (hasMedoid.value || !withMap.value ? 'auto' : 'fixed'))
const derived = ref<LatLng | null>(props.location?.derived ?? (derivedMode.value === 'auto' ? props.medoid : null))
const name = ref(props.location?.name ?? '')
const nameSource = ref<'geocoded' | 'manual'>(props.location?.nameSource ?? (canGeocode.value ? 'geocoded' : 'manual'))
const levels = ref<GeoLevel[]>([])
const chosenLevel = ref<GeoLevel['key'] | null>(props.location?.nameAnchor?.level ?? null)

const initialCenter = computed<LatLng>(() => derived.value ?? props.medoid ?? WORLD)
const initialZoom = computed(() => (derived.value || props.medoid ? DEFAULT_ZOOM : 2))
const levelOptions = computed(() => levels.value.map((l) => ({ label: l.name, value: l.key })))
// The point a geocoded name resolves at (the current place): the medoid in `auto`, the framed centre in `fixed`.
// Without the map an `auto` place keeps its stored position until the photos' medoid is known (the media may still
// be loading), so a rename never drops a position by accident.
const anchorPoint = computed<LatLng | null>(() =>
  derivedMode.value === 'fixed' ? derived.value : withMap.value ? props.medoid : (props.medoid ?? derived.value),
)
// With the map a place needs its point; without it a name is enough (a place that is a name only, uc.memory.place E3).
const canSave = computed(() => Boolean(name.value.trim()) && (!withMap.value || Boolean(anchorPoint.value)))

let geocodeTimer: ReturnType<typeof setTimeout> | null = null

/** Reverse-geocode a point into the level hierarchy and (unless the name is typed) fill the name at the chosen
 *  or default level — the picker's options come from here too. */
async function resolveLevels(lat: number, lng: number) {
  if (!canGeocode.value) return
  const result = await reverseGeocode(geocoderUrl.value, lat, lng, locale.value)
  if (!result) return
  levels.value = result
  if (nameSource.value === 'manual') return
  const level = (chosenLevel.value && result.find((l) => l.key === chosenLevel.value)) || defaultLevel(result)
  if (level) {
    chosenLevel.value = level.key
    name.value = level.name
    nameSource.value = 'geocoded'
  }
}
function scheduleResolve(lat: number, lng: number) {
  if (geocodeTimer) clearTimeout(geocodeTimer)
  geocodeTimer = setTimeout(() => void resolveLevels(lat, lng), 700) // debounce continuous panning
}

/** The framed centre moved. In `auto` a **user pan** takes over → `fixed`; `fixed` tracks the centre as the place. */
function onFrame(f: { lat: number; lng: number; zoom: number; user: boolean }) {
  if (derivedMode.value === 'auto') {
    if (!f.user) return // programmatic recenter (open / follow-again) — don't take over
    derivedMode.value = 'fixed'
  }
  derived.value = { lat: f.lat, lng: f.lng }
  scheduleResolve(f.lat, f.lng)
}

/** Back to `auto`: recenter on the medoid (a programmatic move — no takeover) and re-resolve its name. */
function followAgain() {
  if (!props.medoid) return
  derivedMode.value = 'auto'
  derived.value = props.medoid
  mapRef.value?.setView(props.medoid, DEFAULT_ZOOM)
  void resolveLevels(props.medoid.lat, props.medoid.lng)
}

function pickLevel(key: GeoLevel['key']) {
  const n = nameAtLevel(levels.value, key)
  if (!n) return
  chosenLevel.value = key
  name.value = n
  nameSource.value = 'geocoded'
}
function onNameInput() {
  nameSource.value = 'manual' // typing your own name freezes it (no more auto-fill)
}

// One name control at a time: the level picker (the geocoded name) or a typed field. A link toggles between them.
const showLevelPicker = computed(() => nameSource.value === 'geocoded' && levelOptions.value.length > 0)
function toggleNameMode() {
  if (showLevelPicker.value) {
    nameSource.value = 'manual' // → type your own name (keeps the current one to edit)
  } else {
    nameSource.value = 'geocoded' // → back to the map's name at the chosen (or default) level
    const lvl = (chosenLevel.value && levels.value.find((l) => l.key === chosenLevel.value)) || defaultLevel(levels.value)
    if (lvl) {
      chosenLevel.value = lvl.key
      name.value = lvl.name
    }
  }
}

function save() {
  if (!canSave.value) return
  const p = anchorPoint.value
  // Without the map nothing is resolved here: an untouched geocoded name keeps the anchor it was resolved at. A map that
  // was turned off while the editor was open resolved its levels before that, so a level picked from them anchors here.
  const resolvedHere = withMap.value || levels.value.length > 0
  const anchor = !resolvedHere
    ? (nameSource.value === 'geocoded' ? props.location?.nameAnchor : undefined)
    : nameSource.value === 'geocoded' && chosenLevel.value && p ? { lat: p.lat, lng: p.lng, level: chosenLevel.value } : undefined
  emit('save', {
    name: name.value.trim(),
    nameSource: nameSource.value,
    derived: p ?? undefined,
    derivedMode: derivedMode.value,
    nameAnchor: anchor,
  })
  emit('close')
}
function remove() {
  emit('save', null) // clear the place; the parent preserves the date
  emit('close')
}

/** Frame the map and resolve the current place's names — when the editor opens with the map, and when the map services
 *  are turned on in it. */
function frameAndResolve() {
  if (!withMap.value) return // no map: no framing, no name to resolve
  if (derivedMode.value === 'auto' && props.medoid) {
    if (props.points && props.points.length > 1) mapRef.value?.fitPoints(props.points) // frame the whole spread
    void resolveLevels(props.medoid.lat, props.medoid.lng)
  } else if (derived.value) {
    void resolveLevels(derived.value.lat, derived.value.lng)
  }
}
onMounted(frameAndResolve)

// The map services turned on in the open editor (its offer, or Settings in another tab): the map is drawn here and the
// editor goes on as if it had opened with it - except that a name already typed stays the member's (`manual`). With no
// name yet the map may name the place; with no position yet the place is the framed point (`fixed`), as on an opening
// with the map. Turned off again, the editor keeps what it has (the withdrawal above).
watch(withMap, async (on) => {
  if (!on) return
  if (!name.value.trim() && canGeocode.value) nameSource.value = 'geocoded'
  if (derivedMode.value === 'auto' && !props.medoid && !derived.value) derivedMode.value = 'fixed'
  await nextTick() // the map mounts with this render; framing needs its instance
  frameAndResolve()
})
onBeforeUnmount(() => {
  if (geocodeTimer) clearTimeout(geocodeTimer)
})
</script>

<template>
  <VeyqDialog :visible="true" :header="t('memory.place.title')" @update:visible="emit('close')">
    <form class="veyq-form memory-location-editor" @submit.prevent="save">
      <div v-if="withMap" class="memory-location-mapwrap">
        <MemoryMap
          ref="mapRef"
          :tile-url="tileUrl"
          :attribution="tileAttribution"
          :max-zoom="tileMaxZoom"
          :center="initialCenter"
          :zoom="initialZoom"
          :marker="null"
          :dots="points"
          crosshair
          @frame="onFrame"
        />
        <div class="memory-location-pill">
          <template v-if="derivedMode === 'auto'">
            <VeyqIcon name="images" /> {{ t('memory.place.followsPhotos') }}
          </template>
          <template v-else>
            <VeyqIcon name="map-marker" /> {{ t('memory.place.customPlace') }}
            <button v-if="hasMedoid" type="button" class="memory-location-follow" @click="followAgain">{{ t('memory.place.followAgain') }}</button>
          </template>
        </div>
      </div>

      <MapConsentPrompt v-if="!withMap && mapsOffered" />
      <p v-if="!withMap" class="memory-location-position">
        <template v-if="anchorPoint && derivedMode === 'auto'">
          <VeyqIcon name="images" /> {{ t('memory.place.followsPhotos') }}
        </template>
        <template v-else-if="anchorPoint">
          <VeyqIcon name="map-marker" /> {{ t('memory.place.customPlace') }}
        </template>
        <template v-else>
          <VeyqIcon name="tag" /> {{ t('memory.place.nameOnly') }}
        </template>
      </p>

      <div class="memory-location-name">
        <VeyqSelect
          v-if="showLevelPicker"
          :model-value="chosenLevel"
          :options="levelOptions"
          class="memory-location-name-field"
          :aria-label="t('memory.place.name')"
          @update:model-value="(key) => key && pickLevel(key)"
        />
        <VeyqInput
          v-else
          v-model="name"
          class="memory-location-name-field"
          :maxlength="MAX_PLACE_NAME"
          :placeholder="t('memory.place.namePlaceholder')"
          :aria-label="t('memory.place.name')"
          @input="onNameInput"
        />
      </div>
      <div v-if="levelOptions.length" class="memory-location-nametoggle">
        <button type="button" class="memory-location-nametoggle-btn" @click="toggleNameMode">
          {{ showLevelPicker ? t('memory.place.nameOwn') : t('memory.place.nameFromMap') }}
        </button>
      </div>

      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" @click="emit('close')" />
        <div class="veyq-action-group">
          <VeyqButton :label="t('memory.place.remove')" icon="trash" variant="danger" appearance="outlined" :disabled="!location" @click="remove" />
          <VeyqButton type="submit" :label="t('common.save')" icon="check" :disabled="!canSave" />
        </div>
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.memory-location-editor {
  min-width: min(480px, 90vw);
  display: grid;
  gap: 0.8rem;
}
.memory-location-mapwrap {
  position: relative;
}
.memory-location-pill {
  position: absolute;
  top: 10px;
  right: 10px; /* top-right — the Leaflet zoom control sits top-left */
  z-index: 500;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.28rem 0.6rem;
  border-radius: 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-line);
  color: var(--app-text);
  font-size: 0.82rem;
}
.memory-location-follow {
  border: 0;
  background: none;
  color: var(--app-accent);
  cursor: pointer;
  font-size: 0.82rem;
  padding: 0;
  text-decoration: underline;
}
.memory-location-position {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  margin: 0;
  color: var(--app-muted);
  font-size: 0.86rem;
}
.memory-location-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.memory-location-name-field {
  flex: 1;
  min-width: 0;
}
.memory-location-nametoggle {
  margin-top: -0.35rem;
}
.memory-location-nametoggle-btn {
  border: 0;
  background: none;
  padding: 0;
  color: var(--app-accent);
  cursor: pointer;
  font-size: 0.8rem;
}
</style>
