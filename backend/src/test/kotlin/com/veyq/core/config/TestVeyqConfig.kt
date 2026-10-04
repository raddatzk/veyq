package com.veyq.core.config

import io.quarkus.runtime.configuration.DurationConverter
import io.smallrye.config.SmallRyeConfigBuilder
import java.time.Duration

/**
 * Builds a real [VeyqConfig] mapping from an in-memory property map for pure-JVM unit tests that construct a
 * bean directly (no CDI). Keys are the `veyq.*` property names; anything omitted falls back to the interface's
 * `@WithDefault`s — so a caller only sets the knob under test.
 *
 * The Quarkus [DurationConverter] is registered because the interface's `@WithDefault`s use Quarkus's relaxed
 * duration format (e.g. `1h`), which the plain SmallRye Duration converter (ISO-8601 only) would reject.
 */
fun testVeyqConfig(props: Map<String, String> = emptyMap()): VeyqConfig =
    SmallRyeConfigBuilder()
        .withConverter(Duration::class.java, 100, DurationConverter())
        .withMapping(VeyqConfig::class.java)
        .withDefaultValues(props)
        .build()
        .getConfigMapping(VeyqConfig::class.java)
