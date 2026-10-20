package me.raddatz.veyq.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import javax.inject.Inject

/**
 * `veyqOpenApi { generateOwnSpecs() }` — generates the spec set the project owns by its path (`SpecSets.ownerOf`:
 * `:core` → the specs at the top level of `contracts/api/`, `:apps:<app>` → those in `contracts/api/<app>/`) and
 * registers its tasks:
 * `openApiGenerate<Owner>`, the aggregate `openApiGenerateSpecs` (`./gradlew openApiGenerateSpecs` from `src/backend/` runs
 * every owner's) and, for core, `generateAdminChannelOperations`, `generateCapabilityRegister` (the capability ids and
 * their texts, from `contracts/platform/capabilities.json`) and `generatePlatformMajor` (the major `/api/setup` advertises,
 * from the repository's `VERSION`). A method, not a property: the task names depend on
 * the owner, so it is read once, while the project is configured — never in an `afterEvaluate`.
 */
abstract class VeyqOpenApiExtension @Inject constructor(private val project: Project) {
    /** The owner this project generates, once `generateOwnSpecs()` ran — read by core's check that every application directory is generated. */
    internal var owner: String? = null
        private set

    fun generateOwnSpecs() {
        owner?.let { throw GradleException("${project.path}: veyqOpenApi generates its specs once (they are `$it`'s)") }
        val name = SpecSets.ownerOf(project.path)
        owner = name
        register(project, name)
    }

    private companion object {
        fun register(project: Project, ownerName: String) {
            // `src/backend/../contracts/api`: every module resolves the specs from the Gradle root, as the tests read them.
            val sets = SpecSets(
                project.rootDir.resolve("../../contracts/api"),
                SpecSets.applicationModules(project.rootProject.allprojects.map { it.path }),
            )
            sets.requireNoStray()
            val set = sets.of(ownerName)
            val isCore = set.owner == SpecSets.coreOwner
            val generated = project.layout.buildDirectory.dir("generated/openapi/${set.owner}")
            val apiDir = generated.map { it.dir("api") }
            val modelsDir = generated.map { it.dir("models") }
            val channelDir = project.layout.buildDirectory.dir("generated/admin-channel")
            val capabilitiesDir = project.layout.buildDirectory.dir("generated/capabilities")
            val platformMajorDir = project.layout.buildDirectory.dir("generated/platform-major")

            if (isCore) {
                // A module the settings include whose build never calls `generateOwnSpecs()` would leave its directory's
                // specs generating nothing: the stray check above cannot see that, since the module exists.
                project.gradle.projectsEvaluated {
                    val ungenerated = sets.applications.filter { app ->
                        project.rootProject.findProject(":apps:$app")?.extensions?.findByType<VeyqOpenApiExtension>()?.owner != app
                    }
                    if (ungenerated.isNotEmpty()) {
                        throw GradleException(
                            ungenerated.joinToString { "contracts/api/$it/" } + ": specs no module generates — apply " +
                                "`veyq.openapi` and call `veyqOpenApi { generateOwnSpecs() }` in `:apps:<app>`",
                        )
                    }
                }
            }

            val generate = project.tasks.register<OpenApiGenerate>("openApiGenerate${set.owner.replaceFirstChar(Char::uppercase)}") {
                group = "openapi tools"
                description = "Generate Kotlin models + JAX-RS interfaces from the ${set.owner} specs."
                specs.from(set.specs)
                commonSpec.set(sets.commonSpec)
                owner.set(set.owner)
                packageRoot.set(set.packageRoot)
                mapsCommon.set(set.mapsCommon)
                contractsDir.set(sets.contractsDir)
                this.apiDir.set(apiDir)
                this.modelsDir.set(modelsDir)
                if (!isCore) {
                    // Core's names are read from `:core`'s own generated output, so core generates first.
                    val core = sets.of(SpecSets.coreOwner)
                    dependsOn(":core:openApiGenerateCore")
                    coreModels.from(
                        project.rootProject.project(":core").layout.buildDirectory
                            .dir("generated/openapi/${core.owner}/models/src/main/kotlin/${core.modelPackage.replace('.', '/')}"),
                    )
                }
            }

            project.tasks.register("openApiGenerateSpecs") {
                group = "openapi tools"
                description = "Generate this project's Kotlin models + JAX-RS interfaces from contracts/api (`./gradlew openApiGenerateSpecs` from src/backend/ runs every owner's)."
                dependsOn(generate)
            }

            if (isCore) {
                project.tasks.register<GenerateAdminChannelOperations>("generateAdminChannelOperations") {
                    group = "openapi tools"
                    description = "Derive the admin-channel operationIds from the x-veyq-channel markers in contracts/api."
                    specs.from(set.specs)
                    contractsDir.set(sets.contractsDir)
                    outputDir.set(channelDir)
                }
                project.tasks.register<GenerateCapabilityRegister>("generateCapabilityRegister") {
                    group = "openapi tools"
                    description = "Generate the capability ids and their EN/DE texts from contracts/platform/capabilities.json."
                    // Beside the specs, read from the Gradle root as they are; absent during the Dockerfile's warm-up,
                    // which only configures.
                    catalog.set(project.rootDir.resolve("../../contracts/platform/capabilities.json"))
                    outputDir.set(capabilitiesDir)
                }
                project.tasks.register<GeneratePlatformMajor>("generatePlatformMajor") {
                    group = "openapi tools"
                    description = "Generate the platform's one major from the repository's VERSION (ADR 0085 decision 1)."
                    // From the Gradle root like the catalog; absent during the Dockerfile's warm-up, which only configures.
                    versionFile.set(project.rootDir.resolve("../../VERSION"))
                    outputDir.set(platformMajorDir)
                }
            }

            project.extensions.getByType<SourceSetContainer>().named("main") {
                java.srcDir(apiDir.map { it.dir("src/gen/java") })
            }
            project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.named("main") {
                kotlin.srcDir(modelsDir.map { it.dir("src/main/kotlin") })
                if (isCore) {
                    kotlin.srcDir(channelDir.map { it.dir("src/main/kotlin") })
                    kotlin.srcDir(capabilitiesDir.map { it.dir("src/main/kotlin") })
                    kotlin.srcDir(platformMajorDir.map { it.dir("src/main/kotlin") })
                }
            }

            project.tasks.named("compileKotlin") {
                dependsOn(project.tasks.named("openApiGenerateSpecs"))
                if (isCore) {
                    dependsOn(project.tasks.named("generateAdminChannelOperations"))
                    dependsOn(project.tasks.named("generateCapabilityRegister"))
                    dependsOn(project.tasks.named("generatePlatformMajor"))
                }
            }
            project.tasks.named("compileJava") { dependsOn(project.tasks.named("openApiGenerateSpecs")) }
        }
    }
}
