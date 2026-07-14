import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
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
                        // Loaded reflectively only by the bundled Java 21 runtime; keeps RefactorKit API bytecode at 8.
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
