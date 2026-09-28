# Reflector

**Offline-first data synchronisation for Kotlin.** A Kotlin Multiplatform client SDK (Room, Ktor)
keeps a device's tables in step with a JVM server module (Exposed, PostgreSQL) that you embed in
your own backend.

Your users write whenever they want — in a tunnel, on a plane, on two devices at once — and
Reflector gets those writes to the server and to every other device, in order, without losing any
of them and without guessing at conflicts on the user's behalf.

- **Your data stays yours.** Reflector owns the *metadata* of synchronisation — what is dirty, what
  is in flight, where the cursor is. Your rows, schema, queries and migrations are untouched; the
  library reaches them through one adapter you write.
- **Embeddable, not a service.** The server side is a library inside your backend. You keep
  authentication, routing and deployment; it brings ordering, versions, conflicts and its own
  database schema.
- **Multiplatform.** The client runs on Android, iOS (arm64 and simulator) and the JVM.

> **Status:** early releases — the latest is on the
> [releases page](https://github.com/VoirDev/reflector/releases). The mechanism is implemented and
> covered by tests; the API may still move. What is known to be missing is listed in
> [TODO.md](TODO.md).

---

## Core principles

Each of these is a decision, and each has its reasoning in [How it works](docs/concepts.md).

1. **State, not operations.** A change is a full snapshot of an entity, not a diff. Two devices that
   edited the same entity have a conflict, and somebody decides — no merge semantics the user cannot
   predict.
2. **Atomic groups.** Everything you mark inside one `mutate { }` block is one group, and the server
   applies it all or not at all. That is how an invariant across rows survives synchronisation.
3. **Explicit conflicts.** A stale write is refused, never overwritten. Your adapter resolves the
   conflict in code, or leaves it to the user with both versions in hand. Nothing expires one.
4. **The server orders, the client follows.** Versions and cursors are opaque to the client, and the
   client clock takes part in no decision. The server's change log has no gaps and no visibility
   holes.
5. **Crash-safe by construction.** Applying a batch of changes and advancing the cursor happen in
   one transaction with your own rows — which is why the library's tables live in *your* database.
6. **Real time is an alarm clock, not a data path.** The WebSocket only says "something changed";
   data always travels over an ordinary pull, so polling is a trivial fallback.
7. **Never silently lose work.** Sign-out with unsent changes, a queue blocked by a refusal, a file
   that will never arrive — each is reported to the application, which decides.
8. **Files beside the log, never through it.** Bytes go between the device and *your* storage
   directly. A record may be published before its file, and a device need not hold every file.

## How it fits together

```text
 Device                                            Your backend
┌──────────────────────────────┐                 ┌───────────────────────────────┐
│ Your UI ── Your Room tables  │                 │ Your routes + authorisation   │
│               ▲              │  HTTP push/pull │               │               │
│      CollectionAdapter       │ ◄─────────────► │         SyncModule            │
│               │              │  WS invalidate  │  (ordering, versions, log)    │
│  client-sdk: queue, cursor,  │                 │               │               │
│  conflicts, workers          │                 │  PostgreSQL, `sync` schema    │
└──────────────────────────────┘                 └───────────────────────────────┘
        │  file bytes (optional), presigned, directly  ▲
        └──────────────────►  Your object storage  ◄───┘
```

The vocabulary you will meet everywhere:

| Term | Meaning |
|---|---|
| **Scope** | Whose data it is — usually a user, possibly a shared workspace. Authorised by your host. |
| **Collection** | The unit of consistency: its own cursor, queue, worker and bootstrap. |
| **Entity type / entity** | A kind of record in a collection, and one record, identified by a UUID you generate. |
| **Group** | The entities marked in one `mutate { }` block, applied by the server atomically. |
| **Cursor** | An opaque position in a collection's change log. |

## Requirements

| | |
|---|---|
| Kotlin | 2.4.20 |
| JDK | 21 for the server and the JVM client; Android bytecode targets 17 |
| Client targets | Android (minSdk 26), iOS `iosArm64` / `iosSimulatorArm64`, JVM |
| Client stack | Room 3 (`androidx.room3`) with KSP, Ktor client, kotlinx.serialization |
| Server stack | Exposed 1.5 on JDBC, PostgreSQL, Flyway (bundled); any HTTP framework |

## Installation

Releases are published to **GitHub Packages**, which asks for a token even to read. Create a
personal access token with `read:packages` and keep it out of the build files:

```properties
# ~/.gradle/gradle.properties  (or ORG_GRADLE_PROJECT_reflectorUsername / …Password in CI)
reflectorUsername=<your GitHub user>
reflectorPassword=<token with read:packages>
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven {
            name = "reflector"
            url = uri("https://maven.pkg.github.com/VoirDev/reflector")
            credentials(PasswordCredentials::class)
            content { includeGroup("dev.voir.reflector") }
        }
    }
}
```

In the snippets below, `<version>` is the latest release on the
[releases page](https://github.com/VoirDev/reflector/releases). The client and server artifacts are
released together, so both take the same number.

**Server** — a JVM backend:

```kotlin
dependencies {
    implementation("dev.voir.reflector:server-sdk:<version>")
    implementation("org.postgresql:postgresql:42.7.13")
}
```

**Client** — a Kotlin Multiplatform module, declared once in `commonMain`; Gradle picks the
Android, iOS or JVM variant for each target:

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room3")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.voir.reflector:client-sdk:<version>")
            implementation("androidx.sqlite:sqlite-bundled:2.7.1")   // or any SQLite driver
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.6.0") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.0") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:3.6.0") }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room runs KSP per target; without these lines the DAOs are not generated.
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.3")
    add("kspJvm", "androidx.room3:room3-compiler:3.0.3")
    add("kspIosArm64", "androidx.room3:room3-compiler:3.0.3")
    add("kspIosSimulatorArm64", "androidx.room3:room3-compiler:3.0.3")
}
```

`dev.voir.reflector:sync-protocol`, the shared wire vocabulary, arrives with either SDK. Room,
coroutines and kotlinx.serialization arrive with `client-sdk` too — your `@Database` is written
against them.

## Quick start

A ledger with wallets and transactions, condensed from the [samples](#samples). The guides walk
through every step in full.

### Server

```kotlin
fun main() {
    val dataSource = HikariDataSource(/* your PostgreSQL */)

    // 1. The module's own `sync` schema, with its own Flyway history.
    SyncMigrations.migrate(dataSource)

    // 2. Register what may be synchronised. Unregistered entity types are refused.
    val module = SyncModule.create(
        database = Database.connect(dataSource),
        config = syncConfig(
            collections = setOf(
                CollectionSpec(
                    id = CollectionId("ledger"),
                    entityTypes = setOf(EntityType("wallet"), EntityType("transaction")),
                ),
            ),
        ),
        // Called after each commit: tell connected clients to pull.
        commitListeners = listOf(SyncCommitListener { scope, collection, seq -> events.publish(scope, collection, seq) }),
    )

    // 3. Publish the operations on your own routes, after your own authorisation.
    embeddedServer(Netty, port = 8080) {
        routing {
            post("/v1/sync/{scope}/{collection}/push") {
                val scope: ScopeId = authorize(call)          // your access model
                val collection = CollectionId(requireNotNull(call.parameters["collection"]))
                call.respond(module.service.push(scope, collection, call.receive<PushRequest>()))
            }
            // …/changes, …/snapshot, /config and the events socket: see the server guide
        }
    }.start(wait = true)
}
```

### Client

```kotlin
// 1. The library's tables live in your database, beside your own.
@Database(entities = [Wallet::class, /* … */ SyncCollectionEntity::class, /* the other Sync*Entity */], version = 1)
abstract class LedgerDatabase : RoomDatabase(), SyncDatabase

