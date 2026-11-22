// Expenses's module (ADR 0155 decision 7.1; ADR 0079): its rules, its screen models and its part of the
// platform's registry, over the platform module alone — never another application, never the composition
// (scripts/validate_mobile_module_graph.py). Its targets, hierarchy and API client (every spec of
// contracts/api/expenses/, into `…mobile.api.expenses`) are the core modules' convention (src/mobile/build.gradle.kts).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
