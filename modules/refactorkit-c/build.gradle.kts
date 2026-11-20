plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    testFixturesImplementation(project(":modules:refactorkit-core"))
}

tasks.test {
    useJUnitPlatform()
    // The full compiler-backed suite retains multiple Clang/JDT diagnostics and
    // native-resource snapshots in one test worker. The Gradle default (512 MiB)
    // reproducibly exhausts its heap before the suite completes; keep this worker
    // bounded to one fork with enough heap for the pinned toolchain acceptance.
    maxHeapSize = "2g"
    maxParallelForks = 1
}
