plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.37.0")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
