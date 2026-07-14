plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    testImplementation(project(":modules:refactorkit-java"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
