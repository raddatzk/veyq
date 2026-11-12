/*
 * The Secret Service for the core's LinuxDeviceKeys (linuxMain, session/LinuxDeviceKeys.kt; ADR 0129's
 * Consequences): the bind keys, the unlock key and the small secrets are items of the default
 * collection under one schema, `me.raddatz.veyq.DeviceKey`, told apart by its one attribute, `alias`.
 *
 * libsecret is reached by dlopen, never linked: the library links against Kotlin/Native's old sysroot with no glib or
 * libsecret header or stub, carries no NEEDED entry for libsecret, and runs where there is none — then nothing loads
 * and the core answers as it does without a store. So the few declarations it uses are this header's own, copied from
 * libsecret's and glib's stable ABI (libsecret 0.18+, glib 2.x): GError's three leading fields, SecretSchema whole
 * (its reserved tail included, which libsecret reads), and the signatures of the functions looked up below. GCancellable
 * stays opaque.
 *
 * Every libsecret call is made here, in C; only integers and UTF-8 strings cross into Kotlin (secret.def's headerFilter
 * exposes this header alone). A function returns 0, or 1 with the GError's message copied into [err].
 */
#ifndef VEYQ_SECRET_H
#define VEYQ_SECRET_H

#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* glib's GError: its layout is ABI, and only the message is read. */
typedef struct {
    uint32_t domain;
    int code;
    char *message;
} veyq_gerror;

/* libsecret's SecretSchema (secret-schema.h): 32 attributes, then the reserved fields it must carry. */
typedef struct {
    const char *name;
    int type; /* SECRET_SCHEMA_ATTRIBUTE_STRING = 0 */
} veyq_schema_attribute;

typedef struct {
    const char *name;
    int flags; /* SECRET_SCHEMA_NONE = 0 */
    veyq_schema_attribute attributes[32];
    int reserved;
    void *reserved1, *reserved2, *reserved3, *reserved4, *reserved5, *reserved6, *reserved7;
} veyq_schema;

static const veyq_schema VEYQ_SECRET_SCHEMA = { "me.raddatz.veyq.DeviceKey", 0, { { "alias", 0 }, { NULL, 0 } }, 0,
                                                NULL, NULL, NULL, NULL, NULL, NULL, NULL };

/* SECRET_SERVICE_OPEN_SESSION: secret_service_get_sync opens a session, a call the service itself must answer. */
#define VEYQ_SECRET_SERVICE_OPEN_SESSION 2

/* The functions the store uses, looked up in the loaded library (glib's and gobject's through its dependencies). The
 * attributes are the variadic NULL-terminated (name, value) list of libsecret's non-v functions: no GHashTable needed. */
typedef struct {
    void *handle;
    int (*store)(const veyq_schema *, const char *collection, const char *label, const char *password, void *cancellable,
                 veyq_gerror **, ...);
    char *(*lookup)(const veyq_schema *, void *cancellable, veyq_gerror **, ...);
    int (*clear)(const veyq_schema *, void *cancellable, veyq_gerror **, ...);
    void (*password_free)(char *);
    void *(*service_get)(int flags, void *cancellable, veyq_gerror **);
    void (*object_unref)(void *);
    void (*error_free)(veyq_gerror *);
} veyq_secret;

static inline void veyq_secret_message(veyq_secret *s, veyq_gerror *e, const char *fallback, char *err, int32_t cap) {
    if (err && cap > 0) {
        const char *m = e && e->message ? e->message : fallback;
        strncpy(err, m, (size_t)cap - 1);
        err[cap - 1] = '\0';
    }
    if (e) s->error_free(e);
}

/*
 * libsecret loaded by its [soname] ("libsecret-1.so.0"), every function found, or NULL — the library absent, or one
 * without a function this header uses. The handle lives as long as the process (never dlclosed: glib cannot be
 * unloaded). [err] says what failed.
 */
static inline veyq_secret *veyq_secret_load(const char *soname, char *err, int32_t cap) {
    void *h = dlopen(soname, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        veyq_secret_message(NULL, NULL, dlerror(), err, cap);
        return NULL;
    }
    veyq_secret *s = (veyq_secret *)calloc(1, sizeof(veyq_secret));
    if (!s) {
        dlclose(h);
        veyq_secret_message(NULL, NULL, "out of memory", err, cap);
        return NULL;
    }
    s->handle = h;
    *(void **)&s->store = dlsym(h, "secret_password_store_sync");
    *(void **)&s->lookup = dlsym(h, "secret_password_lookup_sync");
    *(void **)&s->clear = dlsym(h, "secret_password_clear_sync");
    *(void **)&s->password_free = dlsym(h, "secret_password_free");
    *(void **)&s->service_get = dlsym(h, "secret_service_get_sync");
    *(void **)&s->object_unref = dlsym(h, "g_object_unref");
    *(void **)&s->error_free = dlsym(h, "g_error_free");
    if (!s->store || !s->lookup || !s->clear || !s->password_free || !s->service_get || !s->object_unref || !s->error_free) {
        veyq_secret_message(NULL, NULL, "libsecret lacks a function veyq uses", err, cap);
        free(s);
        dlclose(h);
        return NULL;
    }
    return s;
}

/* Whether a Secret Service answers on the session bus: a session opened with it (no item read, so no unlock asked). */
static inline int32_t veyq_secret_service(veyq_secret *s, char *err, int32_t cap) {
    veyq_gerror *e = NULL;
    void *service = s->service_get(VEYQ_SECRET_SERVICE_OPEN_SESSION, NULL, &e);
    if (!service) {
        veyq_secret_message(s, e, "no Secret Service", err, cap);
        return 1;
    }
    s->object_unref(service);
    if (e) s->error_free(e);
    return 0;
}

/* Stores [value] as the item [alias] of the default collection, replacing one there. */
static inline int32_t veyq_secret_store(veyq_secret *s, const char *alias, const char *label, const char *value, char *err,
                                        int32_t cap) {
    veyq_gerror *e = NULL;
    int ok = s->store(&VEYQ_SECRET_SCHEMA, NULL, label, value, NULL, &e, "alias", alias, (char *)NULL);
    if (!ok || e) {
        veyq_secret_message(s, e, "not stored", err, cap);
        return 1;
    }
    return 0;
}

/* The item [alias]'s value into *out (NULL: there is none), which the caller hands to veyq_secret_free. */
static inline int32_t veyq_secret_lookup(veyq_secret *s, const char *alias, char **out, char *err, int32_t cap) {
    veyq_gerror *e = NULL;
    *out = s->lookup(&VEYQ_SECRET_SCHEMA, NULL, &e, "alias", alias, (char *)NULL);
    if (e) {
        if (*out) s->password_free(*out);
        *out = NULL;
        veyq_secret_message(s, e, "not read", err, cap);
        return 1;
    }
    return 0;
}

/* Clears and frees a value veyq_secret_lookup gave (libsecret's secret_password_free zeroes it first). */
static inline void veyq_secret_free(veyq_secret *s, char *value) {
    if (value) s->password_free(value);
}

/* Removes the item [alias]; none there is no failure. */
static inline int32_t veyq_secret_clear(veyq_secret *s, const char *alias, char *err, int32_t cap) {
    veyq_gerror *e = NULL;
    s->clear(&VEYQ_SECRET_SCHEMA, NULL, &e, "alias", alias, (char *)NULL);
    if (e) {
        veyq_secret_message(s, e, "not cleared", err, cap);
        return 1;
    }
    return 0;
}

#endif
