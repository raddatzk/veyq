plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

// The core's JavaScript target (ADR 0155 decisions 3 and 6) is a library with no JavaScript environment: Gradle compiles,
// pnpm installs, bundles and tests (`src/packages/core-js/`). A `nodejs()` or `browser()` environment would make even
// the link task depend on Kotlin's own Node, Yarn and npm install (measured 2026-11-20, cr-core-js-target phase 1), so
// none is chosen, and the root plugin the environment would have applied — whose extension the link's sync task reads —
// is applied here. Its setup tasks never download (the settings refuse the repository a download adds,
// `FAIL_ON_PROJECT_REPOS`), and a task graph that reaches any of Kotlin's Node, Yarn or npm tasks fails before it runs.
apply<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin>()
the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().download.set(false)
}
val kotlinJsTooling = setOf("kotlinNodeJsSetup", "kotlinYarnSetup", "kotlinNpmInstall", "kotlinStoreYarnLock", "kotlinRestoreYarnLock", "kotlinUpgradeYarnLock", "kotlinNpmCachesSetup", "kotlinKotlinNpmCachesSetup", "rootPackageJson", "kotlinPackageJsonUmbrella")
gradle.taskGraph.whenReady {
    val reached = allTasks.filter { it.name in kotlinJsTooling || it.name.endsWith("PackageJson") }.map { it.path }
    if (reached.isNotEmpty()) throw GradleException("Kotlin's own Node/Yarn/npm tooling never runs (ADR 0155 decision 6); this build reached $reached")
}
