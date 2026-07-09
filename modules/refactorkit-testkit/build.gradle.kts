plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation(project(":modules:refactorkit-java"))
    implementation(project(":modules:refactorkit-web-importer"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Run tests from the root project so relative paths like testdata/ and samples/ resolve correctly
    workingDir = rootProject.projectDir
}
