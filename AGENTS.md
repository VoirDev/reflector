# Instructions for agents and developers

The repository of a library that synchronises data between many clients and one server.
The client is Kotlin Multiplatform (Room, Ktor), the server is JVM. The mechanism is
implemented and covered by tests; what is known to be missing is collected in
[TODO.md](TODO.md).

## Repository layout

```text
README.md                    How the system works and which problems it solves
EXAMPLE.md                   Step-by-step integration of the server and the client
TODO.md                      Work that is known and not yet done
docs/
  sync-client-design.md      Client library specification
  sync-server-design.md      Server module specification
.claude/skills/handbook/     The handbook reading order as a Claude Code skill; it routes, not restates
handbooks/                   The engineering handbook: the Kotlin standard this repository holds to
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

`handbooks/` **is** the engineering handbook for this repository. There is no other copy to
consult and nothing to reconcile it against: what is in the directory is what applies, and a rule
that is not there is not a rule here.

The directory is the authority on its own contents. It is refreshed by replacing documents, and it
will grow, so read what is in it rather than what any list — including this one — says is in it.

For Claude Code the same reading order is packaged as the `handbook` skill in
[`.claude/skills/handbook/`](.claude/skills/handbook/SKILL.md), so that `exposed.md` is opened when
the Exposed package is touched rather than only when somebody remembers this section. The skill
routes and does not restate: the rules live in `handbooks/` and nowhere else.

Before any change to Kotlin code, read in this order:

1. `handbooks/kotlin.md` — always mandatory, for every module and source set.
2. The document covering the area being changed, if the directory has one. Today that is
   `handbooks/exposed.md` for the `sync.server.postgres` package of `server-sdk/`, which is
   mandatory before touching it, and `handbooks/optional-kmp.md` for multiplatform questions.
3. Where no document covers the area — the client SDK's application-level concerns are the
   current example — `kotlin.md` and this repository's own rules are the whole of the standard.
   That is a real answer, not a gap to be filled by guessing at a convention.

How to apply them: a narrower document **refines** a broader one rather than replacing it. The
requirements of a specific task and this repository's rules take priority over the handbook. An
irreconcilable conflict between documents must be raised explicitly rather than resolved silently.

A convention cannot be inferred from a file name — a document is read in full.

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
- the history retention window and the cursor's lifetime are the same number;
- the commit listener is called after the transaction, the projection inside it;
- the module lives in its own `sync` schema with its own Flyway history;
- `SchemaUtils.create` and automatic schema alignment are never used;
- the `dev.voir.reflector.sync.server` package is the contract and mentions no Exposed, JDBC or
  PostgreSQL type; storage lives in `.postgres`, which may. Both packages are in one module, so
  nothing but review enforces this — the deliberate exception is `SyncModule.create`, which takes
  the host's Exposed `Database` so the module never picks up a global default.

Before changing anything in the `.postgres` package, reading `handbooks/exposed.md` is
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
  `dev.voir.reflector:client-sdk` and the rest into `~/.m2`.

The SDK and contract modules are built in strict explicit API mode (`explicitApi()` in the
module's `kotlin` block): public declarations require explicit visibility and an explicit
return type. The samples in `samples/` deliberately do not enable it.

## Repository conventions

- The whole repository is in English: code, KDoc, names, comments, commit messages,
  project documents and specifications.
- Branches and commits describe a change in behaviour, not files.
