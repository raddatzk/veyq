// A STUB row factory for core's level view tests (inbox #407): core's view knows no application, and neither do its tests.
// Kinds are made-up tokens (`ALPHA`, `BETA`, …); the stub draws each node as a row titled by its name, records what the level
// handed it (called once per level view; the context, reactive; the host) and gives every row ONE menu action `rename` that
// asks the host to rename it. Optional: a presentation for a mode (the stand-in for Memories' map), a level action, and each
// row's part in a selection of the level (`selection`, inbox #421; omitted: the row carries none - it allows nothing) and its
// answer for core's default rename, move and delete (`standard`; omitted: none - the stub's own `rename` still replaces
// core's of that id).
import { computed, defineComponent, h, ref, type Ref } from 'vue'
import type { LevelActionHandler } from '../core/node/levelActions'
import type { LevelNotice, LevelRowSelection, LevelRowStandard, LevelRowsContext, LevelRowsFactory, LevelRowsHost, LevelRowSource } from '../core/node/levelRow'
import type { TreeNode } from '../core/node/tree'
import type { ViewMode } from '../shared/state/useViewMode'
import type { VeyqIconName } from '../shared/ui/icons'

/** The icon every stub row draws, whatever its `name` (a made-up token is no icon name): a test that asserts the row
 *  draws the factory's icon asserts this one. */
export const STUB_ROW_ICON: VeyqIconName = 'tag'

export interface StubRows {
  factory: LevelRowsFactory
  /** How often the level called the factory (once per level view mount). */
  calls: () => number
  /** The context of the latest call: reactive (`ctx.value` follows the level). */
  ctx: () => Readonly<Ref<LevelRowsContext>>
  host: () => LevelRowsHost
  /** The notices the stub shows; set from a test. */
  notices: Ref<LevelNotice[]>
}

export function stubRows(
  name: string,
  opts: {
    presentationFor?: ViewMode
    action?: { id: string; handler: LevelActionHandler }
    shownActions?: boolean
    selection?: (node: TreeNode) => LevelRowSelection
    standard?: (node: TreeNode) => LevelRowStandard
    /** Leave out the stub's own `rename`, so core's default (if granted) is the row's. */
    noOwnRename?: boolean
  } = {},
): StubRows {
  let calls = 0
  let lastCtx: Readonly<Ref<LevelRowsContext>> | null = null
  let lastHost: LevelRowsHost | null = null
  const notices = ref<LevelNotice[]>([])
  const Presentation = defineComponent({
    props: { nodes: { type: Array, default: () => [] } },
    emits: ['open'],
    setup: (p) => () => h('div', { class: 'stub-presentation', 'data-name': name }, (p.nodes as { name: string }[]).map((n) => n.name).join(',')),
  })
  const factory: LevelRowsFactory = (ctx, host): LevelRowSource => {
    calls++
    lastCtx = ctx
    lastHost = host
    if (opts.action) host.registerAction(opts.action.id, opts.action.handler)
    return {
      row: (node) => ({
        icon: STUB_ROW_ICON,
        title: node.name,
        meta: `${name} meta`,
        primary: { label: `open ${node.name}`, onClick: () => host.open?.(node) },
        actions: opts.noOwnRename
          ? []
          : [{ id: 'rename', label: `rename ${node.name}`, icon: 'pencil', shown: opts.shownActions ?? true, onClick: () => host.rename?.(node) }],
        ...(opts.selection ? { selection: opts.selection(node) } : {}),
        ...(opts.standard ? { standard: opts.standard(node) } : {}),
      }),
      notices: computed(() => notices.value),
      presentation: (mode) => (opts.presentationFor === mode ? { component: Presentation, props: { nodes: ctx.value.nodes } } : null),
    }
  }
  return { factory, calls: () => calls, ctx: () => lastCtx!, host: () => lastHost!, notices }
}
