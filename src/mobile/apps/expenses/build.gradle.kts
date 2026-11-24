// Expenses's module (ADR 0155 decision 7.1; ADR 0079): its rules, its screen models and its part of the
// platform's registry, over the platform module alone — never another application, never the composition
// (scripts/validate_mobile_module_graph.py). Its targets, hierarchy and API client (every spec of
// contracts/api/expenses/, into `…mobile.api.expenses`) are the core modules' convention (src/mobile/build.gradle.kts).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Expenses' runners for `@veyq/core-js`'s check (ADR 0155 decision 6): a binary of this module's own, never an export of
    // the shipped module — the format's reader of plaintext bytes (`DecodeRun.kt`), which no façade call reaches on its own.
    // `:composition:jsPackage` writes it into the package's `dist/conformance/expenses/`.
    js {
        val main = compilations.getByName("main")
        val conformance = compilations.create("conformance") { associateWith(main) }
        binaries.library(conformance)
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
