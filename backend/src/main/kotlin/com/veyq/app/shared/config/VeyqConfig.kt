package com.veyq.app.shared.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/**
 * The single typed model of the project-owned `veyq.*` configuration (styleguide §8, audit P3c / cr-0134).
 * Replaces the ~28 scattered per-value `@ConfigProperty` injections with one `@ConfigMapping` so every knob is
 * discoverable in one place, typed, and defaulted here rather than repeated at each injection site.
 *
 * Property names are derived from the method path in kebab-case, so `tasks().maxAttempts()` ⇒
 * `veyq.tasks.max-attempts`. Values still come from `application.properties` (+ `%dev`/`%test` overrides); the
 * `@WithDefault`s mirror those files and the previous annotation defaults — they are the fallback when a key is
 * absent, not a second source of truth.
 *
 * Framework config (`quarkus.*`, `mp.jwt.*`, …) stays on direct injection (styleguide §8) and is not modeled here.
 *
 * The `*.purge.every` / `*.reconcile.every` intervals are consumed by the sibling `@Scheduled(every = "{…}")`
 * beans as raw property expressions (a `@Scheduled` string cannot read a mapping), but they are declared here too
 * so this interface stays the complete inventory of `veyq.*`.
 */
@ConfigMapping(prefix = "veyq")
interface VeyqConfig {
    fun version(): Version
    fun release(): Release
    fun capabilities(): Capabilities
    fun tenant(): Tenant
    fun session(): Session
    fun recovery(): Recovery
    fun pairing(): Pairing
    fun scope(): Scope
    fun media(): Media
    fun node(): Node
    fun blob(): Blob
    fun blobStore(): BlobStore
    fun storeReclaim(): StoreReclaim
    fun tasks(): Tasks
    fun ratelimit(): RateLimit
    fun share(): Share
    fun security(): Security

    /** A scheduled cleanup interval (`*.purge.every` / `*.reconcile.every`); read by the `@Scheduled` sibling. */
    interface Interval {
        @WithDefault("1h")
        fun every(): Duration
    }

    interface Version {
        /** Hard compatibility-gate major version. */
        @WithDefault("1")
        fun major(): Int

        /** Informational build version. */
        @WithDefault("1")
        fun build(): Int
    }

    /**
     * The release the running image was published under — a **different** number from [Version], never merged
     * with it (ADR 0057 §4). [Version] answers *"can these two sides talk to each other?"* and is pre-auth;
     * this answers *"which bits are running, and what do I roll back to?"* and is Server-Owner-only, because a
     * precise release number is what maps an instance to a list of known vulnerabilities.
     */
    interface Release {
        /**
         * `<major>.<minor>` — the tree's `VERSION` at the commit that published this image, bound from the
         * image's `VEYQ_VERSION` in `application.properties`. **Absent** for any build the release pipeline
         * never stamped (a local `docker build`, a `quarkusDev` run), and that absence is reported as itself:
         * a synthesised number is the one an operator would later try to roll back *to*
         * (uc.admin.identify-instance-release A1).
         */
        fun version(): Optional<String>
    }

    interface Capabilities {
        /** Operator off-switch: capability ids to disable (absent ⇒ all enabled). */
        fun disabled(): Optional<List<String>>
    }

    interface Tenant {
        fun invite(): Invite
        fun ingress(): Ingress

        /**
         * The DB→reverse-proxy routing projection (ADR 0063). Both values absent is the `%dev`/`%test` default
         * and a clean no-op: a self-hoster running the backend without Traefik must not need either.
         */
        interface Ingress {
            /**
             * Where `TenantIngressProjector` writes the Traefik file-provider config. **Absent ⇒ the projector
             * does nothing at all** — not an error, and not a degraded mode: an instance with no proxy in front
             * of it has nothing to project into.
             */
            fun dynamicConfigPath(): Optional<String>

            /**
             * The deployment-time bootstrap domain (`VEYQ_DOMAIN`), which the compose file already routes with
             * static labels. The projector **excludes** it, so it never emits a second router for a domain that
             * already has one — and it is the domain a request that resolves to no tenant is signed against.
             */
            fun bootstrapDomain(): Optional<String>
        }

        interface Invite {
            /** Owner-invite validity window. */
            @WithDefault("P7D")
            fun ttl(): Duration

            /** How long terminal (accepted/expired) invites are kept before purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            /** Dev-only JSONL sink for invite links (absent in prod). */
            fun devFile(): Optional<String>

            fun purge(): Interval
        }
    }

    interface Session {
        fun cookie(): Cookie
        fun purge(): Interval

        interface Cookie {
            /** `Secure` attribute on the refresh cookie (off in `%dev`/`%test`). */
            @WithDefault("true")
            fun secure(): Boolean
        }
    }

