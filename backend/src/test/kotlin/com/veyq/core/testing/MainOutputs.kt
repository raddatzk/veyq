package com.veyq.core.testing

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The compiled main classes of every backend Gradle project on this test's classpath — what the posture sweeps walk
 * as their "everything that was compiled" angle (`RateLimitPostureSweepTest`, `AdminChannelPostureSweepTest`).
 *
 * Since the module split the code under test is no longer one directory: the project the test runs in contributes
 * its classes directories (`build/classes/{kotlin,java}/main`), every project it depends on a jar
 * (`core/build/libs/veyq-core.jar`). Walking one class's code source — the sweeps' old anchor — saw one module and
 * missed the rest. So the sweep reads **every classpath entry under the Gradle root** (`backend/`, every backend
 * test's working directory, `gradle/module.gradle.kts`), directory or jar, except the outputs of a `test` source set.
 * Library jars live in the Gradle cache, outside the root, and are never read. The sweeps' own cross-checks keep
 * this honest: the rate-limit sweep compares the walk with what the CDI container deployed, so an output this
 * misses (or a stray one it reads) fails there before any posture is judged.
 */
object MainOutputs {

    private val gradleRoot: Path = Paths.get("").toAbsolutePath().normalize()

    /** The main-output entries of this JVM's classpath: directories and jars of backend projects. */
    fun entries(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Paths.get(it).toAbsolutePath().normalize() }
            .filter { it.startsWith(gradleRoot) && !isTestOutput(gradleRoot.relativize(it)) }
            .filter { it.isDirectory() || (it.isRegularFile() && it.extension == "jar") }
            .distinct()

    /** Every class name the main outputs hold, inner classes included. */
    fun classNames(): List<String> = entries().flatMap { entry ->
        if (entry.isDirectory()) {
            Files.walk(entry).use { paths ->
                paths.filter { it.extension == "class" }
                    .map { entry.relativize(it).invariantSeparatorsPathString.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        } else {
            ZipFile(entry.toFile()).use { jar ->
                jar.entries().asSequence().map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }
    }.distinct()

    /** `build/classes/kotlin/test`, `build/resources/test`, … — a `test` source set's output, never main code. */
    private fun isTestOutput(relative: Path): Boolean {
        val parts = relative.map { it.toString() }
        val build = parts.indexOf("build")
        return build >= 0 && parts.drop(build + 1).any { it == "test" }
    }
}
