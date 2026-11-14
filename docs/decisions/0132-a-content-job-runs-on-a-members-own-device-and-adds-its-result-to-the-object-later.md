# ADR 0132 — A content job runs on a member's own device, and adds its result to the object later

- Status: Accepted
- Built: no
- Date: 2026-11-12
- Model: [background-tasks](../crosscutting/background-tasks.md)

## Context

[ADR 0021](0021-operator-blind-background-tasks.md) built the blind job queue and named its heavy consumers — a video's
playable copy, text read from a scan, embeddings for semantic search, faces — without building one (inbox #10, #23).
Every one of them needs what the queue alone does not give:

- **A worker that is not the open browser tab.** The owner ruled out an in-browser transcoder (inbox #493, 2026-10-19);
  the native apps (their Kotlin Multiplatform core, decided on the apps' branch `claude/windows-cr-f6c73c`) carry the platforms'
  hardware encoders and, later, their on-device recognition. Measured 2026-11-12 on `claude/windows-cr-f6c73c`: the
  Kotlin core already signs in, reads and seals with no UI (its JVM live tests, `bridge/smoke/smoke.c`), and no app
  runs anything in the background yet.
- **A worker identity finer than the account.** The lease holder and a decline's exclusion are the **user id**
  (`TasksResource.kt`, `JobDeclineEntity.workerId`). Two devices of one account are one worker: a phone that declines a
  transcode excludes the same member's desktop from it for good, and one device can complete a lease another holds.
- **A way to add a result to an object that already exists.** A medium is born whole and "no path adds a derivative
  later" ([ADR 0113](0113-memories-media-are-blobs-of-cores-blob-service.md) § Context); every content job runs after its
  object is complete, and the stored videos the owner wants copied (2026-11-12: "Bestand wird nachgezogen") are years
  old.

There is no server-side alternative: the server holds no plaintext, and no third-party service may see content
([security-model](../crosscutting/security-model.md) § third-party services; inbox #549: not even as an opt-in).

## Decision

**1. A worker is a device of a member, and holds what that member holds.** A content job is done by a device signed
in to a member's account — an app in the foreground, the same app while it is idle, or a headless host (decision 8).
It opens exactly what that member can open: another member's personal space is out of its reach because it is out of
that member's. There is no separate worker principal and no key narrower than the account's. Rejected: a worker
principal of its own (its own identity, granted per space like a member) — it would let one box serve a whole tenant
without any member's full key on it, but it is a new kind of key-holder with its own grant, revocation and
governance, and nothing yet needs it; it stays the way to go if a shared, always-on box ever must serve spaces no single
member holds.

**2. Each worker has an id of its own, and the lease is held by it.** A worker draws a random 128-bit id when it starts
(a tab when its page loads, an app when its process starts) and sends it with every claim, heartbeat, completion,
failure and decline; the server holds the lease on (user id, worker id) and answers only that pair. The id is never
stored on the device beyond the process and says nothing about the device's kind, so session families stay
indistinguishable ([ADR 0004](0004-session-tokens.md)). A decline excludes **the worker** by default; a tag whose
decline is about the account — `grant-delivery`, where a key-holder that cannot anchor the target declines it
([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 2) — keeps excluding **the account**. The tag
registry states which, per tag (`contracts/tasks/tags/v1.json`).

**3. A content job is enqueued once per object and remembered.** Enqueueing is idempotent over (scope, tag, resource):
a second enqueue of a pending, leased **or done** job answers the existing job and creates none. A content job's row
is kept while its resource lives and erased with it — the application's erasure of an object erases its jobs through a
core call by resource — so a done job is the queue's memory of "already handled", and a backfill pass does not open the
same original twice. A new version of a job's output is a new tag (decision 6), which starts afresh.

**4. Whoever writes an object enqueues its jobs; any worker may backfill.** The client that completes an object
enqueues the jobs that object may need — the web client too, which never runs them. A capable worker also walks the
spaces its member holds and enqueues what is missing (the stored videos, an object a client without the job's
knowledge wrote). The worker that claims a job decides on the bytes whether there is anything to do; a job that finds
nothing to do completes without a result. Rejected: the client decides before it enqueues — the web client would have
to parse every container the worker reads, and the job's absence would tell the operator the same thing its empty
completion does.

**5. A job's result is a blob added to its object later, in a role of its own.** An application may declare a role as
**derived**: a blob in that role may be added to an existing, live object by any member who may write there — sealed
under a fresh `K_blob` and wrapped under the same key as the object's original (the key its origin fixes, a guest's
`K_up` included), bound to (scope, owner, blob, role) like every blob ([ADR 0072](0072-a-seal-binds-its-object.md)). A
derived role is its owner's content ([ADR 0113](0113-memories-media-are-blobs-of-cores-blob-service.md) decision 2): it
is trashed, restored, re-keyed, shared and erased with its object, and counted against the tenant's quota
([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)). The addition locks the object, is refused when
the object is trashed or gone, and carries the version of the node key it wrapped under, refused when the node was
re-keyed since ([ADR 0077](0077-a-write-carries-the-version-it-read.md),
[ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)); the worker then reads the new key and adds again. One
blob per role per owner still holds: a newer output replaces the older one through the same addition. The original and
the roles born with the object never change.

**6. Job types are core's mechanism and the applications' content.** Core owns the queue, the worker id, the
scheduler, derived roles and the hosts; an application contributes a job type — its tag in the public registry, the
code that does the work, and the derived role it writes — through its registrar, as it contributes search
([ADR 0125](0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md) decision 6) and blob roles
([ADR 0112](0112-an-applications-stored-blobs-are-managed-by-a-core-service.md)). Core never names an application
([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 4). A tag's name carries the version of
its output (`memories-playback-v1`); what a job runs — an encoder profile, a model — ships with the release that runs it,
pinned by hash, never fetched at run time (ADR 0079 decision 5).

**7. A job sends a sealed body only when it has a parameter the operator may not see.** The tag already says what to
do and the resource on what; a job whose output follows from the object alone — the playable copy — sends no body.
The first job with a parameter of its own adds the body's seal purpose and its vector (ADR 0021 § 3).

**8. Where a worker runs, and when.**
- **The web client** enqueues and plays results; it runs no content job (inbox #493).
- **The apps** run jobs only when the platform's own scheduler lets them work unattended: Android's WorkManager on an
  unmetered network while charging, Apple's background processing tasks under the same conditions, the desktop apps
  while the session is idle and the machine is on mains power. A person's foreground use always comes first.
- **A headless host** — the Linux app's core without a window, driven through the bridge's C surface
  (`src/mobile/bridge/bridge.h`, on the apps' branch `claude/windows-cr-f6c73c` until it merges) — runs on a machine the member
  chooses (a home server, a NAS), signed in as that member. It is never part of an instance: no compose file, chart or
  image the operator runs includes it, because an operator who runs it holds that member's keys. Unattended, it opens
  its account at start from the password typed into it (`systemd-ask-password`); staying signed in across a restart is
  the member's choice and seals the device's unlock key with `systemd-creds` (bound to the TPM where one exists). Either
  way the machine holds the member's whole key while it runs, and a member who loses it secures the account as for
  any lost device ([ADR 0060](0060-key-rotation-after-compromise.md)).
- **No decrypted original is written to disk.** A worker reads it through the core's range reader
  (`blob/RangeReader.kt`) and hands it to the platform's decoder as a stream. Where a platform's muxer can write only to
  a file, that file lives in the app's own protected container for the job's length and is removed before the result
  is sealed — the CR measures each platform and names any such case.

**9. The first job: every video gets a preview copy, and the gallery plays it.** A video is treated as an image is: the
gallery and the lightbox show the preview, and the original is fetched only to download it (owner 2026-11-12: "only a
preview, so that the whole video need not be streamed; the original only on download, as for images"). Tag
`memories-playback-v1`, derived role `PLAYBACK` on a medium (the role `PREVIEW` stays the video's poster frame), the
target of [ADR 0047](0047-inline-video-playback-range-streaming.md) § The small playable copy: H.264, at most 30 frames per
second, AAC-LC sound where the source has sound, at the size and bitrate that section fixes. **Every** video gets one,
stored videos too (decision 4's backfill); until a video's copy exists it plays its original, as today. The encoders are the platforms' own (MediaCodec, AVFoundation, Media Foundation,
GStreamer with `openh264enc` on Linux): no ffmpeg build, no GPL encoder in a release.

## Consequences

- **A member's agent works for everyone in their spaces.** A copy one member's device makes is there for every member
  and guest who can see the video; nothing new is revealed to them, since the copy shows what the original does.
- **What the operator newly sees.** The copy's size, which at a fixed bitrate tells a video's length more closely than
  its original's size did; that a video has a copy says nothing, since every video gets one; when a job ran and how long it was leased; which
  tags a worker claims, so that some session of an account can transcode. Content and results stay sealed. This is ADR
  0021's correlation-bounded leak, and [security-model](../crosscutting/security-model.md) names it per job type.
- **Storage.** Every video costs its copy's bitrate times its length on top of its original, charged to the tenant.
- **A headless host is the strongest key exposure veyq allows.** It is a device that is always on and unattended; the
  member chooses it and the docs say so plainly, beside the stay-signed-in levels.
- **ADR 0113's "no path adds a derivative later" is replaced** by decision 5 for derived roles only; the roles born
  with a medium are unchanged. ADR 0021 § 6's lease holder becomes the worker (decision 2); its § 3 body stays
  unbuilt (decision 7).
- **What comes after this ADR**, each a decision of its own: text read from a scan or a PDF (inbox #840, a derived
  `TEXT` role padded to a fixed set of sizes), embeddings for semantic search (ADR 0125 decision 8c, a fixed-size
  derived role), and faces (inbox #23) — the last only after the owner settles its legal and product questions:
  biometric data under GDPR Art. 9, the consent of the people a photo shows, and whether a space's people are shared.
- **Rejected — the uploader's app makes the copy at upload, born whole.** It would keep ADR 0113 untouched for new
  native uploads, but the web's uploads and every stored video still need a later path, and two paths to one copy is
  one too many; the uploading app claims its own job at once instead, through decision 4.
- **Rejected — a copy only above a threshold** (a file size, a bitrate, a codec no engine plays everywhere). The point
  is that the gallery never streams an original, whatever its size; a rule would also leave two ways to play a video
  and tell the operator which videos met it.
- **Rejected — the original kept as the preview when it already meets the target.** It saves a few small copies at
  the price of a second playback path and a visible exception; every video gets a copy instead.
- **Rejected — the headless host as an instance service.** Bundling it with the stack would make the operator the
  holder of a member's key, which is exactly what operator-blindness forbids.
