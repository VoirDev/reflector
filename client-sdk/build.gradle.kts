plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.serialization)
}

// The whole client library in one module, published as `dev.voir.reflector:client-sdk`.
// The internal division is by package rather than by Gradle module: `core` holds the
// contracts the application implements, `persistence` the Room tables, `network` the Ktor
// transport, `engine` the machinery that drives them. An application depends on one artifact
// because it needs all four anyway — the SDK's tables live in the application's own database,
// so the entities are as much a part of the contract as the interfaces are.
kotlin {
    // The module's public API is a contract for external consumers:
    // require explicit visibility modifiers and return types.
    explicitApi()

    jvm()
    android {
        namespace = "dev.voir.reflector.sync"
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
            // Protocol identifiers are the shared vocabulary of client and server, not a
            // transport detail: duplicating them in the SDK would mean mapping one onto the
            // other on every call.
            api(projects.contracts.syncProtocol)

            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)

            // The application declares the SDK's entities in its own `@Database` and hands the
            // `RoomDatabase` back to the engine: Room is part of the contract, not a detail.
            api(libs.room.runtime)
            // RawSource is part of the blob ports the application implements.
            api(libs.kotlinx.io.core)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }

        // A SQLite driver is the only place the library needs one: the tests open a database of
        // their own. An application supplies its own driver — bundled, Android or native is its
        // choice, not the SDK's — so shipping one in the published artifact would be deciding
        // for it.
        jvmTest.dependencies {
            implementation(libs.sqlite.bundled)
        }
    }
}

room3 {
    // Room schemas are versioned in the repository: without them migrations cannot be
    // verified by an automated test.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room runs the KSP processor separately for every target: KMP has no shared `ksp`
    // configuration, and without listing the targets explicitly the DAOs are simply not
    // generated.
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)

    // The engine tests run against a real database rather than a fake store: almost all of the
    // logic there is SQL (record dirtiness, group merging, cursor advancement), and a fake would
    // only be verifying itself. The database is declared in `jvmTest` and needs its own processor
    // round: the SQL is the same on every target, and running it on one is an order of magnitude
    // faster.
    add("kspJvmTest", libs.room.compiler)
}
