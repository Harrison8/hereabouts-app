// The app's logic: reads the bundle, finds the county and builds the plant list like the website.
// Plain Kotlin on the JVM, with no Android imports, so it runs in an ordinary test on CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Built with JDK 21, but compiled for Java 17, so the Android app module (minSdk 26) can use this module.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Check calls against Java 17's library, not JDK 21's.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // api, not implementation: the module's public types use KSerializer and JsonElement.
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit5)
}

// The tests read the bundle that `npm run app:bundle` writes to apps/shared/bundle.
val bundleDir = rootProject.projectDir.resolve("../shared/bundle").normalize()

tasks.test {
    useJUnitPlatform()
    // The tests find the bundle as "../shared/bundle", so they run from the apps/android folder.
    workingDir = rootProject.projectDir
    // Rerun the tests when the bundle changes.
    inputs.dir(bundleDir.resolve("expected")).optional()
    inputs.dir(bundleDir.resolve("data")).optional()
    inputs.dir(bundleDir.resolve("tables")).optional()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
