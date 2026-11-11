/*
 * libveyq.so run as a host runs it, from C through bridge.h alone (`run.py bridge`): the core starts, a watch gets the
 * way in's gate, a call is answered, the host's store is asked and answered from the host's own thread, a call that is
 * no call is refused, and the core shuts down. With VEYQ_LIVE it signs in as the seeded principal (VEYQ_EMAIL,
 * VEYQ_PASSWORD, VEYQ_SECRET_KEY of the env file VEYQ_ENV_FILE names) over the library's own HTTP client and waits for the
 * shell's lists. Exit 0 when every step answered as expected; each step prints what came back.
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "bridge.h"

/* ---- the host's store: names and values in memory, answered from a thread of the host's own ---- */

#define SLOTS 64
static char *names[SLOTS], *values[SLOTS];
static pthread_mutex_t store_lock = PTHREAD_MUTEX_INITIALIZER;
static int asked = 0;

static char *field(const char *json, const char *key) {
    char pat[64];
    snprintf(pat, sizeof pat, "\"%s\":\"", key);
    const char *s = strstr(json, pat);
    if (!s) return NULL;
    s += strlen(pat);
    size_t n = 0;
    char *out = malloc(strlen(s) + 1);
    for (; *s && *s != '"'; s++) {
        char c = *s;
        if (c == '\\' && s[1]) {
            c = *++s;
            if (c == 'n') c = '\n';
            else if (c == 't') c = '\t';
        }
        out[n++] = c;
    }
    out[n] = 0;
    return out;
}

static void quote(const char *in, char *out) { /* JSON string body: the values here are the core's own */
    for (; *in; in++) {
        if (*in == '"' || *in == '\\') *out++ = '\\';
        if (*in == '\n') { *out++ = '\\'; *out++ = 'n'; continue; }
        if (*in == '\t') { *out++ = '\\'; *out++ = 't'; continue; }
        *out++ = *in;
    }
    *out = 0;
}

struct ask { uint64_t token; char *json; };

static void *answer_store(void *p) {
    struct ask *a = p;
    char *name = field(a->json, "name");
    char reply[8192];
    pthread_mutex_lock(&store_lock);
    asked++;
    int i, at = -1;
    for (i = 0; i < SLOTS; i++) if (names[i] && name && strcmp(names[i], name) == 0) at = i;
    if (strstr(a->json, "\"Store.Read\"")) {
        if (at < 0) strcpy(reply, "{\"type\":\"Value\",\"value\":null}");
        else { char q[7000]; quote(values[at], q); snprintf(reply, sizeof reply, "{\"type\":\"Value\",\"value\":\"%s\"}", q); }
    } else if (strstr(a->json, "\"Store.Write\"")) {
        char *value = field(a->json, "value");
        if (at < 0) for (i = 0; i < SLOTS; i++) if (!names[i]) { at = i; names[i] = strdup(name); break; }
        free(values[at]);
        values[at] = value;
        strcpy(reply, "{\"type\":\"Done\"}");
    } else {
        if (at >= 0) { free(names[at]); free(values[at]); names[at] = values[at] = NULL; }
        strcpy(reply, "{\"type\":\"Done\"}");
    }
    pthread_mutex_unlock(&store_lock);
    veyq_reply(a->token, reply, NULL, 0);
    free(name);
    free(a->json);
    free(a);
    return NULL;
}

static void host(void *ctx, uint64_t token, const char *json, const uint8_t *bytes, size_t len) {
    (void)ctx; (void)bytes; (void)len;
    struct ask *a = malloc(sizeof *a);
    a->token = token;
    a->json = strdup(json); /* valid only during this call */
    pthread_t t;
    pthread_create(&t, NULL, answer_store, a);
    pthread_detach(t);
}

/* ---- what arrives: the last answer, and every update ---- */

static pthread_mutex_t seen_lock = PTHREAD_MUTEX_INITIALIZER;
static char *last_answer, *last_update;
static int updates = 0;

static void on_answer(void *ctx, const char *json, const uint8_t *bytes, size_t len) {
    (void)ctx; (void)bytes; (void)len;
    pthread_mutex_lock(&seen_lock);
    free(last_answer);
    last_answer = strdup(json);
    pthread_mutex_unlock(&seen_lock);
}

static void on_update(void *ctx, const char *json, const uint8_t *bytes, size_t len) {
    (void)bytes; (void)len;
    pthread_mutex_lock(&seen_lock);
    free(last_update);
    last_update = strdup(json);
    updates++;
    pthread_mutex_unlock(&seen_lock);
    printf("  update [%s] %.160s\n", (const char *)ctx, json);
}

