import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin JVM module: all format logic of the meeting folder (Constitution Principle VII).
// It MUST NOT apply any Android plugin and MUST NOT depend on Android libraries.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets {
    test {
        // The tests read the one and only copy of the documented schema.
        resources.srcDir("../specs/001-meeting-recording-mvp/contracts")
    }
}

tasks.test {
    useJUnitPlatform()
}
