import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.application.tasks.CreateStartScripts
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

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
    implementation(project(":modules:refactorkit-jvm"))
    implementation(project(":modules:refactorkit-tree-sitter"))
    implementation(project(":modules:refactorkit-typescript"))
    implementation(project(":modules:refactorkit-kotlin"))
    implementation(project(":modules:refactorkit-testkit"))
    runtimeOnly(project(":modules:refactorkit-mcp"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(project(":modules:refactorkit-lsp"))
    testImplementation(project(":modules:refactorkit-mcp"))
    testImplementation(kotlin("test"))
    testImplementation("io.cucumber:cucumber-java:7.20.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.20.1")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.2")
}

sourceSets.test {
    resources.srcDir(rootProject.file("features"))
}

val packagedMavenMoveClassAuthorityTestSourceSet = sourceSets.create("packagedMavenMoveClassAuthorityTest") {
    kotlin.srcDir("src/packagedMavenMoveClassAuthorityTest/kotlin")
    resources.srcDir(rootProject.file("features"))
}

configurations.named(packagedMavenMoveClassAuthorityTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(packagedMavenMoveClassAuthorityTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val packagedMavenModuleRenameQualificationTestSourceSet = sourceSets.create(
    "packagedMavenModuleRenameQualificationTest",
) {
    kotlin.srcDir("src/packagedMavenModuleRenameQualificationTest/kotlin")
    resources.srcDir(rootProject.file("features"))
}

configurations.named(packagedMavenModuleRenameQualificationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(packagedMavenModuleRenameQualificationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
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
    .orElse("java.se,jdk.httpserver,jdk.unsupported,jdk.zipfs")

val packageDir = layout.buildDirectory.dir("package/refactorkit")
val runtimeDir = layout.buildDirectory.dir("jlink/runtime")
val bundledLauncherDir = layout.buildDirectory.dir("generated/bundled-launchers")
val bundledJavaPlatformDir = layout.buildDirectory.dir("generated/java-platform/jdk")

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
 *     -Prefactorkit.runtime.modules=java.base,java.compiler,java.desktop,java.logging,java.xml,jdk.unsupported,jdk.zipfs
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

/** Stage immutable release signatures separately from the reduced runtime image. */
tasks.register("stageJavaPlatformEvidence") {
    group = "distribution"
    description = "Stage the build JDK release metadata and ct.sym for release-aware Java diagnostics."
    val javaHome = File(System.getProperty("java.home"))
    val releaseMetadata = javaHome.resolve("release")
    val signatures = javaHome.resolve("lib/ct.sym")
    inputs.files(releaseMetadata, signatures)
    outputs.dir(bundledJavaPlatformDir)
    doLast {
        require(releaseMetadata.isFile && signatures.isFile) {
            "The packaging JDK must provide release metadata and lib/ct.sym"
        }
        val output = bundledJavaPlatformDir.get().asFile
        delete(output)
        output.resolve("lib").mkdirs()
        releaseMetadata.copyTo(output.resolve("release"), overwrite = true)
        signatures.copyTo(output.resolve("lib/ct.sym"), overwrite = true)
    }
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
            |exec "${'$'}APP_HOME/runtime/bin/java" -Drefactorkit.java.platform.home="${'$'}APP_HOME/runtime" -cp "${'$'}APP_HOME/lib/*" org.refactorkit.cli.RefactorKitCliKt "${'$'}@"
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
            |exec "${'$'}APP_HOME/runtime/bin/java" -Drefactorkit.java.platform.home="${'$'}APP_HOME/runtime" -cp "${'$'}APP_HOME/lib/*" org.refactorkit.daemon.RefactorKitDaemonKt "${'$'}@"
            |
            """.trimMargin(),
        )
        daemonUnix.setExecutable(true)
        val mcpUnix = out.resolve("refactorkit-mcp")
        mcpUnix.writeText(
            """
            |#!/usr/bin/env sh
            |set -e
            |APP_HOME="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")/.." && pwd)"
            |exec "${'$'}APP_HOME/runtime/bin/java" -Drefactorkit.java.platform.home="${'$'}APP_HOME/runtime" -cp "${'$'}APP_HOME/lib/*" org.refactorkit.mcp.RefactorKitMcpKt "${'$'}@"
            |
            """.trimMargin(),
        )
        mcpUnix.setExecutable(true)

        out.resolve("refactorkit.bat").writeText(
            """
            |@echo off
            |setlocal
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" -Drefactorkit.java.platform.home="%APP_HOME%\runtime" -cp "%APP_HOME%\lib\*" org.refactorkit.cli.RefactorKitCliKt %*
            |exit /b %ERRORLEVEL%
            |
            """.trimMargin(),
        )
        out.resolve("refactorkit-daemon.bat").writeText(
            """
            |@echo off
            |setlocal
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" -Drefactorkit.java.platform.home="%APP_HOME%\runtime" -cp "%APP_HOME%\lib\*" org.refactorkit.daemon.RefactorKitDaemonKt %*
            |exit /b %ERRORLEVEL%
            |
            """.trimMargin(),
        )
        out.resolve("refactorkit-mcp.bat").writeText(
            """
            |@echo off
            |setlocal
            |set "APP_HOME=%~dp0.."
            |"%APP_HOME%\runtime\bin\java.exe" -Drefactorkit.java.platform.home="%APP_HOME%\runtime" -cp "%APP_HOME%\lib\*" org.refactorkit.mcp.RefactorKitMcpKt %*
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
    dependsOn("installDist", "jlinkRuntime", "stageJavaPlatformEvidence", "writeBundledLaunchers")

    inputs.dir(layout.buildDirectory.dir("install/refactorkit"))
    inputs.dir(runtimeDir)
    inputs.dir(bundledLauncherDir)
    inputs.dir(bundledJavaPlatformDir)
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
        copy {
            from(bundledJavaPlatformDir.map { it.file("lib/ct.sym") })
            into(out.resolve("runtime/lib"))
        }
        out.resolve("bin/refactorkit").setExecutable(true)
        out.resolve("bin/refactorkit-daemon").setExecutable(true)
        out.resolve("bin/refactorkit-mcp").setExecutable(true)
        println("Self-contained RefactorKit CLI package: ${out.absolutePath}")
    }
}

val packagedMavenMoveClassAuthorityPackageRoot = packageDir.map { it.asFile.absolutePath }
val packagedMavenMoveClassAuthorityRepositoryRoot = providers.provider {
    rootProject.layout.projectDirectory.asFile.absolutePath
}
val packagedMavenMoveClassAuthorityFixture = rootProject.layout.projectDirectory.dir(
    "testdata/acceptance/java-maven-move-class-authority-20-modules",
)
val packagedMavenMoveClassAuthorityCucumberJson = layout.buildDirectory.file(
    "reports/cucumber/packaged-maven-move-class-authority.json",
)

tasks.register<Test>("packagedMavenMoveClassAuthorityTest") {
    group = "verification"
    description = "Run packaged-process Cucumber validation for Maven move-class authority."
    dependsOn("refactorkitRuntimeDist")
    inputs.dir(packageDir)
        .withPropertyName("packagedRuntime")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(packagedMavenMoveClassAuthorityFixture)
        .withPropertyName("mavenMoveClassAuthorityFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(packagedMavenMoveClassAuthorityCucumberJson)
        .withPropertyName("cucumberJsonReport")

    testClassesDirs = packagedMavenMoveClassAuthorityTestSourceSet.output.classesDirs
    classpath = packagedMavenMoveClassAuthorityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    forkEvery = 0

    doFirst {
        systemProperty("refactorkit.packaged.root", packagedMavenMoveClassAuthorityPackageRoot.get())
        systemProperty("refactorkit.repository.root", packagedMavenMoveClassAuthorityRepositoryRoot.get())
    }

    reports {
        junitXml.required.set(true)
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/packagedMavenMoveClassAuthorityTest"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/tests/packagedMavenMoveClassAuthorityTest"))
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

tasks.register<Exec>("smokePackagedRecipe") {
    group = "verification"
    description = "Load and preview a YAML recipe using only the packaged jlink runtime."
    dependsOn("refactorkitRuntimeDist")
    workingDir = rootProject.projectDir
    environment.remove("JAVA_HOME")
    commandLine(
        "bash",
        rootProject.file("scripts/smoke-packaged-recipe.sh").absolutePath,
        packageDir.get().asFile.absolutePath,
    )
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
            path == "refactorkit/bin/refactorkit-mcp" ||
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

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun validatePackagedMavenModuleRenameCandidateArchive(archive: File) {
    require(archive.isFile) { "Packaged Maven module-rename candidate archive is missing: $archive" }
    val exactPaths = mutableSetOf<String>()
    val caseFoldedPaths = mutableSetOf<String>()

    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        var entryCount = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount += 1
            val rawName = entry.name
            require(rawName.isNotEmpty() && !rawName.contains('\\') && !rawName.startsWith('/')) {
                "Unsafe ZIP entry path: $rawName"
            }
            val normalizedName = rawName.removeSuffix("/")
            val segments = normalizedName.split('/')
            require(
                normalizedName.isNotEmpty() &&
                    segments.first() == "refactorkit" &&
                    segments.none { segment -> segment.isEmpty() || segment == "." || segment == ".." },
            ) {
                "ZIP entry escapes the single refactorkit candidate root: $rawName"
            }
            require(exactPaths.add(normalizedName)) { "Duplicate ZIP entry path: $rawName" }
            require(caseFoldedPaths.add(normalizedName.lowercase(Locale.ROOT))) {
                "Case-folded ZIP entry collision: $rawName"
            }

            if (!entry.isDirectory) {
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (input.read(buffer) >= 0) {
                        // Drain every entry before extraction so malformed or CRC-invalid content fails closed.
                    }
                }
            }
        }
        require(entryCount > 0 && exactPaths.contains("refactorkit")) {
            "Candidate archive must contain one non-empty refactorkit root"
        }
    }
}

val packagedMavenModuleRenameQualificationCandidateArchive =
    tasks.named<Zip>("refactorkitRuntimeZip").flatMap { task -> task.archiveFile }
val packagedMavenModuleRenameQualificationEvidenceRoot = layout.buildDirectory.dir(
    "qualification/packaged-maven-module-rename",
)
val packagedMavenModuleRenameQualificationCandidateChecksum =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root ->
        root.file("candidate/refactorkit-runtime.zip.sha256")
    }
val packagedMavenModuleRenameQualificationExtractedRoot =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root -> root.dir("candidate/extracted") }
val packagedMavenModuleRenameQualificationCandidateRoot =
    packagedMavenModuleRenameQualificationExtractedRoot.map { root -> root.dir("refactorkit") }
