plugins {
    // AGP 9 brings Kotlin support with it: adding `org.jetbrains.kotlin.android` here is refused.
    alias(libs.plugins.androidApplication)
}

// A deliberately thin application: it exists so that the platform integration described in
// EXAMPLE.md is executed rather than only compiled. Everything about the data lives in the shared
// module; what is here is the part that can only be written against Android.
android {
    namespace = "dev.voir.reflector.sample.ledger.android"
    compileSdk =
        libs.versions.androidCompileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.voir.reflector.sample.ledger"
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.androidTargetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName =
            providers
                .fileContents(rootProject.layout.projectDirectory.file("VERSION"))
                .asText
                .get()
                .trim()
    }

    lint {
        // The libraries this depends on are linted by their own builds, and their findings belong
        // there rather than against the sample that happens to consume them.
        checkDependencies = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.androidJvmTarget.get())
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    implementation(projects.samples.ledger.shared)

    // The engine, the transport and the trigger sources are used directly here: the shared module
    // keeps them as `implementation`, because wiring the platform in is the application's job.
    implementation(projects.clientSdk)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sqlite.bundled)
    implementation(libs.androidx.work.runtime)
}
