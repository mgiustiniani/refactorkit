plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(project(":modules:refactorkit-java"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kotlin.compiler.test.classpath", configurations.testRuntimeClasspath.get().asPath)
}
