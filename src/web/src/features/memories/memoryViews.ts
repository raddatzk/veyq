// Memories' presentations (inbox #370, #417): core's grid and list, and the places map, which is Memories' own - drawn by
// `components/SpaceMap.vue` over `spaceMap.ts`'s pins. ONE list for both places a person meets Memories as a list: the level
// (`register.ts` names it as the kinds' `list.views`, the factory draws the map, `levelRows.ts`) and a person's profile
// (`components/PersonMemories.vue`, the same switch over the same map). A second map component would be a second place to
// keep the consent, the tiles and the empty state right.
import { GRID_VIEW, LIST_VIEW, type LevelViewOption } from '../../core/node/levelViews'

/** The places map (cr-0071): Memories' own presentation. */
export const MAP_VIEW: LevelViewOption = { mode: 'map', icon: 'map', label: 'memory.viewMap' }

/** What a list of Memories offers: tiles, rows and the map, in the toolbar's order. */
export const MEMORY_VIEWS: readonly LevelViewOption[] = [GRID_VIEW, LIST_VIEW, MAP_VIEW]