val packagedMavenModuleRenameQualificationLogs =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root -> root.dir("logs") }
val packagedMavenModuleRenameQualificationManifests =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root -> root.dir("manifests") }
val packagedMavenModuleRenameQualificationFailureDiagnostics =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root -> root.dir("failure-diagnostics") }
val packagedMavenModuleRenameQualificationVerifierRoot =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root -> root.dir("verifier") }
val packagedMavenModuleRenameQualificationVerifierLog =
    packagedMavenModuleRenameQualificationVerifierRoot.map { root ->
        root.file("runtime-archive-verifier-complete.log")
    }
val packagedMavenModuleRenameQualificationVerifierStatus =
    packagedMavenModuleRenameQualificationVerifierRoot.map { root ->
        root.file("runtime-archive-verifier-status.properties")
    }
val packagedMavenModuleRenameQualificationTestOutcome =
    packagedMavenModuleRenameQualificationEvidenceRoot.map { root ->
        root.file("run/test-task-outcome.properties")
    }
val packagedMavenModuleRenameQualificationHostManifest =
    packagedMavenModuleRenameQualificationManifests.map { root ->
        root.file("packaged-maven-module-rename-host-evidence-manifest.json")
    }
val packagedMavenModuleRenameQualificationJunitAggregate =
    packagedMavenModuleRenameQualificationManifests.map { root ->
        root.file("packaged-maven-module-rename-junit-xml-aggregate.json")
    }
