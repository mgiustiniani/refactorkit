plugins {
    kotlin("plugin.serialization")
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.refactorkit.lsp.RefactorKitLspKt")
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation(project(":modules:refactorkit-java"))
    implementation(project(":modules:refactorkit-jvm"))
    implementation(project(":modules:refactorkit-tree-sitter"))
    implementation(project(":modules:refactorkit-typescript"))
    implementation(project(":modules:refactorkit-kotlin"))
    implementation(project(":modules:refactorkit-c"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":modules:refactorkit-c")))
    testImplementation("io.cucumber:cucumber-java:7.20.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.20.1")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.2")
}

sourceSets.test {
    resources.srcDir(rootProject.file("features"))
}

tasks.test {
    useJUnitPlatform()
    // Native C artifact capture exhausts the default 512 MiB test heap before
    // the managed lifecycle completes. Match the bounded CLI qualification heap.
    maxHeapSize = "2g"
    maxParallelForks = 1
    providers.gradleProperty("refactorkitCucumberFilter").orNull?.let { filter ->
        systemProperty("cucumber.filter.tags", filter)
    }
}
