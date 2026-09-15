plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// A reference host: it shows how a backend wires in the sync module, resolves a ScopeId
// with its own access model, and exposes the transport.
application {
    mainClass.set("dev.voir.reflector.sample.ledger.server.ApplicationKt")
}

dependencies {
    implementation(projects.serverSdk)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    implementation(libs.postgresql.driver)
    implementation(libs.hikaricp)

    // The host's logging backend, and the module's only connection to it. server-sdk depends on no
    // logging library at all: it reports through the SyncLog port, and Slf4jSyncLog in this module
    // is the adapter. Ktor and Flyway bring slf4j-api along, so only the backend is declared here.
    implementation(libs.logback.classic)

    // The host creates the Database itself and hands it to the module: the module never picks
    // up Exposed's global default.
    implementation(platform(libs.exposed.bom))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.ktor.server.testHost)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)

    // The end-to-end test talks to the host through the real client transport: it is the only
    // way to verify that client and server speak one language rather than two similar ones.
    testImplementation(projects.clientSdk)
    testImplementation(libs.ktor.client.contentNegotiation)

    // Convergence needs two whole clients, not two transports: the demonstration application
    // brings the Room database and the adapter, so the test drives the same wiring an application
    // would rather than a second implementation of it.
    testImplementation(projects.samples.ledger.shared)
    testImplementation(libs.sqlite.bundled)
}