val packagedMavenModuleRenameQualificationFailureAggregate =
    packagedMavenModuleRenameQualificationFailureDiagnostics.map { root ->
        root.file("aggregate-failure-diagnostics.json")
    }
val packagedMavenModuleRenameQualificationFocusedLog =
    packagedMavenModuleRenameQualificationLogs.map { root ->
        root.file("packaged-maven-module-rename-qualification-focused.log")
    }
val packagedMavenModuleRenameQualificationCucumberJson = layout.buildDirectory.file(
    "reports/cucumber/packaged-maven-module-rename-qualification.json",
)
val packagedMavenModuleRenameQualificationFixture = rootProject.layout.projectDirectory.dir(
    "testdata/acceptance/java-maven-move-class-authority-20-modules",
)
val packagedMavenModuleRenameQualificationFeature = rootProject.layout.projectDirectory.file(
    "features/java-maven-module-rename-packaged.feature",
)
val packagedMavenModuleRenameQualificationBaseline = rootProject.layout.projectDirectory.file(
    "docs/requirements/req-java-maven-module-rename-packaged-001-baseline.md",
)
val packagedMavenModuleRenameQualificationApprovedChange = rootProject.layout.projectDirectory.file(
    "docs/requirements/req-java-maven-module-rename-packaged-001-approved-change-001.md",
)

