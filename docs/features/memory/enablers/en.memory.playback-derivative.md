# Enabler: A small playable copy of a video, made on the device

- **ID:** en.memory.playback-derivative
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **planned** — refined 2026-10-18 with [uc.memory.banner](../use-cases/uc.memory.banner.md)
  ([ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md) decision 4); built by
  `changes/cr-memory-banner.md`. Nothing is built: no code in `web/` or `backend/` transcodes today.
- **Provides:** from a video the device can read (a picked file, or a decrypted original), a **small MP4** —
  H.264, long edge at most 1280, at most 2 Mbit/s, its sound kept — plus a JPEG of its **first frame**, both as bytes the
  caller seals and uploads like any blob. Made **in the browser**, so the operator never sees a frame; the original
  never leaves the device unsealed.
- **Serves (use-cases):** [uc.memory.banner](../use-cases/uc.memory.banner.md) (a video banner plays this copy, never the
  original). The gallery does **not** use it: it plays the original
  ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)); a small tier for gallery videos would be
  that ADR's to decide, with this enabler under it.
- **Depends on:** the browser's **WebCodecs** (`VideoDecoder`, `VideoEncoder`, `AudioDecoder`, `AudioEncoder`), and one MP4
  demuxer/muxer dependency that is actively maintained and vendor-neutral (`CLAUDE.md`: no deprecated dependencies; the CI
  install refuses `npm warn deprecated`). [en.memory.derivatives](en.memory.derivatives.md) for the first-frame poster
  (`deriveVideoPoster`).
- **Preconditions:** a browser whose `VideoEncoder.isConfigSupported` accepts an H.264 configuration at the target size; a
  video no longer than the caller's bound (the banner's is 2 minutes).
- **Postconditions (success):** two byte arrays, the MP4 and the poster JPEG, and their dimensions and duration; nothing
  stored, nothing sent.

## Decisions

1. **On the device, never on the server.** The server holds no plaintext (operator-blind), and a server transcode would also
   be a heavy dependency on a small self-hosted instance. Inbox #23's worker agent holds no key and is out of V1.
2. **A fixed target, not a ladder.** One copy: H.264 (the codec every engine plays), long edge 1280, at most 2 Mbit/s
   video, the frame rate of the source capped at 30. No adaptive streaming: the copy is small enough to stream through
   ADR 0047's range shim as any blob.
3. **The sound is kept.** Its codec is the spike's (below): AAC where the engine encodes it, otherwise the codec every target
   engine plays in MP4; a source without sound gives a copy without sound.
4. **Refuse, never degrade to the original.** Where `VideoEncoder` is missing or refuses every H.264 configuration, the
   caller is told the device cannot make the copy; a caller may offer something else (the banner offers an image), never
   the original.
5. **Cancellable and bounded.** The transcode reports progress, stops on cancel, and runs on the page that holds the keys
   (WebCodecs runs in a Worker too; where it runs is the CR's measurement, as the image tiers' Worker was).
6. **The poster is the copy's first frame**, through the existing `deriveVideoPoster` (JPEG, long edge 2048, quality 0.85),
   so the poster and the video agree.

## Open — settled by `cr-memory-banner`'s spike, before any code depends on it

- **Engine support (R):** which of Chromium, WebKit and Gecko (desktop and phone) encode H.264 and AAC through WebCodecs. The
  spike measures it in the e2e browsers and records the matrix here; it is remembered, not measured, until then.
- **The muxer:** which maintained library, its licence (AGPL-compatible), its size, and that `npm` reports it not deprecated.

## Related

- Use-case: [uc.memory.banner](../use-cases/uc.memory.banner.md)
- Decisions: [ADR 0102](../../../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md),
  [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (playback; why the gallery has no small tier)
- Enabler: [en.memory.derivatives](en.memory.derivatives.md)
