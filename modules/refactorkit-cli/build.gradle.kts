import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.application.tasks.CreateStartScripts
import java.io.File

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
    implementation(project(":modules:refactorkit-daemon"))
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
    .orElse("java.base,java.compiler,java.logging,java.xml,jdk.unsupported")

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
 *     -Prefactorkit.runtime.modules=java.base,java.compiler,java.logging,java.xml,jdk.unsupported
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
        val daemonUnix = out.resolve("refactorkit-daemon")
        daemonUnix.writeText(
            """
            |#!/usr/bin/env sh
            |set -e
            |APP_HOME="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")/.." && pwd)"
            |exec "${'$'}APP_HOME/runtime/bin/java" -cp "${'$'}APP_HOME/lib/*" org.refactorkit.daemon.RefactorKitDaemonKt "${'$'}@"
            |
            """.trimMargin(),
        )
        daemonUnix.setExecutable(true)

        out.resolve("refactorkit.bat").writeText(
            """
            |@echo off
            |setlocal
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" -cp "%APP_HOME%\lib\*" org.refactorkit.cli.RefactorKitCliKt %*
            |exit /b %ERRORLEVEL%
            |
            """.trimMargin(),
        )
        out.resolve("refactorkit-daemon.bat").writeText(
            """
            |@echo off
            |setlocal
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" -cp "%APP_HOME%\lib\*" org.refactorkit.daemon.RefactorKitDaemonKt %*
            |exit /b %ERRORLEVEL%
            |
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
        out.resolve("bin/refactorkit-daemon").setExecutable(true)
        println("Self-contained RefactorKit CLI package: ${out.absolutePath}")
    }
}

tasks.register<Exec>("smokePackagedCli") {
    group = "verification"
    description = "Smoke-test signed JDT lookups using only the packaged jlink runtime."
    dependsOn("refactorkitRuntimeDist")

    workingDir = rootProject.projectDir
    environment.remove("JAVA_HOME")
    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        commandLine(
            "pwsh",
            "-NoProfile",
            "-File",
            rootProject.file("scripts/smoke-packaged-cli.ps1").absolutePath,
            packageDir.get().asFile.absolutePath,
        )
    } else {
        commandLine(
            "bash",
            rootProject.file("scripts/smoke-packaged-cli.sh").absolutePath,
            packageDir.get().asFile.absolutePath,
        )
    }
}

tasks.register<Zip>("refactorkitRuntimeZip") {
    group = "distribution"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    description = "Zip the self-contained RefactorKit CLI runtime distribution."
    dependsOn("refactorkitRuntimeDist")

    archiveFileName.set("refactorkit-runtime.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(packageDir) {
        into("refactorkit")
    }

    dirPermissions {
        unix("rwxr-xr-x")
    }
    filePermissions {
        unix("rw-r--r--")
    }
    eachFile {
        if (path == "refactorkit/bin/refactorkit" ||
            path == "refactorkit/bin/refactorkit-daemon" ||
            path.startsWith("refactorkit/runtime/bin/") ||
            path == "refactorkit/runtime/lib/jexec" ||
            path == "refactorkit/runtime/lib/jspawnhelper"
        ) {
            permissions {
                unix("rwxr-xr-x")
            }
        }
    }

    doLast {
        println("Self-contained RefactorKit CLI zip: ${archiveFile.get().asFile.absolutePath}")
    }
}
