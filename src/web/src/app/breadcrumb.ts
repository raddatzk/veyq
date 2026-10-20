// The shell breadcrumb trail (cr-0030/0031). The browse view sets it from the LOCAL decrypted tree (the
// operator-blind server computes no navigation); the topbar renders it, each crumb linking to its `#fragment`.
// `id: null` is the space root.
import { reactive } from 'vue'

export type Crumb = { id: string | null; name: string; hash: string }

export const breadcrumb = reactive({ trail: [] as Crumb[] })

export function setBreadcrumb(trail: Crumb[]): void {
  breadcrumb.trail = trail
}
