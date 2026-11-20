import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
    testImplementation(project(":modules:refactorkit-java"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
    testImplementation(kotlin("test"))
}

val specBdd = sourceSets.create("specBdd") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[specBdd.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[specBdd.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(specBdd.implementationConfigurationName, "io.kotest:kotest-runner-junit5-jvm:5.9.1")
    add(specBdd.implementationConfigurationName, "io.kotest:kotest-assertions-core-jvm:5.9.1")
}

val specBddTest = tasks.register<Test>("specBddTest") {
    description = "Runs focused approved Kotlin Spec BDDs on an isolated test classpath."
    testClassesDirs = specBdd.output.classesDirs
    classpath = specBdd.runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(specBddTest)
}

val testRuntimeClasspath = configurations.testRuntimeClasspath

tasks.test {
    useJUnitPlatform()
    doFirst {
        systemProperty("kotlin.compiler.test.classpath", testRuntimeClasspath.get().asPath)
    }
}
