// The Android app: Jetpack Compose and Material 3, on the logic in :core. It ships the shared bundle (data, tables,
// photos) as assets and the two brand fonts as font resources. The Android Gradle plugin compiles the Kotlin itself,
// so only the Compose compiler plugin is applied next to it.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "garden.hereabouts.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "garden.hereabouts.app"
        // Android 8.0, the first with java.time and variable fonts.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "garden.hereabouts.app.HereaboutsTestRunner"
    }

    // Compiled for Java 17, the same as :core.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Licence notes that two test libraries both carry; neither is needed in the app or its tests.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Check calls against Java 17's library, as :core does.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// The shared folders, found the same way here and in the public copy of the app code: shared/ sits next to android/.
val sharedDir = rootProject.projectDir.resolve("../shared").normalize()

// The screen tests read the website's answers (the plant list for a county, search and filters) from the test app's
// own assets. Only the test app carries them; the app never does.
android.sourceSets.getByName("androidTest").assets.srcDir(sharedDir.resolve("bundle/expected"))

/**
 * Copies the bundle into the app's assets: data/, tables/, images/ and the fonts' licences. It leaves out expected/,
 * the answers only the logic tests read, and the font files, which ship once, as font resources.
 */
abstract class BundleAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(bundleDir) { exclude("expected/**", "fonts/*.ttf") }
            into(outputDir)
        }
    }
}

/** Copies the two brand fonts into res/font/, renamed to the lower-case names Android resources need. */
abstract class BrandFonts : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fontsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(fontsDir) {
                include("YoungSerif-Regular.ttf")
                rename { "young_serif.ttf" }
            }
            from(fontsDir) {
                include("Figtree*.ttf")
                rename { "figtree.ttf" }
            }
            into(outputDir.dir("font"))
        }
    }
}

// Each variant gets the two folders as generated sources, so every task that reads assets or resources (the build,
// lint, the tests) runs the copy first.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val assets = tasks.register<BundleAssets>("copy${name}BundleAssets") {
            bundleDir.set(sharedDir.resolve("bundle"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(assets, BundleAssets::outputDir)
        val fonts = tasks.register<BrandFonts>("copy${name}BrandFonts") {
            fontsDir.set(sharedDir.resolve("fonts"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(fonts, BrandFonts::outputDir)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
