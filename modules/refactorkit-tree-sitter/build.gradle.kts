plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":modules:refactorkit-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    runtimeOnly("io.github.bonede:tree-sitter:0.25.3@jar")
    runtimeOnly("io.github.bonede:tree-sitter-typescript:0.23.2@jar")
    runtimeOnly("io.github.bonede:tree-sitter-javascript:0.23.1@jar")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.test {
    useJUnitPlatform()
}
