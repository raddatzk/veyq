# Enabler: A small playable copy of a video, made by a device that holds the key

- **ID:** en.memory.playback-derivative
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **planned** — decided in [ADR 0132](../../../decisions/0132-a-content-job-runs-on-a-members-own-device-and-adds-its-result-to-the-object-later.md) (the queue's workers; decision 9 is this copy: which
  videos, when, the stored ones too), over the operator-blind background jobs of
  [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md); after V1. Nothing is built: no code in `src/web/` or
  `src/backend/` transcodes. Until it exists a video banner plays its original
  ([uc.memory.banner](../use-cases/uc.memory.banner.md), [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  decision 4), as a gallery video plays its original ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)).
- **Provides:** from a video a key-holding device can read (a decrypted original), a **small MP4** — H.264, long edge at
  most 1920, about 5 Mbit/s, its sound kept — as bytes the worker seals and uploads like any blob. The operator never
  sees a frame; the original never leaves a device unsealed.
- **Serves (use-cases):** [uc.memory.banner](../use-cases/uc.memory.banner.md) — the copy fills the banner's later slot,
  `BANNER_PLAYBACK`, and a `playback` ref beside the banner's `blob` (ADR 0102 decision 2), so nothing that exists moves
  when it lands. A small tier for gallery videos would use it too (inbox #493, ADR 0047's to decide).
- **Depends on:** the client worker queue (ADR 0021, inbox #23); a decoder and an encoder on the worker's device, and an
  MP4 muxer that is actively maintained and vendor-neutral (`CLAUDE.md`: no deprecated dependencies).
- **Postconditions (success):** the small copy sealed under a fresh `K_blob` bound to its node and slot, attached by the
  worker; the original untouched.

## The copy

The choices and their rejected alternatives are [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)
§ The small playable copy; what the copy is:

- **Made on a key-holding device by a client worker node**, never on the server and never in the editor's browser tab.
- **For every video**, stored ones too; the gallery and the lightbox play the copy, and the original is fetched only to
  download it, as for an image (ADR 0132 decision 9). Its slot is the derived role `PLAYBACK`, added to the medium later
  (ADR 0132 decision 5).
- **One fixed target:** H.264, long edge 1920, about 5 Mbit/s video, the source's frame rate capped at 30. No adaptive
  streaming: the copy streams through ADR 0047's range shim as any blob does.
- **The sound is kept**; a source without sound gives a copy without sound.
- **Additive:** the copy goes into a slot of its own beside the copied original; a reader that does not know it plays
  the original.

## Open

- Whether a long input lowers the bitrate. The engines are the platforms' own encoders and the sound AAC-LC
  (ADR 0132 decisions 8 and 9); each platform's muxer, and whether it can write without a file, the CR measures.
- Whether the banner's original is dropped once the small copy exists (it halves the storage a long video costs, where the
  banner does not share the gallery's object).

## Related

- Use-case: [uc.memory.banner](../use-cases/uc.memory.banner.md)
- Decisions: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md),
  [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (playback; why the gallery has no small tier),
  [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) (the worker queue)
- Enabler: [en.memory.derivatives](en.memory.derivatives.md)
