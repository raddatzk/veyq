<script setup lang="ts">
// The shell's footer bar (the registry's `app.footerBar`, ADR 0131): what the applications registered for the place below
// the footer, mounted ONCE by the shell that draws it - the signed-in app's (`AppShell`, while a session is signed in and
// unlocked) and the share page's (`ShareView`) - so a bar outlives every change of page within that shell and is torn
// down with it. Core draws only the place: in the page's flow after the footer, sticky at the viewport's bottom edge, so
// it is always within reach and at the page's end sits below the footer's links. What a bar shows, and whether it shows
// anything, is its application's; core names none. An application switched off here gets no bar (the same answer every
// place of the shell reads, `isSwitchedOff`); a guest's page is not told which applications are off, so it mounts every one.
import { computed } from 'vue'
import { registry } from '../applications/registry'
import { useCapabilities } from '../../shared/state/useCapabilities'

const props = defineProps<{ audience: 'member' | 'guest' }>()
const { isSwitchedOff } = useCapabilities()
// A bar registered with a condition (`app.footerBar(component, shown)`) is mounted only while it holds.
const bars = computed(() =>
  registry
    .footerBars()
    .filter((b) => props.audience === 'guest' || !isSwitchedOff(b.application))
    .filter((b) => b.shown?.() ?? true),
)
</script>

<template>
  <div v-if="bars.length > 0" class="veyq-footer-bars" data-testid="footer-bars">
    <component :is="bar.component" v-for="bar in bars" :key="bar.application" />
  </div>
</template>

<style scoped>
.veyq-footer-bars {
  position: sticky;
  bottom: 0;
  /* Above the page's content and its own sticky rows (a Memory's tab row), below a dialog's layer. */
  z-index: 20;
  display: grid;
}
</style>
