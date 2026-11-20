plugins {
    kotlin("plugin.serialization")
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.refactorkit.daemon.RefactorKitDaemonKt")
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation(project(":modules:refactorkit-java"))
    implementation(project(":modules:refactorkit-tree-sitter"))
    implementation(project(":modules:refactorkit-typescript"))
    implementation(project(":modules:refactorkit-kotlin"))
    implementation(project(":modules:refactorkit-jvm"))
    implementation(project(":modules:refactorkit-c"))
    implementation(project(":modules:refactorkit-web-importer"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("io.cucumber:cucumber-java:7.20.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.20.1")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.2")
}

sourceSets.test {
    resources.srcDir(rootProject.file("features"))
}

tasks.test {
    useJUnitPlatform()
}
