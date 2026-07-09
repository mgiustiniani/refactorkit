import org.gradle.jvm.application.tasks.CreateStartScripts
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.refactorkit.cli.RefactorKitCliKt")
    applicationName = "refactorkit"
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation(project(":modules:refactorkit-java"))
    implementation(project(":modules:refactorkit-web-importer"))
    implementation(project(":modules:refactorkit-tree-sitter"))
    implementation(project(":modules:refactorkit-testkit"))
    testImplementation(kotlin("test"))
}

tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "refactorkit"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}

// ── self-contained CLI packaging ─────────────────────────────────────────────

val runtimeModules = providers.gradleProperty("refactorkit.runtime.modules")
    .orElse("java.base,java.logging,java.xml,jdk.unsupported")

val packageDir = layout.buildDirectory.dir("package/refactorkit")
val runtimeDir = layout.buildDirectory.dir("jlink/runtime")
val bundledLauncherDir = layout.buildDirectory.dir("generated/bundled-launchers")

fun javaTool(toolName: String): String {
    val javaHome = System.getProperty("java.home")
    val executable = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "$toolName.exe" else toolName
    return File(javaHome, "bin/$executable").absolutePath
}

/**
 * Build a minimal runtime image with jlink.
 *
 * Override modules if needed:
 *   ./gradlew :modules:refactorkit-cli:jlinkRuntime \
 *     -Prefactorkit.runtime.modules=java.base,java.logging,java.xml,jdk.unsupported
 */
tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "Create a minimal Java runtime image for the RefactorKit CLI using jlink."

    val output = runtimeDir.get().asFile
    outputs.dir(output)
    inputs.property("runtimeModules", runtimeModules)

    doFirst { delete(output) }
    executable = javaTool("jlink")
    args(
        "--add-modules", runtimeModules.get(),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=2",
        "--output", output.absolutePath,
    )
}

/** Write launchers that always use the bundled runtime instead of PATH java. */
tasks.register("writeBundledLaunchers") {
    group = "distribution"
    description = "Generate shell/batch launchers for the self-contained RefactorKit CLI package."

    val out = bundledLauncherDir.get().asFile
    outputs.dir(out)

    doLast {
        out.mkdirs()
        val unix = out.resolve("refactorkit")
        unix.writeText(
            """
            |#!/usr/bin/env sh
            |set -e
            |APP_HOME="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")/.." && pwd)"
            |exec "${'$'}APP_HOME/runtime/bin/java" -cp "${'$'}APP_HOME/lib/*" org.refactorkit.cli.RefactorKitCliKt "${'$'}@"
            |
            """.trimMargin(),
        )
        unix.setExecutable(true)

        out.resolve("refactorkit.bat").writeText(
            """
            |@echo off

            |set APP_HOME=%~dp0..\

            |"%APP_HOME%runtime\bin\java.exe" -cp "%APP_HOME%lib\*" org.refactorkit.cli.RefactorKitCliKt %*

            """.trimMargin(),
        )
    }
}

// Assemble build/package/refactorkit with bin/, lib/, and runtime/.
tasks.register("refactorkitRuntimeDist") {
    group = "distribution"
    description = "Assemble a self-contained RefactorKit CLI distribution with embedded Java runtime."
    dependsOn("installDist", "jlinkRuntime", "writeBundledLaunchers")

    inputs.dir(layout.buildDirectory.dir("install/refactorkit"))
    inputs.dir(runtimeDir)
    inputs.dir(bundledLauncherDir)
    outputs.dir(packageDir)

    doLast {
        val out = packageDir.get().asFile
        delete(out)
        copy {
            from(layout.buildDirectory.dir("install/refactorkit")) {
                exclude("bin/**")
            }
            into(out)
        }
        copy {
            from(runtimeDir)
            into(out.resolve("runtime"))
        }
        copy {
            from(bundledLauncherDir)
            into(out.resolve("bin"))
        }
        out.resolve("bin/refactorkit").setExecutable(true)
        println("Self-contained RefactorKit CLI package: ${out.absolutePath}")
    }
}

tasks.register("refactorkitRuntimeZip") {
    group = "distribution"
    description = "Zip the self-contained RefactorKit CLI runtime distribution."
    dependsOn("refactorkitRuntimeDist")

    val zipFile = layout.buildDirectory.file("distributions/refactorkit-runtime.zip")
    inputs.dir(packageDir)
    outputs.file(zipFile)

    doLast {
        val source = packageDir.get().asFile
        val target = zipFile.get().asFile
        target.parentFile.mkdirs()
        if (target.exists()) target.delete()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = source.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                val entry = ZipEntry("refactorkit/$relative")
                entry.time = file.lastModified()
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("Self-contained RefactorKit CLI zip: ${target.absolutePath}")
    }
}
