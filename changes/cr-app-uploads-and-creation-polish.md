# CR — The apps' uploads in the background with placeholder tiles; the place in New Memory; the participants offline

- adrs: [0145](../docs/decisions/0145-the-apps-uploads-go-on-with-the-app-left-and-show-in-the-gallery-a-desktop-asks-before-a-quit-drops-them.md),
  [0140](../docs/decisions/0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md) (decision 5's allowlist),
  [0144](../docs/decisions/0144-the-apps-curate-a-memorys-participants-a-persons-profile-a-mediums-capture-time-and-the-banner-the-webs-rules-in-the-core.md) (decision 7)
- satisfies: uc.memory.upload, uc.memory.place A1, uc.memory.participants (offline); inbox #940, #948; V2 block C, half 1
- Model: [native-apps](../docs/crosscutting/native-apps.md) § Open, *Uploads* and *Memories*

Scaffolding: deleted at harvest, its lessons moved where they bind.

## Refined (2026-11-18)

- **#940 re-derived:** the web's `MemoryNodeDialog` has **no date at creation** (the date starts `auto`); it offers one
  opt-in, *Determine location automatically*, unticked, where `mapsAvailable`, sealing a pending `auto` place
  `{name: '', nameSource: 'geocoded', derivedMode: 'auto'}` beside the participants. The apps take exactly that; the
  docs' "the date in the creation dialog" is dropped as the web's own rule (`grep -n date
  src/web/src/features/memories/components/MemoryNodeDialog.vue` → none).
- **#948 decided:** `listScopeMembers` joins ADR 0140's kept reads (`KeptRead.MEMBERS`, per scope, sealed like every
  entry, reached while the scope is the account's, kept by a pin of a node in it); offline the people line is named from
  it, read-only; an offline open names the tenant's directory from the tenant scope's kept list.
- **Uploads:** ADR 0145.

## Phases

1. Core: `KeptRead.MEMBERS` and the reads through it; `MemoryCreate` arms the place; `UploadsUnderway`; `UploadTile`,
   `GalleryModel.State.uploading`, the layout. Tests red first: `OfflineReadPathTest` (members kept, swept),
   `ParticipantsTest` (offline line), `MemoryCreateTest` (the armed place), `UploadQueueTest` (under way),
   `GalleryModelTest` (placeholders).
2. Bridge: `GalleryShown.uploading`, `Gallery.Layout`, `Shell.CreateMemory.autoPlace`, `Answer.WasThere.autoPlace`.
3. Platforms: Android (placeholders, the checkbox, `UploadWorker`), Apple (placeholders, the toggle, `ContinuedWork`,
   the Mac's quit guard), Linux and Windows (placeholders, the checkbox, the close question).
4. Passes where reachable; harvest.
