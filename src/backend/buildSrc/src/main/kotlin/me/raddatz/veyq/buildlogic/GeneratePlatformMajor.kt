package me.raddatz.veyq.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The platform's one major as code (ADR 0085 decision 1): `PlatformMajor.MAJOR`, read from the repository's `VERSION`,
 * which holds the release major alone (ADR 0057 §1). `GET /api/setup` advertises it as `version.major`, the one number a
 * client gates on before login, so it is derived here rather than set by hand or by configuration: a config key is
 * settable per instance (`VEYQ_VERSION_MAJOR`), and an instance must not advertise a major its code does not speak. The
 * jar carries it, so neither runtime target (compose, Helm) needs a value, and every build has it — unlike the release
 * stamp `VEYQ_VERSION`, which only a publish sets. The web client reads the same file (`src/web/vite.config.ts`).
 *
 * The build refuses a `VERSION` that is not a bare non-negative integer, as `container.yml` and
 * `scripts/validate_release_identity.py` do, rather than compile a major nobody wrote.
 */
@CacheableTask
abstract class GeneratePlatformMajor : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = versionFile.get().asFile.readText().trim()
        if (!MAJOR.matches(text)) throw GradleException("VERSION must hold the major alone (ADR 0057 §1), got: $text")
        val dir = outputDir.get().asFile.resolve("src/main/kotlin/${PACKAGE.replace('.', '/')}")
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("PlatformMajor.kt").writeText(
            """
            |package $PACKAGE
            |
            |/** Generated from the repository's VERSION (buildSrc's GeneratePlatformMajor) — never edited. */
            |object PlatformMajor {
            |    /**
            |     * The platform's one major (ADR 0085 decision 1): the release major and the protocol major a client gates on
            |     * (`GET /api/setup` `version.major`, ADR 0032). `0` is pre-release.
            |     */
            |    const val MAJOR: Int = ${text.toInt()}
            |}
            |""".trimMargin(),
        )
    }

    private companion object {
        const val PACKAGE = "me.raddatz.veyq.core.platform.capabilities"
        val MAJOR = Regex("[0-9]+")
    }
}
