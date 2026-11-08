// The veyq app for Android (ADR 0123): a native Compose UI on `core`. AGP 9 compiles Kotlin itself (built-in Kotlin),
// so only the Compose compiler plugin is applied beside it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The web's colour roles, the brand mark and the launcher icon as Android resources (ADR 0123 decision 7), generated
// from src/web/src/styles/base.css and src/web/src/assets/brand by src/mobile/theme/generate_theme.py.
abstract class GenerateThemeRes : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:InputDirectory abstract val brand: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @get:javax.inject.Inject abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        exec.exec { commandLine("python3", script.get().asFile.absolutePath, "--android-res", outDir.get().asFile.absolutePath) }
    }
}

val generateThemeRes = tasks.register<GenerateThemeRes>("generateThemeRes") {
    group = "veyq"
    description = "Generates the theme's Android resources from the web's tokens and brand mark."
    script.set(rootDir.resolve("theme/generate_theme.py"))
    tokens.set(rootDir.resolve("../web/src/styles/base.css"))
    brand.set(rootDir.resolve("../web/src/assets/brand"))
}

androidComponents {
    onVariants { variant -> variant.sources.res?.addGeneratedSourceDirectory(generateThemeRes, GenerateThemeRes::outDir) }
}

android {
    namespace = "me.raddatz.veyq.mobile.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "me.raddatz.veyq"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
}
