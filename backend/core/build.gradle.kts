// `:core` — the platform (ADR 0079): every `com.veyq.core.*` class, core's generated API and core's migrations.
plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
}

apply(from = "../gradle/module.gradle.kts")
