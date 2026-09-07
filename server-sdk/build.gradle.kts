plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.serialization)
    id("java-library")
}

kotlin {
    // The module's public API is a contract for the host.
    explicitApi()
}

// The embeddable sync module: the contracts the host sees — operations, models, configuration and
// listeners — together with their Exposed and PostgreSQL implementation and its Flyway migrations
// in the `sync` schema.
//
// The two used to be separate modules so that Gradle could enforce where storage types are allowed
// to appear. The rule has not changed; what enforces it has. The `dev.voir.reflector.sync.server`
// package holds the contracts and must mention no Exposed, JDBC or PostgreSQL type — it currently
// mentions none — while `.postgres` is the implementation and may: `SyncModule.create` takes the
// host's Exposed `Database` on purpose, so that the module never picks up a global default.
// That boundary is now a review rule rather than a compile error, which is the price of the merge.
// See AGENTS.md.
dependencies {
    // Protocol types appear in operation signatures: this is a contract, not a detail.
    api(projects.contracts.syncProtocol)
    api(libs.kotlinx.serialization.json)

    // The host creates the Database itself and hands it to the module: the module never picks
    // up Exposed's global default. That makes Exposed's `Database` part of the public signature of
    // `SyncModule.create`, so the BOM and the JDBC artefact that declares it are `api` — a consumer
    // that cannot name the type it has to pass cannot call the function at all. The rest of Exposed
    // is used only inside the implementation and stays off the consumer's compile classpath.
    api(platform(libs.exposed.bom))
    api(libs.exposed.jdbc)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.kotlinDatetime)
    implementation(libs.exposed.json)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.hikaricp)
}
