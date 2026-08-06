import { reactive } from 'vue'

// Reactive instance state the router + boot read to land on the right first screen.
export const serverState = reactive({
  // null while boot is still discovering; true/false once GET /api/setup has answered.
  setupCompleted: null as boolean | null,
  // true when the instance couldn't be reached on boot — surfaces a clear error instead of a blank screen.
  unreachable: false,
})
