package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.config.VeyqConfig
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * `veyq.media.*` — the Memories application's own configuration (inbox #342): a mapping of its own, in its module,
 * so core's [VeyqConfig] names no key of an application (ADR 0079). A prefix below `veyq` rather than a second
 * `veyq` root, whose tree would overlap core's. The key names did not change, so neither did compose or Helm.
 *
 * **The `@WithDefault`s are the defaults — no properties file holds these keys.** SmallRye refuses a `veyq.*` key no
 * mapping claims (SRCFG00050), and `:core`'s base `application.properties` is also what core boots with alone (its
 * tests), where this mapping does not exist; a key there fails that boot. SmallRye registers a mapping's defaults as
 * config defaults, so the purge's `@Scheduled` expression resolves from them too (`MediaTrashSliceTest` pins both).
 */
@ConfigMapping(prefix = "veyq.media")
interface MemoriesMediaConfig {
    fun trash(): Trash

    interface Trash {
        /** Trashed-media restorable window before [MediaTrashPurge] hard-deletes it (uc.memory.delete-media). */
        @WithDefault("P30D")
        fun retention(): Duration

        /** Read by `@Scheduled(every = "{veyq.media.trash.purge.every}")` as a raw expression; declared for the inventory. */
        fun purge(): VeyqConfig.Interval
    }
}
