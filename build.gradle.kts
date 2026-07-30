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
    version = "0.7.0-SNAPSHOT"
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
    dependsOn(":modules:refactorkit-cli:refactorkitRuntimeDist")
}
