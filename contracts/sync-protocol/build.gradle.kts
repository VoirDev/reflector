plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.serialization)
}

kotlin {
    // The module's public API is a contract for external consumers:
    // require explicit visibility modifiers and return types.
    explicitApi()

    jvm()
    android {
        namespace = "dev.voir.reflector.sync.protocol"
        compileSdk =
            libs.versions.androidCompileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()

        // Without host tests the Android target has no source set for unit tests,
        // and the module's common tests simply never run on it.
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // JsonObject and Json are part of the module's public contract.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
