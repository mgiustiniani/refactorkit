plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation(project(":modules:refactorkit-java"))
    implementation(project(":modules:refactorkit-kotlin"))
    testImplementation(project(":modules:refactorkit-testkit"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kotlin.compiler.test.classpath", configurations.testRuntimeClasspath.get().asPath)
}