val checksumPackagedMavenModuleRenameQualificationCandidate = tasks.register(
    "checksumPackagedMavenModuleRenameQualificationCandidate",
) {
    group = "verification"
    description = "Create the SHA-256 sidecar for the packaged Maven module-rename candidate archive."
    dependsOn("refactorkitRuntimeZip")
    inputs.file(packagedMavenModuleRenameQualificationCandidateArchive)
        .withPropertyName("candidateArchive")
        .withPathSensitivity(PathSensitivity.NONE)
    outputs.file(packagedMavenModuleRenameQualificationCandidateChecksum)
        .withPropertyName("candidateArchiveChecksum")

    doLast {
        val archive = packagedMavenModuleRenameQualificationCandidateArchive.get().asFile
        val checksum = packagedMavenModuleRenameQualificationCandidateChecksum.get().asFile
        checksum.parentFile.mkdirs()
        checksum.writeText("${fileSha256(archive)}  ${archive.name}\n", Charsets.UTF_8)
    }
}

fun resetPackagedMavenModuleRenameQualificationExtraction(root: File) {
    val rootPath = root.toPath()
    if (Files.notExists(rootPath, LinkOption.NOFOLLOW_LINKS)) return
    require(!Files.isSymbolicLink(rootPath)) { "Qualification extraction root must not be a symbolic link" }
    val paths = Files.walk(rootPath).use { stream ->
        stream.sorted(Comparator.comparingInt<Path> { path -> path.nameCount }).toList()
    }
    val currentUser = runCatching {
        rootPath.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }.getOrNull()
    paths.forEach { path ->
        require(!Files.isSymbolicLink(path)) { "Qualification extraction contains a symbolic link: $path" }
        Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.let { view -> if (view.readAttributes().isReadOnly) view.setReadOnly(false) }
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.takeIf { currentUser != null }
            ?.let { view ->
                val retained = view.acl.filterNot { entry ->
                    entry.type() == AclEntryType.DENY && entry.principal() == currentUser
                }
                if (retained.size != view.acl.size) view.acl = retained
            }
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.let { view ->
                val permissions = view.readAttributes().permissions().toMutableSet()
                permissions += PosixFilePermission.OWNER_WRITE
                view.setPermissions(permissions)
            }
    }
    root.deleteRecursively()
    require(Files.notExists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        "Previous read-only qualification extraction could not be reset"
    }
}

