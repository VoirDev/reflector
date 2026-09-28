import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    // Plugins are declared here once with `apply false` so that they are not loaded
    // again into every subproject's classloader.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.spotless) apply false
}

val projectVersion =
    providers
        .gradleProperty("releaseVersion")
        .orElse(providers.fileContents(layout.projectDirectory.file("VERSION")).asText.map { it.trim() })
        .get()

/**
 * The commit a release is built from, written into every POM it publishes.
 *
 * Publication reads it back from a version the registry already holds, to tell the rerun of a
 * release that failed halfway from a different build published under the same number. Only the
 * scripts under `ci/release` set it; an ordinary build leaves it out of the POM altogether.
 */
val releaseRevision = providers.gradleProperty("releaseRevision")

/** The repository on GitHub: where the source lives, and the owner of its Maven packages. */
val sourceRepositoryUrl = "https://github.com/VoirDev/reflector"

allprojects {
    // One group for the whole repository. This works only because no two modules share a name:
    // a repository with `client-sdk/sync-core` and `server-sdk/sync-core` would publish both as
    // `dev.voir.reflector:sync-core`, Gradle would treat them as one module, silently drop one
    // from the classpath, and the error would surface at runtime as a ClassNotFoundException in
    // somebody else's test. Keep module names unique and this stays a non-problem.
    group = "dev.voir.reflector"
    version = projectVersion
}

val jvmToolchainVersion =
    libs.versions.jvmToolchain
        .get()
        .toInt()
val ktlintVersion = libs.versions.ktlint.get()
val warningsAsErrors = providers.gradleProperty("reflector.warningsAsErrors").orNull.toBoolean()

/**
 * Repository-wide Kotlin compiler settings.
 *
 * The opt-ins are listed here rather than in the sources because the handbook requires
 * `kotlin.uuid.Uuid` and `kotlin.time.Instant` in every contract: sprinkling `@OptIn`
 * across every file would be noise without benefit.
 */
fun KotlinCommonCompilerOptions.applyRepositoryDefaults(warningsAsErrors: Boolean) {
    allWarningsAsErrors.set(warningsAsErrors)
    progressiveMode.set(true)
    freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
        "-Xconsistent-data-class-copy-visibility",
    )
    optIn.addAll(
        "kotlin.uuid.ExperimentalUuidApi",
        "kotlin.time.ExperimentalTime",
    )
}

allprojects {
    // Intermediate projects such as `:samples:ledger` have no sources of their own: they have
    // no build file and exist only as containers for nested modules. On such a project Spotless
    // walks the whole tree including the children's `build/` directories and fails on the first
    // unreadable artifact (klib, linkdata), so only real modules are formatted.
    if (!buildFile.exists()) {
        return@allprojects
    }

    apply(plugin = "com.diffplug.spotless")

    extensions.configure<SpotlessExtension> {
        kotlin {
            // The tree starts at `src` rather than at the project root with `build/` excluded:
            // in the latter case Spotless still walks the build directories and fails on the
            // artifacts KSP and Kotlin/Native create and delete as they go
            // (klib, linkdata, generated/ksp).
            target(fileTree("src") { include("**/*.kt") })
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts", "src/**/*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(jvmToolchainVersion)

            compilerOptions {
                applyRepositoryDefaults(warningsAsErrors)
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(jvmToolchainVersion)

            compilerOptions {
                applyRepositoryDefaults(warningsAsErrors)
            }
        }
    }

    // AGP's lint reads the KSP output directories without saying that it does, and Gradle refuses
    // a task that consumes another's output with no dependency between them. It surfaces only once
    // something pulls a library's lint model — an Android application in the same build does — so
    // the missing edge is declared here rather than waited for.
    tasks.matching { it.name.startsWith("lint") || it.name.endsWith("LintModel") }.configureEach {
        dependsOn(tasks.matching { it.name.startsWith("ksp") })
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // A test waiting on a background worker does not fail on error — it hangs forever and
        // holds up the whole build. The ceiling is set here rather than in every test: forgetting
        // it in one place is cheaper than forgetting it in twenty.
        timeout.set(java.time.Duration.ofMinutes(5))
    }
}

/**
 * What the repository publishes, and how each artifact describes itself.
 *
 * The samples are consumers rather than products, and an intermediate container project such as
 * `:samples:ledger` has no sources of its own, so neither is listed here. Anything absent from this
 * map gets no `maven-publish` plugin at all: a module is published on purpose, not by default.
 */
val publishedProjects =
    mapOf(
        ":client-sdk" to
            (
                "Reflector client SDK" to
                    "Offline-first synchronisation for Kotlin Multiplatform: the sync engine, the " +
                    "Room tables it keeps its metadata in, and the Ktor transport."
            ),
        ":contracts:sync-protocol" to
            (
                "Reflector sync protocol" to
                    "Wire contracts shared by the Reflector client and server: identifiers, " +
                    "request and response bodies, serializers."
            ),
        ":server-sdk" to
            (
                "Reflector server SDK" to
                    "The embeddable Reflector sync module for a Kotlin or Java backend: " +
                    "operations, models, configuration and listeners, over Exposed and PostgreSQL."
            ),
    )

allprojects {
    val coordinates = publishedProjects[path] ?: return@allprojects

    apply(plugin = "maven-publish")

    // A Kotlin/JVM module has no publication of its own: the JVM plugin registers a software
    // component and stops there, where the multiplatform plugin registers the publications too
    // — one per target plus the common one that resolves between them.
    plugins.withId("java-library") {
        extensions.configure<JavaPluginExtension> {
            // Sources travel with the artifact so that a consumer stepping into the SDK in a
            // debugger lands in the code rather than in decompiled bytecode. The multiplatform
            // plugin already does this on its own.
            withSourcesJar()
        }
        extensions.configure<PublishingExtension> {
            publications.register<MavenPublication>("jvm") {
                from(components["java"])
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // A directory inside the build, which the release scripts build every artifact into
            // before anything leaves the machine: the release pull request stops there, and
            // publication checks what landed in it against ci/release/packages first.
            maven {
                name = "release"
                url = uri(rootProject.layout.buildDirectory.dir("release/repository"))
            }
            // GitHub Packages, which only Publish Release writes to. The credentials are the
            // `gitHubPackagesUsername` and `gitHubPackagesPassword` Gradle properties, read when a
            // task publishes here and not before, so every other build runs without them.
            maven {
                name = "gitHubPackages"
                url = uri("https://maven.pkg.github.com/VoirDev/reflector")
                credentials(PasswordCredentials::class)
            }
        }

        // `configureEach` rather than a loop: the multiplatform plugin adds its publications
        // late, and anything eager here would describe only the ones that already exist.
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set(coordinates.first)
                description.set(coordinates.second)
                url.set(sourceRepositoryUrl)

                scm {
                    url.set(sourceRepositoryUrl)
                    connection.set("scm:git:$sourceRepositoryUrl.git")
                    developerConnection.set("scm:git:$sourceRepositoryUrl.git")
                }

                // Absent unless a release script names the commit: see `releaseRevision`. Read
                // here rather than handed over as a provider, because a map property given an
                // absent entry loses its whole value, not just that entry.
                releaseRevision.orNull?.let { properties.put("reflector.revision", it) }

                // `licenses` and `developers` are deliberately absent: the repository has no
                // licence file, and a POM is the wrong place to invent one. GitHub Packages does
                // not ask for them; Maven Central rejects a POM without them, so they are the
                // first thing to add before publishing there.
            }
        }
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Full repository check: build, tests and formatting verification."
    dependsOn(subprojects.map { "${it.path}:build" })
}
