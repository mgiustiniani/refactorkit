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
    implementation("com.fasterxml.woodstox:woodstox-core:7.1.1")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("io.cucumber:cucumber-java:7.20.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.20.1")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.2")
}

sourceSets.test {
    resources.srcDir(rootProject.file("features"))
}

tasks.test {
    useJUnitPlatform()
}