val preparePackagedMavenModuleRenameQualificationCandidate = tasks.register<Sync>(
    "preparePackagedMavenModuleRenameQualificationCandidate",
) {
    group = "verification"
    description = "Verify and safely extract the packaged Maven module-rename candidate archive."
    dependsOn(checksumPackagedMavenModuleRenameQualificationCandidate)
    inputs.file(packagedMavenModuleRenameQualificationCandidateChecksum)
        .withPropertyName("candidateArchiveChecksum")
        .withPathSensitivity(PathSensitivity.NONE)
    from(packagedMavenModuleRenameQualificationCandidateArchive.map { archive -> zipTree(archive.asFile) })
    into(packagedMavenModuleRenameQualificationExtractedRoot)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    includeEmptyDirs = true

    doFirst {
        resetPackagedMavenModuleRenameQualificationExtraction(
            packagedMavenModuleRenameQualificationExtractedRoot.get().asFile,
        )
        val archive = packagedMavenModuleRenameQualificationCandidateArchive.get().asFile
        val checksum = packagedMavenModuleRenameQualificationCandidateChecksum.get().asFile
        val checksumLine = checksum.readText(Charsets.UTF_8).trimEnd('\r', '\n')
        val fields = checksumLine.split("  ", limit = 2)
        require(fields.size == 2 && fields[0].matches(Regex("[0-9a-f]{64}")) && fields[1] == archive.name) {
            "Invalid candidate archive checksum sidecar: $checksum"
        }
        require(fileSha256(archive) == fields[0]) {
            "Packaged Maven module-rename candidate archive checksum mismatch: $archive"
        }
        validatePackagedMavenModuleRenameCandidateArchive(archive)
    }

    doLast {
        require(packagedMavenModuleRenameQualificationCandidateRoot.get().asFile.isDirectory) {
            "Safely extracted candidate root is missing"
        }
    }
}

fun packagedMavenModuleRenameQualificationPlatform(): String {
    val operatingSystem = org.gradle.internal.os.OperatingSystem.current()
    val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val x8664 = architecture in setOf("amd64", "x86_64", "x64")
    val arm64 = architecture in setOf("aarch64", "arm64")
    return when {
        operatingSystem.isLinux && x8664 -> "linux-x86_64"
        operatingSystem.isWindows && x8664 -> "windows-x86_64"
        operatingSystem.isMacOsX && x8664 -> "macos-x86_64"
        operatingSystem.isMacOsX && arm64 -> "macos-aarch64"
        else -> error(
            "Unsupported packaged Maven module-rename qualification host: ${operatingSystem.name}/$architecture",
        )
    }
}

fun packagedMavenModuleRenameNativeQualificationRequested(): Boolean =
    System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true) ||
        providers.systemProperty("refactorkit.packaged.module.rename.nativeQualification")
            .orNull.equals("true", ignoreCase = true)

