// A stand-in for a host page's doors (`core/node/nodeHost.ts`): every door records its calls, so a test of a view that asks
// the page for something (core's `LevelView`, a kind's `detail`) mounts it as a host page would and reads what it asked.
// `MEMBER_DOORS` and `GUEST_DOORS` are the sets the two real hosts hand on (`views/SpaceView.vue`: every one;
// `core/sharing/ShareTreePage.vue`: open, the download, the create and - for a link that moves anything - the move).
import type { NodeHost } from '../core/node/nodeHost'
import { nodeHostProvide } from '../core/node/nodeHost'
import type { Access } from '../core/access'
import { ShareRole } from '../api/generated/sharing'

export type DoorName = keyof NodeHost

export const MEMBER_DOORS: readonly DoorName[] = [
  'open', 'rename', 'renameTo', 'move', 'moveMany', 'delete', 'deleteMany', 'share', 'createChild', 'download', 'copy', 'importArchive', 'openTrash',
]
export const GUEST_DOORS: readonly DoorName[] = ['open', 'move', 'moveMany', 'download', 'createChild']

export interface RecordingHost {
  host: NodeHost
  /** Each door's calls, its arguments without a trailing `undefined` (none yet: absent). */
  calls: Partial<Record<DoorName, unknown[][]>>
  /** A `global.provide` that hands `host` to the mounted view. */
  provide: Record<symbol, NodeHost>
}

/** The host page `access` is on, recording: a member's every door, a guest's the guest page's (the move only for a link
 *  that moves anything, `READ_WRITE`). */
export function hostFor(access: Access): RecordingHost {
  if (access.mode === 'member') return recordingHost(MEMBER_DOORS)
  return recordingHost(access.role === ShareRole.ReadWrite ? GUEST_DOORS : GUEST_DOORS.filter((d) => d !== 'move' && d !== 'moveMany'))
}

/** A host with the doors `doors` (default: a member's), each recording its calls. */
export function recordingHost(doors: readonly DoorName[] = MEMBER_DOORS): RecordingHost {
  const calls: RecordingHost['calls'] = {}
  const host: Record<string, (...args: unknown[]) => void> = {}
  for (const door of doors) {
    host[door] = (...args: unknown[]) => {
      while (args.length && args[args.length - 1] === undefined) args.pop()
      ;(calls[door] ??= []).push(args)
    }
  }
  return { host: host as NodeHost, calls, provide: nodeHostProvide(host as NodeHost) }
}
