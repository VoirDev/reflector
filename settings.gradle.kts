rootProject.name = "reflector"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Sync protocol contracts shared by the client and the server.
include(":contracts:sync-protocol")

// The client-side sync library.
include(":client-sdk")

// The server-side sync module, embeddable into any Kotlin/Java backend.
include(":server-sdk")

// Demonstration consumers of the SDK.
include(":samples:ledger:shared")

// The thin Android application around the shared module: it exists to run the platform
// integration the guide describes — lifecycle, connectivity and background triggers — rather
// than to leave it compiled and untried.
include(":samples:ledger:android")
include(":samples:ledger-server")