val capturePackagedMavenModuleRenameQualificationVerifierEvidence = tasks.register<Exec>(
    "capturePackagedMavenModuleRenameQualificationVerifierEvidence",
) {
    group = "verification"
    description = "Run the fixed runtime verifier and retain its complete qualification evidence."
    dependsOn(preparePackagedMavenModuleRenameQualificationCandidate)
    inputs.file(packagedMavenModuleRenameQualificationCandidateArchive)
        .withPropertyName("candidateArchive")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(packagedMavenModuleRenameQualificationCandidateChecksum)
        .withPropertyName("candidateArchiveChecksum")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.layout.projectDirectory.file("scripts/verify-runtime-archive.py"))
        .withPropertyName("runtimeArchiveVerifier")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("qualificationPlatform", providers.provider(::packagedMavenModuleRenameQualificationPlatform))
    outputs.files(
        packagedMavenModuleRenameQualificationVerifierLog,
        packagedMavenModuleRenameQualificationVerifierStatus,
    ).withPropertyName("runtimeArchiveVerifierEvidence")
    outputs.upToDateWhen { false }

    val capturedStdout = ByteArrayOutputStream()
    val capturedStderr = ByteArrayOutputStream()
    isIgnoreExitValue = true
    workingDir = rootProject.projectDir

    doFirst {
        delete(packagedMavenModuleRenameQualificationVerifierRoot)
        packagedMavenModuleRenameQualificationVerifierRoot.get().asFile.mkdirs()
        capturedStdout.reset()
        capturedStderr.reset()
        standardOutput = capturedStdout
        errorOutput = capturedStderr
        commandLine(
            System.getenv("PYTHON")?.takeIf(String::isNotBlank) ?: "python",
            rootProject.file("scripts/verify-runtime-archive.py").absolutePath,
            packagedMavenModuleRenameQualificationCandidateArchive.get().asFile.absolutePath,
            packagedMavenModuleRenameQualificationCandidateChecksum.get().asFile.absolutePath,
            "--platform",
            packagedMavenModuleRenameQualificationPlatform(),
        )
    }

    doLast {
        val archive = packagedMavenModuleRenameQualificationCandidateArchive.get().asFile
        val checksum = packagedMavenModuleRenameQualificationCandidateChecksum.get().asFile
        val platform = packagedMavenModuleRenameQualificationPlatform()
        val exitCode = executionResult.get().exitValue
        fun normalizedCapture(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val stdout = normalizedCapture(capturedStdout.toByteArray())
        val stderr = normalizedCapture(capturedStderr.toByteArray())
        packagedMavenModuleRenameQualificationVerifierLog.get().asFile.writeText(
            buildString {
                appendLine("schemaVersion=1")
                appendLine("platform=$platform")
                appendLine("archive=modules/refactorkit-cli/build/distributions/${archive.name}")
                appendLine("archiveSha256=${fileSha256(archive)}")
                appendLine("checksum=modules/refactorkit-cli/build/qualification/packaged-maven-module-rename/candidate/${checksum.name}")
                appendLine("checksumSha256=${fileSha256(checksum)}")
                appendLine("exitCode=$exitCode")
                appendLine("===== stdout =====")
                append(stdout)
                if (stdout.isNotEmpty() && !stdout.endsWith('\n')) appendLine()
                appendLine("===== stderr =====")
                append(stderr)
                if (stderr.isNotEmpty() && !stderr.endsWith('\n')) appendLine()
            },
            Charsets.UTF_8,
        )
        packagedMavenModuleRenameQualificationVerifierStatus.get().asFile.writeText(
            buildString {
                appendLine("schemaVersion=1")
                appendLine("platform=$platform")
                appendLine("exitCode=$exitCode")
                appendLine("archiveSha256=${fileSha256(archive)}")
                appendLine("checksumSha256=${fileSha256(checksum)}")
            },
            Charsets.UTF_8,
        )
    }
}

