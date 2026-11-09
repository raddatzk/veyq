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

// Every word the app shows (ADR 0127 decision 3), generated from src/mobile/words/words.json: the resources under their
// keys, and `Context.words(Msg, …)` for what the core raises. The app writes no word of its own.
abstract class GenerateWords : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val catalog: RegularFileProperty
    @get:OutputDirectory abstract val resDir: DirectoryProperty
    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty
    @get:javax.inject.Inject abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        exec.exec {
            commandLine(
                "python3", script.get().asFile.absolutePath,
                "--android-res", resDir.get().asFile.absolutePath, "--android-kotlin", kotlinDir.get().asFile.absolutePath,
            )
        }
    }
}

val generateWords = tasks.register<GenerateWords>("generateWords") {
    group = "veyq"
    description = "Generates the app's words from the catalog."
    script.set(rootDir.resolve("words/generate_words.py"))
    catalog.set(rootDir.resolve("words/words.json"))
    resDir.set(layout.buildDirectory.dir("generated/words/res"))
    kotlinDir.set(layout.buildDirectory.dir("generated/words/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateThemeRes, GenerateThemeRes::outDir)
        variant.sources.res?.addGeneratedSourceDirectory(generateWords, GenerateWords::resDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateWords, GenerateWords::kotlinDir)
    }
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
