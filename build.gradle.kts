import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.github.spotbugs") version "6.5.9" apply false
}

val buildJdkFeature = Runtime.version().feature()
if (buildJdkFeature != 21) {
    throw GradleException(
        "RefactorKit source builds require JDK 21; detected JDK $buildJdkFeature. " +
            "Set JAVA_HOME to a JDK 21 installation. Packaged runtimes remain self-contained.",
    )
}

allprojects {
    group = "org.refactorkit"
    version = "0.8.0"
}

subprojects {
    dependencies {
        components {
            listOf("tree-sitter", "tree-sitter-typescript", "tree-sitter-javascript").forEach { artifact ->
                withModule("io.github.bonede:$artifact") {
                    allVariants {
                        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8)
                    }
                }
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "8"
        targetCompatibility = "8"
        options.release.set(8)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        val boundedTemp = rootProject.layout.buildDirectory.dir("test-tmp/${project.name}")
        doFirst { boundedTemp.get().asFile.mkdirs() }
        systemProperty("java.io.tmpdir", boundedTemp.get().asFile.absolutePath)
        environment("TMPDIR", boundedTemp.get().asFile.absolutePath)
        if (project.path == ":modules:refactorkit-cli") {
            val qualificationGradle = project.providers.environmentVariable("REFACTORKIT_GRADLE_EXECUTABLE")
                .orElse(project.providers.provider {
                    val launcher = if (System.getProperty("os.name").startsWith("Windows")) "gradle.bat" else "gradle"
                    requireNotNull(gradle.gradleHomeDir).resolve("bin/$launcher").absolutePath
                })
            environment("REFACTORKIT_GRADLE_EXECUTABLE", qualificationGradle.get())
        }
        if (name == "test" && project.path in setOf(":modules:refactorkit-cli", ":modules:refactorkit-lsp")) {
            val callerRuntime = project.providers.environmentVariable("REFACTORKIT_C_QUERY_CANDIDATE_ROOT")
            val cli = rootProject.project(":modules:refactorkit-cli")
            val ownedRuntime = cli.providers.gradleProperty("refactorkit.package.dir")
                .orElse(cli.layout.buildDirectory.dir("package/refactorkit").map { it.asFile.absolutePath })
            if (!callerRuntime.isPresent) dependsOn(":modules:refactorkit-cli:refactorkitRuntimeDist")
            environment("REFACTORKIT_C_QUERY_CANDIDATE_ROOT", callerRuntime.orElse(ownedRuntime).get())
            val evidenceKey = if (project.path == ":modules:refactorkit-cli")
                "refactorkit.ts.packaged.evidence" else "refactorkit.c.lsp.packaged.evidence"
            val evidence = project.providers.systemProperty(evidenceKey)
                .orElse(project.layout.buildDirectory.dir("reports/runtime-wire/$name").map { it.asFile.absolutePath })
            systemProperty(evidenceKey, evidence.get())
            outputs.dir(evidence).withPropertyName("runtimeWireEvidence")
            doFirst { file(evidence.get()).mkdirs() }
        }
        if (project.path in setOf(":modules:refactorkit-c", ":modules:refactorkit-cli")) {
            project.providers.environmentVariable("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN").orNull?.let {
                systemProperty("refactorkit.c.qualificationToolchain", it)
            }
            // Ordinary source/CI tests retain native evidence without caller-only setup.
            // Distinct task/module directories also prevent unrelated runs overwriting it.
            mapOf(
                "compilerRuntimeEvidence" to ("REFACTORKIT_C_COMPILER_RUNTIME_EVIDENCE" to "compiler-runtime"),
                "formatterRuntimeEvidence" to ("REFACTORKIT_C_FORMATTER_RUNTIME_EVIDENCE" to "formatter-runtime"),
                "runtimeDiscoveryEvidence" to ("REFACTORKIT_C_RUNTIME_DISCOVERY_EVIDENCE" to "runtime-discovery"),
                "runtimeManifestEvidence" to ("REFACTORKIT_C_RUNTIME_MANIFEST_EVIDENCE" to "runtime-manifest"),
            ).forEach { (key, selectors) ->
                val (environmentKey, directory) = selectors
                val property = "refactorkit.c.$key"
                val destination = project.providers.environmentVariable(environmentKey)
                    .orElse(project.layout.buildDirectory.dir("reports/c-qualification/$name/$directory")
                        .map { it.asFile.absolutePath })
                systemProperty(property, destination.get())
                outputs.dir(destination).withPropertyName(key)
                doFirst { file(destination.get()).mkdirs() }
            }
        }
    }

    // ── Static analysis plugins ──
    pluginManager.apply("pmd")
    pluginManager.apply("com.github.spotbugs")

    // PMD configuration (via reflection)
    val pmdExt = project.extensions.findByName("pmd")
    if (pmdExt != null) {
        pmdExt::class.java.methods.forEach { m ->
            when (m.name) {
                "setConsoleOutput" -> m.invoke(pmdExt, true)
                "setRulesMinimumConfidence" -> m.invoke(pmdExt, 2)
                "setRuleSetsConfigFiles" -> m.invoke(pmdExt, listOf("category/java/bestpractices.xml", "category/java/errorprone.xml"))
                "setIgnoreFailures" -> m.invoke(pmdExt, true)
            }
        }
    }

    // SpotBugs extension configuration (via reflection)
    val sbExt = project.extensions.findByName("spotbugs")
    if (sbExt != null) {
        sbExt::class.java.methods.forEach { m ->
            when (m.name) {
                "setIgnoreFailures" -> m.invoke(sbExt, true)
                "setShowStackTraces" -> m.invoke(sbExt, true)
                "setShowProgress" -> m.invoke(sbExt, true)
                "setReportFormat" -> m.invoke(sbExt, "html")
                "setReportsDir" -> m.invoke(sbExt, layout.buildDirectory.dir("reports/spotbugsMain").get().asFile)
            }
        }
    }
}

val leafModules = subprojects.flatMap { p -> if (p.subprojects.isEmpty()) listOf(p) else p.subprojects }
tasks.register("pmdAll") {
    group = "verification"
    description = "Run PMD on all subprojects"
    dependsOn(leafModules.map { "${it.path}:pmdMain" })
}

tasks.register("spotbugsAll") {
    group = "verification"
    description = "Run SpotBugs on all subprojects"
    dependsOn(leafModules.map { "${it.path}:spotbugsMain" })
}

tasks.register("goldenTest") {
    group = "verification"
    description = "Run the RefactorKit golden refactoring acceptance suite."
    dependsOn(":modules:refactorkit-testkit:test")
}

tasks.register("packageCliRuntime") {
    group = "distribution"
    description = "Build the self-contained RefactorKit CLI package with embedded Java runtime."
    dependsOn(":modules:refactorkit-cli:refactorkitRuntimeDist")
}

tasks.register("distCliRuntimeZip") {
    group = "distribution"
    description = "Build the zipped self-contained RefactorKit CLI package."
    dependsOn(":modules:refactorkit-cli:refactorkitRuntimeZip")
}