// 2. One adapter per collection: how to read an entity, and how to apply a remote change.
class LedgerAdapter(private val dao: LedgerDao) : CollectionAdapter {
    override suspend fun snapshot(entityType: EntityType, id: EntityId): JsonObject? = /* your row → JSON, or null if gone */
    override suspend fun applyRemote(ops: List<RemoteOp>) { /* upsert or delete your rows */ }
    override suspend fun onRejected(entityType: EntityType?, id: EntityId?, rejection: SyncRejection) { /* tell the user */ }
    override suspend fun resolve(conflict: Conflict): Resolution? = null   // null → the user decides
}

// 3. Wire the engine.
val http = syncHttpClient(OkHttp.create())
val engine = SyncEngine(
    database = database,
    transactions = RoomSyncTransactionRunner(database),
    transport = KtorSyncTransport(http, baseUrl, tokens),
    eventChannel = KtorSyncEventChannel(http, baseUrl, tokens),
    adapters = mapOf(LEDGER to LedgerAdapter(database.ledgerDao())),
    coroutineScope = applicationScope,
)
val ledger = engine.scope(ScopeId(userId)).collection(LEDGER)

// 4. Write through `mutate`: it is the transaction, and everything marked in it is one group.
ledger.mutate {
    dao.upsertWallet(Wallet(walletId, "Cash", "EUR"))
    markUpserted(WALLET, EntityId(walletId))
}

// 5. Read your rows as always; watch synchronisation through state.
ledger.state.collect { state -> showStatus(state.phase, state.pendingCount, state.conflictCount) }
```

## Documentation

| | |
|---|---|
| [How it works](docs/concepts.md) | The problem, the mechanism and the reasoning behind every choice |
| [Server guide](docs/server-guide.md) | Embedding the module: migrations, collections, routes, access, events, maintenance |
| [Client guide](docs/client-guide.md) | Database, adapter, transport, engine, writes, state, conflicts, triggers |
| [Files](docs/files-guide.md) | Optional blob synchronisation, on both sides |
| [Wire protocol](docs/protocol.md) | Endpoints, status codes, and the rules a server must honour |
| [Testing](docs/testing.md) | How to test an integration |
| [Specifications](docs/README.md#specifications) | The source of truth for the mechanism: client, server, files |
| [Development](docs/development.md) | Building this repository, CI and releases |

## Samples

Everything in the guides is taken from working code, compiled and covered by tests:

- [`samples/ledger-server`](samples/ledger-server) — a reference host: routes, scope authorisation,
  revocation, events, file storage;
- [`samples/ledger/shared`](samples/ledger/shared) — a consumer application: its own tables, adapter,
  wiring;
- [`samples/ledger/android`](samples/ledger/android) — a thin Android application with the platform
  triggers wired up for real.

## Contributing

Build and test with `./gradlew build`; the server tests start PostgreSQL through Testcontainers and
need Docker. Repository rules are in [AGENTS.md](AGENTS.md), and [docs/development.md](docs/development.md)
covers the build, CI and releases.