    /** Password recovery (ADR 0054) — one-time token lifetimes. */
    interface Recovery {
        fun token(): Token
        fun purge(): Interval

        interface Token {
            /** Recovery-link validity. Much shorter than an invite's: it is redeemed at the mailbox, not later. */
            @WithDefault("PT1H")
            fun ttl(): Duration

            /** How long spent/expired rows are kept, so a replay reads as spent rather than unknown. */
            @WithDefault("P7D")
            fun retention(): Duration
        }
    }

    /** Device linking (ADR 0059) — how long a pairing channel lives, and how long a spent one is kept. */
    interface Pairing {
        fun channel(): Channel
        fun purge(): Interval

        interface Channel {
            /** How long a channel stays open. Shorter than anything else here on purpose: both devices are in
             *  the person's hands for the whole ceremony, so this bounds an abandoned one, not a slow user. */
            @WithDefault("PT10M")
            fun ttl(): Duration

            /** How long terminal rows are kept, so a replayed handle reads as spent rather than unknown
             *  (uc.account.onboard-device E1). */
            @WithDefault("P1D")
            fun retention(): Duration
        }
    }

    interface Scope {
        fun delete(): Delete

        interface Delete {
            /** Group soft-delete grace window before the hard purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    interface Node {
        fun trash(): Trash

        interface Trash {
            /** Trashed-subtree restorable window before `NodeSubtreePurge` hard-erases it (uc.memory.delete-node). */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    interface Media {
        fun trash(): Trash
        fun upload(): Upload

        interface Trash {
            /** Trashed-media restorable window before the hard purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }

        interface Upload {
            /** How long an OPEN upload session is kept before the sweep reclaims its row + staged chunk objects.
             *  Deliberately far shorter than the trash/scope/invite windows, and the floor is **measured, not
             *  chosen**: a session's presigned PUTs are signed once at `start` under
             *  `veyq.blob-store.presign-ttl-seconds` and nothing re-signs them, so no client can write another
             *  byte past that TTL. What this buffers is only a PUT that started inside the TTL and is still
             *  streaming — one 256 KiB web chunk. */
            @WithDefault("PT1H")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    /** `veyq.store-reclaim.*` — the single store-object deleter (ADR 0062). */
    interface StoreReclaim {
        /** How often the sweep drains the reclaim list. This is also the **latency the design accepts**: bytes
         *  linger up to one interval after the rows that named them are gone (Decision 3). */
        fun sweep(): Interval

        /** Refusals a row survives before it is stamped abandoned and its bytes become a countable leak. An
         *  unbounded retry was rejected in Decision 5 — a queue that grows silently is the same disease one
         *  level up. */
        @WithDefault("5")
        fun maxAttempts(): Int

        /** Base retry delay after a refusal, doubling per attempt. The sweep interval is the resolution floor,
         *  so a value below it simply means "next tick"; at the defaults a dead store is given up on after
         *  roughly eight hours. */
        @WithDefault("PT15M")
        fun backoff(): Duration

        /** Rows one tick attempts. Bounds the batch handed to the store, not the list — the rest waits a tick. */
        @WithDefault("500")
        fun batchSize(): Int
    }

    interface Blob {
        /** Per-blob declared byte cap. */
        @WithDefault("2147483648")
        fun maxBytes(): Long

        /** Per-blob declared chunk cap. */
        @WithDefault("100000")
        fun maxChunks(): Int
    }

    interface BlobStore {
        /** Internal S3 endpoint (blank/absent fails startup — media is not gracefully degraded). */
        fun endpoint(): Optional<String>

        /** Client-reachable URL used purely to SIGN presigned URLs (blank ⇒ endpoint). */
        fun publicEndpoint(): Optional<String>

        fun accessKey(): Optional<String>

        fun secretKey(): Optional<String>

        @WithDefault("veyq")
        fun bucket(): String

        @WithDefault("us-east-1")
        fun region(): String

        @WithDefault("600")
        fun presignTtlSeconds(): Int
    }

    interface Tasks {
        fun lease(): Lease

        /** Max attempts before a job is dead-lettered. */
        @WithDefault("5")
        fun maxAttempts(): Int

        fun grantDelivery(): GrantDelivery

        interface Lease {
            /** Lease TTL granted on job claim. */
            @WithDefault("PT5M")
            fun ttl(): Duration
        }

        interface GrantDelivery {
            fun reconcile(): Interval
        }
    }

    /** `veyq.ratelimit.*` — the boundary rate limiters. Values live here, never in an annotation: the
     *  `@RateLimit(key)` posture (ADR 0051) declares the *axis* so a self-hosted instance can still tune the
     *  *numbers* ([invariant] self-hostable). */
    interface RateLimit {
        /** `veyq.ratelimit.auth.*` — the per-IP bound. The prefix is historical (it began as the auth-only
         *  limiter); the bound is `@RateLimit(key = IP)` wherever it is declared. Renaming it would break every
         *  self-hosted instance's config file for no behavioural gain, so it stays. */
        fun auth(): Auth

        /** `veyq.ratelimit.user.*` — the per-principal bound for `@RateLimit(key = USER)`. Deliberately generous:
         *  an authenticated caller is attributable and revocable, so this bounds runaway automation, not abuse. */
        fun user(): User

        /** `veyq.ratelimit.invite.*` — the member-invite mail limiter (cr-email-invite-send-hardening): the real
         *  anti-bombing guard, keyed per-(tenant,recipient) AND per-tenant, applied in the handler before the mint.
         *  Defaults are **deliberately set, not empirically calibrated — revidierbar**: 3 per recipient / 20 per
         *  tenant, per hour. */
        fun invite(): Invite

        /** `veyq.ratelimit.recovery.*` — the password-recovery limiter (cr-account-recovery, ADR 0054), keyed
         *  per-ADDRESS and per-INSTANCE. Deliberately not per-tenant like [invite]: an account is tenant-global,
         *  so a tenant key would bound neither one address nor the instance. Defaults deliberately set. */
        fun recovery(): Recovery

        interface Recovery {
            /** The anti-bombing guard: recovery mails one **address** may trigger per window. */
            @WithDefault("3")
            fun perAddressMaxRequests(): Int

            /** The volume cap against a scraper walking a list of addresses. */
            @WithDefault("60")
            fun perInstanceMaxRequests(): Int

            @WithDefault("3600")
            fun windowSeconds(): Long
        }

        interface Auth {
            @WithDefault("60")
            fun maxRequests(): Long

            @WithDefault("60")
            fun windowSeconds(): Long
        }

        interface User {
            @WithDefault("600")
            fun maxRequests(): Long

            @WithDefault("60")
            fun windowSeconds(): Long
        }

        interface Invite {
            /** The tighter, primary guard: how many invites one tenant may send to the **same recipient** per window. */
            @WithDefault("3")
            fun perRecipientMaxRequests(): Int

            /** The volume cap against an owner-as-sender: total invites a **tenant** may send per window. */
            @WithDefault("20")
            fun perTenantMaxRequests(): Int

            @WithDefault("3600")
            fun windowSeconds(): Long
        }
    }

    /** `veyq.security.*` — operator-side security knobs. */
    interface Security {
        /** Path to the file holding the base64 32-byte KEK that seals server-held config secrets at rest
         *  ([ADR 0050](../../../../../../../docs/decisions/0050-operator-config-secret-encryption.md) amendment).
         *  The server **generates and manages** this file on first start (`0600`, base64 32-byte key) if it does
         *  not exist; an operator supplying their own key mounts it and points this at it (the K8s/Docker-secret
         *  shape). Default `config-secret.key` (relative to the process CWD) — put it on a **persisted volume** in
         *  a container, else every recreate loses the key (the DB fingerprint tripwire then flags it loudly). The
         *  old env **value** `veyq.security.config-secret-key` is gone. */
        @WithDefault("config-secret.key")
        fun configSecretFile(): String
    }

    /** `veyq.share.upload.rate.*` — the uploader-link rate limiter. */
    interface Share {
        fun upload(): Upload

        /** cr-0165: the brute-force bound on wrong share-password attempts (per share, per window). Distinct budget
         *  from [upload] — an order of magnitude tighter, since a password guess is an attack and an upload is not. */
        fun password(): Password

        /** `veyq.share.identity.rate.*` — the bound on anonymous contributor-identity creation, the one guest call
         *  that writes a row before any upload exists. A real guest names itself **once** per share, so this is
         *  deliberately much tighter than [upload] and still far above honest traffic. */
        fun identity(): Identity

        interface Upload {
            fun rate(): Rate

            interface Rate {
                @WithDefault("300")
                fun maxPerWindow(): Int

                @WithDefault("60")
                fun windowSeconds(): Long
            }
        }

        interface Identity {
            fun rate(): Rate

            interface Rate {
                @WithDefault("30")
                fun maxPerWindow(): Int

                @WithDefault("3600")
                fun windowSeconds(): Long
            }
        }

        interface Password {
            fun rate(): Rate

            interface Rate {
                @WithDefault("10")
                fun maxPerWindow(): Int

                @WithDefault("60")
                fun windowSeconds(): Long
            }
        }
    }
}
