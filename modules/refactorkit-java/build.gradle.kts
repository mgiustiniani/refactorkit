plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.44.0")
    implementation("org.apache.maven:maven-model-builder:3.9.9")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
