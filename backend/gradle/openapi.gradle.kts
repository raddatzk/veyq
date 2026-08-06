buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.openapitools:openapi-generator:7.21.0")
    }
}

// Spec-first generation: Kotlin models + JAX-RS (Quarkus) interfaces from contracts/api/*.openapi.yaml.
// Specs $ref shared components from _common.openapi.yaml (ADR 0038); openapi-generator resolves the external
// ref in place, so no bundling or committed artifact is needed (excluded from the spec glob below).
// Slice Resources implement the generated `*Api` interfaces (one tag per API resource — ADR 0001).

val generatedApiDir = layout.buildDirectory.dir("generated/openapi-api")
val generatedModelsDir = layout.buildDirectory.dir("generated/openapi-models")

val apiPackageName = "com.veyq.app.generated.api"
val modelPackageName = "com.veyq.app.generated.model"
val invokerPackageName = "com.veyq.app.generated"

// `_common.openapi.yaml` is a shared-component $ref target (ADR 0038), not a spec — exclude it so the
// per-spec generator loop never treats it as its own API/model set.
val specFiles = fileTree("../contracts/api") {
    include("*.openapi.yaml")
    exclude("_common.openapi.yaml")
}.files.sorted()

fun runGenerator(
    generator: String,
    specPath: String,
    outDir: Provider<Directory>,
    globalProperties: Map<String, String>,
    additional: Map<String, String>,
    library: String? = null,
) {
    val cfg = org.openapitools.codegen.config.CodegenConfigurator()
        .setGeneratorName(generator)
        .setInputSpec(specPath)
        .setOutputDir(outDir.get().asFile.absolutePath)
        .setGlobalProperties(globalProperties)
        .setAdditionalProperties(additional)
        .setApiPackage(apiPackageName)
        .setModelPackage(modelPackageName)
        .setInvokerPackage(invokerPackageName)
        .setPackageName(invokerPackageName)
    library?.let(cfg::setLibrary)
    org.openapitools.codegen.DefaultGenerator().opts(cfg.toClientOptInput()).generate()
}

tasks.register("openApiGenerateSpecs") {
    group = "openapi tools"
    description = "Generate Kotlin models + JAX-RS interfaces from contracts/api specs."
    inputs.files(specFiles)
    outputs.dirs(generatedApiDir, generatedModelsDir)
    doFirst { delete(generatedApiDir, generatedModelsDir) }
    doLast {
        specFiles.forEach { spec ->
            runGenerator(
                "kotlin", spec.absolutePath, generatedModelsDir,
                mapOf("models" to "", "supportingFiles" to "false"),
                mapOf(
                    "serializationLibrary" to "jackson",
                    "enumPropertyNaming" to "original",
                    "collectionType" to "list",
                ),
            )
            runGenerator(
                "jaxrs-spec", spec.absolutePath, generatedApiDir,
                mapOf("apis" to "", "supportingFiles" to "false"),
                mapOf(
                    "interfaceOnly" to "true",
                    "useJakartaEe" to "true",
                    "useSwaggerAnnotations" to "false",
                    "useSwaggerV3Annotations" to "false",
                    "useMicroProfileOpenAPIAnnotations" to "false",
                    "openApiNullable" to "false",
                    "hideGenerationTimestamp" to "true",
                    "useTags" to "true",
                    "useBeanValidation" to "true",
                    "returnResponse" to "true",
                ),
                library = "quarkus",
            )
        }
    }
}

extensions.configure<org.gradle.api.tasks.SourceSetContainer>("sourceSets") {
    named("main") { java { srcDir(generatedApiDir.map { it.dir("src/gen/java") }) } }
}

@Suppress("UNCHECKED_CAST")
val kotlinSourceSets = extensions.getByName("kotlin").javaClass.methods
    .single { it.name == "getSourceSets" && it.parameterCount == 0 }
    .invoke(extensions.getByName("kotlin")) as org.gradle.api.NamedDomainObjectContainer<Any>

kotlinSourceSets.named("main") {
    val kotlinSourceDirectorySet = javaClass.methods
        .single { it.name == "getKotlin" && it.parameterCount == 0 }.invoke(this)
    val srcDir = kotlinSourceDirectorySet.javaClass.methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }
    srcDir.invoke(kotlinSourceDirectorySet, generatedModelsDir.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerateSpecs")) }
tasks.named("compileJava") { dependsOn(tasks.named("openApiGenerateSpecs")) }