static int wait_for(char **slot, const char *needle, int seconds) {
    for (int i = 0; i < seconds * 20; i++) {
        pthread_mutex_lock(&seen_lock);
        int ok = *slot && strstr(*slot, needle);
        pthread_mutex_unlock(&seen_lock);
        if (ok) return 1;
        usleep(50000);
    }
    return 0;
}

static int failures = 0;

/* KEY's value in the env file at path (`KEY=value`, quotes around it dropped), as the JVM's live tests read it. */
static char *env_value(const char *path, const char *key) {
    FILE *f = fopen(path, "r");
    if (!f) return NULL;
    static char line[2048];
    char *found = NULL;
    while (!found && fgets(line, sizeof line, f)) {
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        char *k = line, *v = eq + 1;
        while (*k == ' ') k++;
        char *ke = k + strlen(k);
        while (ke > k && ke[-1] == ' ') *--ke = 0;
        if (strcmp(k, key) != 0) continue;
        v[strcspn(v, "\r\n")] = 0;
        while (*v == ' ') v++;
        size_t n = strlen(v);
        while (n && v[n - 1] == ' ') v[--n] = 0;
        if (n >= 2 && (v[0] == '"' || v[0] == '\'') && v[n - 1] == v[0]) { v[n - 1] = 0; v++; }
        found = strdup(v);
    }
    fclose(f);
    return found;
}

static void expect(int ok, const char *what) {
    printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

static void call(const char *json) {
    pthread_mutex_lock(&seen_lock);
    free(last_answer);
    last_answer = NULL;
    pthread_mutex_unlock(&seen_lock);
    veyq_call(json, NULL, 0, NULL, on_answer);
}

int main(void) {
    expect(veyq_init("{\"device\":\"smoke\"}", host, NULL) == 0, "veyq_init starts the core");
    expect(veyq_init("{\"device\":\"smoke\"}", host, NULL) == 1, "a second veyq_init is refused");

    uint64_t gate = veyq_watch("{\"type\":\"Session\"}", "session", on_update);
    expect(gate != 0, "veyq_watch(Session) gives an id");
    expect(wait_for(&last_update, "\"Starting\"", 10), "the gate's first state arrives at once");
    expect(veyq_watch("{\"type\":\"Nothing\"}", "none", on_update) == 0, "a watch of nothing is refused");

    call("{\"type\":\"Session.Start\"}");
    expect(wait_for(&last_answer, "{\"type\":\"Done\"}", 30), "Session.Start is answered Done");
    expect(wait_for(&last_update, "\"SignIn\"", 10), "the gate shows the sign-in");
    expect(asked > 0, "the core asked the host's store");

    call("{\"type\":\"Search.Query\",\"query\":\"x\"}");
    expect(wait_for(&last_answer, "NO_TENANT_OPEN", 10), "Search before a tenant is open is refused");
    call("not json");
    expect(wait_for(&last_answer, "NOT_A_CALL", 10), "a request that is no call is refused");

    const char *live = getenv("VEYQ_LIVE");
    if (live) {
        static char req[4096], e[512], p[512], k[512];
        const char *file = getenv("VEYQ_ENV_FILE");
        char *email = file ? env_value(file, "VEYQ_EMAIL") : NULL, *password = file ? env_value(file, "VEYQ_PASSWORD") : NULL,
             *secret = file ? env_value(file, "VEYQ_SECRET_KEY") : NULL;
        if (!email || !password || !secret) { printf("FAIL the env file names no seeded principal\n"); return 1; }
        quote(email, e);
        quote(password, p);
        quote(secret, k);
        snprintf(req, sizeof req, "{\"type\":\"Session.SignIn\",\"server\":\"%s\",\"email\":\"%s\",\"password\":\"%s\",\"secretKey\":\"%s\",\"remember\":false}", live, e, p, k);
        uint64_t shell = veyq_watch("{\"type\":\"Shell\"}", "shell", on_update);
        call(req);
        expect(wait_for(&last_answer, "{\"type\":\"Done\"}", 120), "Session.SignIn is answered Done");
        expect(wait_for(&last_update, "\"PERSONAL\"", 60), "the shell's lists arrive with the personal space");
        veyq_unwatch(shell);
    }

    veyq_unwatch(gate);
    int before = updates;
    /* a sign-in nobody answers moves the gate (working, then unreachable), which no watch may report now */
    call("{\"type\":\"Session.SignIn\",\"server\":\"http://127.0.0.1:9\",\"email\":\"a@b.c\",\"password\":\"x\",\"secretKey\":\"x\",\"remember\":false}");
    wait_for(&last_answer, "Done", 30);
    usleep(300000);
    expect(updates == before, "an ended watch gets no more updates");
    veyq_shutdown();
    printf("%s\n", failures ? "the library did not answer as expected" : "the library answered every step");
    return failures ? 1 : 0;
}
