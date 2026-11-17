// The Android app's Gradle build. For now it has one module, core: the app's logic in plain Kotlin, tested
// against the same expected answers as the website and the iPhone app (apps/shared/bundle/expected).
// Every plugin and library comes from Maven Central or Google's Maven; the Kotlin plugins are published on Central.
pluginManagement {
    repositories {
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // Every repository is listed here; a module that adds its own fails the build.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "hereabouts"
include(":core")
