/*
 * The veyq core as a native library (libveyq.so; ADR 0127 decision 5; ADR 0123's Consequences, the bridge): its whole
 * surface, written once by hand. BridgeHeaderTest holds it to the header Kotlin/Native writes for the library
 * (libveyq_api.h): the same six functions with the same signatures. The JSON types are generated (bridge.rs, from the
 * core's serial descriptors); never write one by hand.
 *
 * Memory: a pointer the host passes in is read before the function returns. A pointer the core passes to a callback is
 * valid only during that call — the host copies what it keeps. JSON is UTF-8 and NUL-terminated; the byte payload beside
 * it carries bytes the UI shows (an avatar, a tile) so they never travel as base64, and is NULL with length 0 where
 * there are none.
 *
 * Threads: every answer, update and host request arrives on a core thread, never the host's UI thread; the host hands
 * it to its own loop. veyq_call, veyq_watch, veyq_unwatch and veyq_reply may be called from any thread. The host
 * function must not wait for the core (it answers later, through veyq_reply, from any thread): a host request may come
 * while the core waits for that very answer.
 *
 * Before veyq_init and after veyq_shutdown the other functions do nothing: a call is not answered, a watch answers 0.
 */
#ifndef VEYQ_BRIDGE_H
#define VEYQ_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

/* An answer to one call, or one update of a watch: an `Answer` or an `Update`. */
typedef void (*veyq_answer)(void *ctx, const char *json, const uint8_t *bytes, size_t len);

/* What the core asks of the host (a `HostRequest`), answered with veyq_reply under the same token. */
typedef void (*veyq_host)(void *ctx, uint64_t token, const char *json, const uint8_t *bytes, size_t len);

/*
 * Starts the core once per process: config is a `Config`, host the one function the core calls for what only the
 * device does. 0: started; 1: already started; 2: the config or the host function was refused; 3: this CPU has no AES
 * instructions, which the suite's AES-256-GCM needs (the app says so and does not start).
 */
int32_t veyq_init(const char *config, veyq_host host, void *host_ctx);

/* One action, a `Call`, answered once through answer(ctx, ...) — asynchronously, on a core thread. */
void veyq_call(const char *request, const uint8_t *bytes, size_t len, void *ctx, veyq_answer answer);

/*
 * Observes one model's state, a `Watched`: the state now arrives at once through update(ctx, ...), then each change.
 * Returns the watch's id for veyq_unwatch, or 0 when the request was refused (nothing arrives then).
 */
uint64_t veyq_watch(const char *request, void *ctx, veyq_answer update);

/*
 * Ends watch id. An update already under way may still arrive after it returns, so the host keeps a ctx it handed in
 * valid until veyq_shutdown.
 */
void veyq_unwatch(uint64_t id);

/* The host's answer to the host request of token, a `HostReply`. */
void veyq_reply(uint64_t token, const char *json, const uint8_t *bytes, size_t len);

/* Ends the core: every watch and every call under way; the host calls it when the application quits. */
void veyq_shutdown(void);

#endif
