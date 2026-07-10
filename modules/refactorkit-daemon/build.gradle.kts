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
    implementation(project(":modules:refactorkit-web-importer"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
