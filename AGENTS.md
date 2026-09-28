# Instructions for agents and developers

The repository of a library that synchronises data between many clients and one server.
The client is Kotlin Multiplatform (Room, Ktor), the server is JVM. The mechanism is
implemented and covered by tests; what is known to be missing is collected in
[TODO.md](TODO.md).

## Repository layout

```text
README.md                    The public front page: principles, installation, quick start
TODO.md                      Work that is known and not yet done
docs/
  README.md                  Index of the documentation
  concepts.md                How it works: the mechanism and the reasoning behind each choice
  server-guide.md            Integrating the server module, step by step
  client-guide.md            Integrating the client SDK, step by step
  files-guide.md             Integrating file synchronisation, on both sides
  protocol.md                Endpoints, status codes and the rules a server must honour
  testing.md                 Testing an integration
  development.md             Building this repository, CI and releases
  sync-client-design.md      Client library specification
  sync-server-design.md      Server module specification
  sync-files-design.md       File specification: blobs, references, and who owns the bytes
.claude/settings.json        Enables the `handbooks` plugin, which carries the engineering standard
.github/workflows/           Verify, Prepare Release and Publish Release
.github/dependabot.yml       Weekly, grouped dependency updates for Gradle and the actions
ci/
  verify                     What a pull request must pass; the same command locally and in CI
  release/                   The release scripts, and `packages`, the inventory of what is published
contracts/
  sync-protocol/             Protocol wire contracts shared by client and server
client-sdk/                  The client library, one module published as one artifact
  src/.../sync/core/         Public SDK contracts and sync domain types
  src/.../sync/network/      Ktor protocol client: HTTP pull/push and the WebSocket channel
  src/.../sync/persistence/  Room tables for sync metadata, and transactions
  src/.../sync/engine/       Workers, push groups, cursors, conflict resolution
server-sdk/                  The server module, one module published as one artifact
  src/.../sync/server/       The module's public API: operations, models, config, listeners
  src/.../sync/server/postgres/  Exposed + PostgreSQL implementation and Flyway migrations
samples/
  ledger/shared/             Demonstration client
  ledger/android/            Thin Android application: the platform triggers, wired up for real
  ledger-server/             Reference host embedding the server module
build.gradle.kts             Root build: settings shared by every module
gradle/libs.versions.toml    The single version catalogue
VERSION                      Repository version; every module takes its version from it
```

## Handbook

The engineering standard this repository holds to is the **`handbooks` plugin**, from the
[`voir-handbooks`](https://github.com/VoirDev/handbook) marketplace. It is enabled for this
repository in [`.claude/settings.json`](.claude/settings.json), and it ships the standard as
on-demand skills rather than as documents checked in here.

The repository used to keep its own copy in `handbooks/`. It does not any more, and that is the
point: one copy, shared across Voir projects, versioned and updated where it is written instead of
drifting per repository. There is nothing in this repository to reconcile the plugin against, and a
rule the plugin does not carry is not a rule here.

The plugin is the authority on its own contents. It will grow and its skills will be revised, so
read what the plugin offers rather than what any list — including this one — says it offers.

Before any change to Kotlin code, invoke in this order:

1. `kotlin-style` — always mandatory, for every module and source set.
2. The skill covering the area being changed, when the plugin has one. Today that is
   `exposed-v1-5` for the `sync.server.postgres` package of `server-sdk/`, which is mandatory
   before touching it, and `optional-kmp-v1` for three-state partial updates. The plugin also
   carries `kmp-application-architecture`, and `github-verify-workflow` and
   `github-release-workflow`, which are mandatory before touching `.github/workflows/` or `ci/`.
3. Where no skill covers the area — the client SDK's application-level concerns are the current
   example — `kotlin-style` and this repository's own rules are the whole of the standard. That is
   a real answer, not a gap to be filled by guessing at a convention.

The versions line up rather than being assumed to: this repository builds on Kotlin 2.4.10 and
Exposed 1.5.0, which is what `kotlin-style` and `exposed-v1-5` are written against. When a
dependency in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) moves past what a skill
states it was validated for, that is a conflict to raise rather than to assume away.

How to apply them: a narrower skill **refines** a broader one rather than replacing it. The
requirements of a specific task and this repository's rules take priority over the handbook. An
irreconcilable conflict must be raised explicitly rather than resolved silently —
[`docs/sync-server-design.md`](docs/sync-server-design.md) records one such departure and its
reasoning, which is the shape that argument is expected to take.

A convention cannot be inferred from a skill's name — a skill is read in full.

If the plugin is not available in the session, say so rather than proceeding on memory of what it
used to say: the rules below are the part this repository will not do without, and they are not the
whole of the standard.

What from the handbook applies constantly and is not up for discussion:

