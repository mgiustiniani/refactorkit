import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "org.refactorkit"
    version = "0.4.0"
}

subprojects {
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
