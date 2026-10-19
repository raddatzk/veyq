# Enabler: A small playable copy of a video, made by a device that holds the key

- **ID:** en.memory.playback-derivative
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **planned** — with the queue of client worker nodes (inbox #23, the operator-blind background jobs of
  [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md)), not V1. Nothing is built: no code in `web/` or
  `backend/` transcodes. Until it exists a video banner plays its original
  ([uc.memory.banner](../use-cases/uc.memory.banner.md), [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  decision 4), as a gallery video plays its original ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)).
- **Provides:** from a video a key-holding device can read (a decrypted original), a **small MP4** — H.264, long edge at
  most 1280, about 2 Mbit/s, its sound kept — as bytes the worker seals and uploads like any blob. The operator never
  sees a frame; the original never leaves a device unsealed.
- **Serves (use-cases):** [uc.memory.banner](../use-cases/uc.memory.banner.md) — the copy fills the banner's later slot,
  `BANNER_PLAYBACK`, and a `playback` ref beside the banner's `blob` (ADR 0102 decision 2), so nothing that exists moves
  when it lands. A small tier for gallery videos would use it too (inbox #493, ADR 0047's to decide).
- **Depends on:** the client worker queue (ADR 0021, inbox #23); a decoder and an encoder on the worker's device, and an
  MP4 muxer that is actively maintained and vendor-neutral (`CLAUDE.md`: no deprecated dependencies).
- **Postconditions (success):** the small copy sealed under a fresh `K_blob` bound to its node and slot, attached by the
  worker; the original untouched.

## Decisions

1. **On a key-holding device, never on the server.** The server holds no plaintext (operator-blind), and a server
   transcode would also be a heavy dependency on a small self-hosted instance. Not in the editor's browser tab either (the
   owner, 2026-10-19): a long video is a long job, and the worker queue serves the gallery as well.
2. **A fixed target, not a ladder.** One copy: H.264 (the codec every engine plays), long edge 1280, about 2 Mbit/s video,
   the frame rate of the source capped at 30. No adaptive streaming: the copy streams through ADR 0047's range shim as
   any blob.
3. **The sound is kept**; a source without sound gives a copy without sound.
4. **Additive, never a replacement in place.** The copy goes into a slot of its own beside the copied original; a reader
   that does not know it plays the original.

## Open — settled when the worker queue is refined

- Which engines and codecs the worker's devices offer (encoder support, AAC or another audio codec in MP4), the muxer,
  and whether a long input lowers the bitrate.
- Whether the banner's original is dropped once the small copy exists (it halves the storage a long video costs, where the
  banner does not share the gallery's object).

## Related

- Use-case: [uc.memory.banner](../use-cases/uc.memory.banner.md)
- Decisions: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md),
  [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (playback; why the gallery has no small tier),
  [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) (the worker queue)
- Enabler: [en.memory.derivatives](en.memory.derivatives.md)
