// The application registry (ADR 0079, cr-platform-web-boundaries Decision 3): what the shell asks instead of
// importing an application. Core renders by node kind and by slot; an application says, once, what it renders for
// which kind and which slot it fills.
//
// **Build-time only (ADR 0079 Decision 5, ADR 0026).** The registry is filled by plain function calls from ONE
// composition root (`web/src/applications.ts`), which statically imports each application. There is no glob, no
// computed `import()`, no plugin loading: what runs is what was reviewed. The guard
// (`scripts/validate_web_core_imports_no_applications.py`) keeps core from importing an application and from
// discovering one by glob.
//
// **Write-once.** Registered code runs with every unlocked key, so the registry is a trust surface and not a
// convenience: registering a kind twice, or claiming a slot twice, THROWS — it never overwrites and never merges.
// Nothing here is exported as mutable state; `registry` exposes only the two register doors and read-only lookups.
//
// **Single-claim slots.** A slot (a level view, a dialog, the topbar tray, the sidebar pins) is held by at most ONE
// application today, because exactly one application exists. A second claim throws, which turns "two applications
// both think they own the folder level" into a boot-time failure instead of a silently shadowed component. The
// next application's change request re-cuts the slots it needs to share (per-kind composition); freezing that
// abstraction from one usage is what the CR's Open question 1 declined to do.
//
// **Fail closed.** `kind()` never returns `undefined`: a node kind nobody registered resolves to
// `{ supported: false }`, which the host renders as an explicit unsupported state, never as an empty region.
import type { Component } from 'vue'
import { NodeKind } from '../../api/generated/content'

/** What core needs to know about a node kind. `detail` is the component the host renders for an OPENABLE node of
 *  the kind; absent means the kind is not openable (a folder browses, it does not open). */
export interface KindDescriptor {
  kind: NodeKind
  detail?: Component
  /** The PrimeIcons class core draws next to a node of this kind (e.g. the trash list). */
  icon: string
  /** Whether a public share can be created for a node of this kind (the capability check still applies). */
  shareable: boolean
}

export type KindLookup = ({ supported: true } & KindDescriptor) | { supported: false; kind: NodeKind }

/** The single-claim slots. Each is claimed by at most one application (see the header). */
export type SlotName = 'levelView' | 'nodeMutationDialog' | 'shareDialog' | 'topbarTray' | 'sidebarPins'

export interface Registry {
  /** Register a node kind. Throws if the kind is already registered (the platform's own FOLDER included). */
  registerKind(descriptor: KindDescriptor): void
  /** Claim a slot. Throws if the slot is already claimed. */
  claimSlot(name: SlotName, component: Component): void
  /** The kind's descriptor, or `{ supported: false }` — never `undefined`. */
  kind(kind: NodeKind): KindLookup
  /** The component filling the slot, or null when no application claimed it (the shell renders nothing). */
  slot(name: SlotName): Component | null
}

/** A fresh registry holding only the platform's own kind (FOLDER: browsable, never openable, never shareable).
 *  The app uses the single `registry` below; tests build their own to prove the rules on an untouched instance. */
export function createRegistry(): Registry {
  const kinds = new Map<NodeKind, KindDescriptor>([
    [NodeKind.Folder, { kind: NodeKind.Folder, icon: 'pi pi-folder', shareable: false }],
  ])
  const slots = new Map<SlotName, Component>()
  return {
    registerKind(descriptor) {
      if (kinds.has(descriptor.kind)) throw new Error(`node kind ${descriptor.kind} is already registered`)
      kinds.set(descriptor.kind, { ...descriptor })
    },
    claimSlot(name, component) {
      if (slots.has(name)) throw new Error(`slot ${name} is already claimed`)
      slots.set(name, component)
    },
    kind(kind) {
      const d = kinds.get(kind)
      return d ? { supported: true, ...d } : { supported: false, kind }
    },
    slot(name) {
      return slots.get(name) ?? null
    },
  }
}

/** The app's registry, filled once by `registerApplications()` (web/src/applications.ts) before the first route
 *  resolves (`main.ts`) and, in tests, by `src/test/setup.ts`. */
export const registry: Registry = createRegistry()