- full KDoc on every production declaration in every file touched;
- `kotlin.time`, `kotlin.uuid.Uuid`, `kotlinx.serialization` instead of Java types;
- closed sets of variants — a `sealed class` in one file, in full;
- value classes instead of interchangeable `String`/`Uuid`;
- `internal`/`private` by default, public only for the module's contract;
- no `!!`, `GlobalScope` or `runBlocking` in ordinary code.

## client-sdk

The mechanism specification: [`docs/sync-client-design.md`](docs/sync-client-design.md).
Read it before any change in `client-sdk/` and `contracts/sync-protocol/` — it records the
decisions that were made and why.

Key invariants that cannot be broken without rewriting the specification:

- the SDK **does not own business records**: entity bodies are materialised lazily through
  the application's adapter, and the library stores only sync metadata;
- the SDK **does not own files either**: bytes live in the application's own store and travel
  between the device and the host's storage directly, and what a document references is whatever
  the adapter says it does;
- the SDK's tables live in the same `RoomDatabase` as the application's tables — otherwise
  applying changes and advancing the cursor could not be done in one transaction;
- a collection's cursor advances only together with the application of a batch;
- the client clock takes part in no decision about ordering or versions;
- local transaction boundaries are preserved by merging groups that share entities.

When you change the mechanism, update the specification in the same change.

## server-sdk

The specification: [`docs/sync-server-design.md`](docs/sync-server-design.md).

The server side is an **embeddable library, not a service**. The host is responsible for
authorisation and transport; the module is responsible for ordering, versions, conflicts
and its own database schema.

Invariants that cannot be broken without rewriting the specification:

- a `ScopeId` arrives in the module **already authorised**; the module does not check access;
- the counter row's lock is taken in the same transaction that inserts the batch and is
  held to commit — the absence of gaps in the log rests on this;
- a cursor points at a batch's seq, not at an individual change;
- an entity's version equals the seq of the batch of its last change;
- the module stores documents as opaque `jsonb` and does not model business entities;
- the module never touches a file: it hands out permission to move bytes, is told what arrived, and
  hands released keys back to the host, which owns disposal;
- the history retention window and the cursor's lifetime are the same number;
- the commit listener is called after the transaction, the projection inside it;
- the module lives in its own `sync` schema with its own Flyway history;
- `SchemaUtils.create` and automatic schema alignment are never used;
- the `dev.voir.reflector.sync.server` package is the contract and mentions no Exposed, JDBC or
  PostgreSQL type; storage lives in `.postgres`, which may. Both packages are in one module, so
  nothing but review enforces this — the deliberate exception is `SyncModule.create`, which takes
  the host's Exposed `Database` so the module never picks up a global default.

Before changing anything in the `.postgres` package, invoking the `exposed-v1-5` skill is
mandatory: it fixes the naming of tables and constraints, the use of `kotlin.uuid`/`kotlin.time`,
the rules for transactions and the requirements for migrations.

## Build

```bash
./gradlew build            # build and test every module
./gradlew spotlessApply    # format (ktlint via Spotless)
./gradlew spotlessCheck    # verify formatting
```

Build configuration:

- versions only through `gradle/libs.versions.toml`, no string coordinates in modules;
- plugins are applied in modules through catalogue aliases (`alias(libs.plugins.kotlinJvm)`)
  and declared once in the root `build.gradle.kts` with `apply false`;
- settings shared by modules (toolchain, compiler options, Spotless, JUnit Platform) live in
  the `allprojects` block of the root `build.gradle.kts`, via `plugins.withId`; the repository
  has no separate included build with convention plugins;
- dependencies between modules go through typesafe accessors (`projects.clientSdk`);
- `implementation` by default; `api` only when a type is deliberately part of the contract
  for the consumer;
- publishing is configured once in the root `build.gradle.kts`: `publishedProjects` lists what is
  published and how each artifact describes itself, and a module absent from that map gets no
  `maven-publish` plugin at all. `./gradlew publishToMavenLocal` puts
  `dev.voir.reflector:client-sdk` and the rest into `~/.m2`;
- releases are published to GitHub Packages by the Publish Release workflow and by nothing else.
  [`ci/release/packages`](ci/release/packages) lists every artifact a release carries, and a
  release that builds an artifact it does not list is refused — so a target or a published module
  added to the build is added there in the same change.

The SDK and contract modules are built in strict explicit API mode (`explicitApi()` in the
module's `kotlin` block): public declarations require explicit visibility and an explicit
return type. The samples in `samples/` deliberately do not enable it.

## Repository conventions

- The whole repository is in English: code, KDoc, names, comments, commit messages,
  project documents and specifications.
- Branches and commits describe a change in behaviour, not files.
- The README is the public front page and stays short; detail belongs in `docs/`. A change to the
  public API updates the guides in `docs/` — and the README's quick start, when it is affected — in
  the same change.