val packagedMavenModuleRenameQualificationTest = tasks.register<Test>(
    "packagedMavenModuleRenameQualificationTest",
) {
    group = "verification"
    description = "Run packaged native Cucumber qualification for the bounded Maven module rename."
    dependsOn(capturePackagedMavenModuleRenameQualificationVerifierEvidence)
    inputs.file(packagedMavenModuleRenameQualificationCandidateArchive)
        .withPropertyName("candidateArchive")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(packagedMavenModuleRenameQualificationCandidateChecksum)
        .withPropertyName("candidateArchiveChecksum")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(packagedMavenModuleRenameQualificationCandidateRoot)
        .withPropertyName("extractedCandidate")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(packagedMavenModuleRenameQualificationFixture)
        .withPropertyName("mavenModuleRenameFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(packagedMavenModuleRenameQualificationFeature)
        .withPropertyName("qualificationFeature")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(packagedMavenModuleRenameQualificationBaseline)
        .withPropertyName("requirementsBaseline")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(packagedMavenModuleRenameQualificationApprovedChange)
        .withPropertyName("approvedRequirementsChange")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(packagedMavenModuleRenameQualificationCucumberJson)
        .withPropertyName("cucumberJsonReport")
    outputs.dirs(
        packagedMavenModuleRenameQualificationLogs,
        packagedMavenModuleRenameQualificationManifests,
        packagedMavenModuleRenameQualificationFailureDiagnostics,
    ).withPropertyName("qualificationEvidence")
    outputs.file(packagedMavenModuleRenameQualificationTestOutcome)
        .withPropertyName("qualificationTestOutcome")
    outputs.upToDateWhen { false }

    testClassesDirs = packagedMavenModuleRenameQualificationTestSourceSet.output.classesDirs
    classpath = packagedMavenModuleRenameQualificationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    forkEvery = 0

    doFirst {
        delete(
            packagedMavenModuleRenameQualificationCucumberJson,
            packagedMavenModuleRenameQualificationLogs,
            packagedMavenModuleRenameQualificationManifests,
            packagedMavenModuleRenameQualificationFailureDiagnostics,
            packagedMavenModuleRenameQualificationTestOutcome,
        )
        listOf(
            packagedMavenModuleRenameQualificationLogs,
            packagedMavenModuleRenameQualificationManifests,
            packagedMavenModuleRenameQualificationFailureDiagnostics,
        ).forEach { directory -> directory.get().asFile.mkdirs() }
        val testOutcome = packagedMavenModuleRenameQualificationTestOutcome.get().asFile
        testOutcome.parentFile.mkdirs()
        testOutcome.writeText(
            "schemaVersion=1\nstate=STARTED\ntaskPath=:modules:refactorkit-cli:packagedMavenModuleRenameQualificationTest\n",
            Charsets.UTF_8,
        )

        systemProperty(
            "refactorkit.packaged.module.rename.repository.root",
            rootProject.layout.projectDirectory.asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.candidate.archive",
            packagedMavenModuleRenameQualificationCandidateArchive.get().asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.candidate.checksum",
            packagedMavenModuleRenameQualificationCandidateChecksum.get().asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.candidate.root",
            packagedMavenModuleRenameQualificationCandidateRoot.get().asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.reports.logs",
            packagedMavenModuleRenameQualificationLogs.get().asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.reports.manifests",
            packagedMavenModuleRenameQualificationManifests.get().asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.reports.failureDiagnostics",
            packagedMavenModuleRenameQualificationFailureDiagnostics.get().asFile.absolutePath,
        )
        systemProperty(
            "refactorkit.packaged.module.rename.nativeQualification",
            packagedMavenModuleRenameNativeQualificationRequested().toString(),
        )
    }

    doLast {
        packagedMavenModuleRenameQualificationTestOutcome.get().asFile.writeText(
            "schemaVersion=1\nstate=COMPLETED\ntaskPath=:modules:refactorkit-cli:packagedMavenModuleRenameQualificationTest\n",
            Charsets.UTF_8,
        )
    }

    reports {
        junitXml.required.set(true)
        junitXml.outputLocation.set(
            layout.buildDirectory.dir("test-results/packagedMavenModuleRenameQualificationTest"),
        )
        html.required.set(true)
        html.outputLocation.set(
            layout.buildDirectory.dir("reports/tests/packagedMavenModuleRenameQualificationTest"),
        )
    }
}

val finalizePackagedMavenModuleRenameQualificationEvidence = tasks.register<Exec>(
    "finalizePackagedMavenModuleRenameQualificationEvidence",
) {
    group = "verification"
    description = "Close and validate one run-level host evidence manifest after packaged module-rename qualification."
    mustRunAfter(packagedMavenModuleRenameQualificationTest)
    inputs.file(
        rootProject.layout.projectDirectory.file(
            "scripts/finalize-packaged-maven-module-rename-qualification.py",
        ),
    ).withPropertyName("qualificationEvidenceFinalizer")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.files(
        packagedMavenModuleRenameQualificationHostManifest,
        packagedMavenModuleRenameQualificationJunitAggregate,
        packagedMavenModuleRenameQualificationFailureAggregate,
        packagedMavenModuleRenameQualificationFocusedLog,
    ).withPropertyName("closedQualificationEvidence")
    outputs.upToDateWhen { false }
    workingDir = rootProject.projectDir

    doFirst {
        val command = mutableListOf(
            System.getenv("PYTHON")?.takeIf(String::isNotBlank) ?: "python",
            rootProject.file("scripts/finalize-packaged-maven-module-rename-qualification.py").absolutePath,
            "--repository-root",
            rootProject.projectDir.absolutePath,
            "--build-directory",
            layout.buildDirectory.get().asFile.absolutePath,
        )
        if (packagedMavenModuleRenameNativeQualificationRequested()) {
            command += "--native-qualification"
        }
        commandLine(command)
    }
}

packagedMavenModuleRenameQualificationTest.configure {
    finalizedBy(finalizePackagedMavenModuleRenameQualificationEvidence)
}
