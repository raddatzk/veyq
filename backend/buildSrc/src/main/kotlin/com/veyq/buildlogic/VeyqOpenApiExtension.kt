package com.veyq.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import javax.inject.Inject

/**
 * `veyqOpenApi { owner("core") }` — names the spec set a project generates (`SpecSets`) and registers its tasks:
 * `openApiGenerate<Owner>`, the aggregate `openApiGenerateSpecs` (`./gradlew openApiGenerateSpecs` from `backend/` runs
 * every owner's) and, for core, `generateAdminChannelOperations`. A method, not a property: the task names depend on
 * the owner, so it is read once, while the project is configured — never in an `afterEvaluate`.
 */
abstract class VeyqOpenApiExtension @Inject constructor(private val project: Project) {
    private var owner: String? = null

    fun owner(name: String) {
        owner?.let { throw GradleException("${project.path}: veyqOpenApi names its owner once (it is `$it`)") }
        owner = name
        register(project, name)
    }

    private companion object {
        fun register(project: Project, ownerName: String) {
            // `backend/../contracts/api`: every module resolves the specs from the Gradle root, as the tests read them.
            val sets = SpecSets(project.rootDir.resolve("../contracts/api"))
            sets.requireNoStray()
            val set = sets.of(ownerName)
            val isCore = set.owner == SpecSets.coreOwner
            val generated = project.layout.buildDirectory.dir("generated/openapi/${set.owner}")
            val apiDir = generated.map { it.dir("api") }
            val modelsDir = generated.map { it.dir("models") }
            val channelDir = project.layout.buildDirectory.dir("generated/admin-channel")

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
                description = "Generate this project's Kotlin models + JAX-RS interfaces from contracts/api (`./gradlew openApiGenerateSpecs` from backend/ runs every owner's)."
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
            }

            project.extensions.getByType<SourceSetContainer>().named("main") {
                java.srcDir(apiDir.map { it.dir("src/gen/java") })
            }
            project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.named("main") {
                kotlin.srcDir(modelsDir.map { it.dir("src/main/kotlin") })
                if (isCore) kotlin.srcDir(channelDir.map { it.dir("src/main/kotlin") })
            }

            project.tasks.named("compileKotlin") {
                dependsOn(project.tasks.named("openApiGenerateSpecs"))
                if (isCore) dependsOn(project.tasks.named("generateAdminChannelOperations"))
            }
            project.tasks.named("compileJava") { dependsOn(project.tasks.named("openApiGenerateSpecs")) }
        }
    }
}
