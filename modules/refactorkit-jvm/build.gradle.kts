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
    testImplementation("io.cucumber:cucumber-java:7.20.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.20.1")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.2")
}

sourceSets.test {
    resources.srcDir(rootProject.file("features"))
}

val testRuntimeClasspath = configurations.testRuntimeClasspath

tasks.test {
    useJUnitPlatform()
    doFirst {
        systemProperty("kotlin.compiler.test.classpath", testRuntimeClasspath.get().asPath)
    }
}
