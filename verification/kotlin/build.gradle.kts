import com.google.devtools.ksp.gradle.KspAATask

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
}

val jimmerVersion = "0.12.4"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("com.h2database:h2:2.2.224")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    testImplementation(kotlin("test"))
    testImplementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

tasks.withType<KspAATask>().configureEach {
    val dtoDirectory = if (name == "kspKotlin") "src/main/dto" else "src/test/dto"
    inputs.files(fileTree(dtoDirectory) { include("**/*.dto") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
