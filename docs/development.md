# Development

Working on Reflector itself: how the repository is laid out, how it is built and verified, and how a
release goes out. Repository rules — code style, the handbook skills to read before changing code,
the invariants that cannot be broken — are in [AGENTS.md](../AGENTS.md). Known open work is in
[TODO.md](../TODO.md).

## Modules

```text
contracts/sync-protocol      Wire DTOs, identifiers, serializers — the shared vocabulary
client-sdk                   The client library, published as one artifact:
  …/sync/core                Public API and ports (adapter, transport, tokens, triggers)
  …/sync/persistence         The library's Room tables, DAOs and typed stores
  …/sync/engine              Mutations, push, pull, bootstrap, conflicts, workers
  …/sync/network             Ktor transport and event channel
server-sdk                   The server module, published as one artifact:
  …/sync/server              Server module API and its ports
  …/sync/server/postgres     Exposed + PostgreSQL implementation, Flyway migrations
samples/ledger/shared        A consumer application: its own tables, adapter, wiring
samples/ledger/android       A thin Android application: database, transport and platform triggers
samples/ledger-server        A reference host: routes, scope authorisation, events
```

The client is one artifact rather than four. An application that synchronises needs the engine, the
tables and a transport together — and the library's entities are declared in *your* `@Database`, so
a module boundary in front of them hid nothing while costing three dependency lines. The division
survives as packages. `SyncTransport` is still the extension point: implement it over your own stack
and the bundled Ktor client goes unused.

## Build

```bash
./gradlew build              # compile and test
./gradlew spotlessApply      # format
./gradlew spotlessCheck      # verify formatting
./gradlew publishToMavenLocal # publish the artifacts to ~/.m2 for a local consumer
ci/verify                    # what a pull request must pass: the whole build
ci/verify apple              # the Apple targets alone, on macOS
```

The published coordinates are `dev.voir.reflector:client-sdk` and
`dev.voir.reflector:server-sdk`, with `dev.voir.reflector:sync-protocol` arriving transitively
with either. Releases go to GitHub Packages — how to depend on them is in the
[README](../README.md#installation).

The client is built and tested on JVM, Android and both iOS targets. Tests in `server-sdk` and
`samples/ledger-server` start PostgreSQL through Testcontainers and need a working Docker;
everything else runs without external dependencies.

## Continuous integration and releases

Three workflows under [`.github/workflows`](../.github/workflows), following the handbook's
`github-verify-workflow` and `github-release-workflow`, with the logic in [`ci/`](../ci) so that it
runs the same on a laptop:

- **Verify** runs on every pull request into `main`: `ci/verify` on Linux, which has Docker for the
  server tests, and `ci/verify apple` on macOS, side by side. `Build and check whole repository`
  gates both and is the check to require. On a release pull request, `Build release artifacts` then
  builds every package at the proposed version on macOS, without publishing it.
- **Dependabot** opens weekly update pull requests for the Gradle build and the actions, grouped so
  that dependencies which only work together — Kotlin, KSP and AGP among them — move together.
- **Prepare Release**, run by hand from `main`, bumps `VERSION` (or takes an exact version) and opens
  `Release vX.Y.Z` from `release/vX.Y.Z`.
- **Publish Release** runs when that pull request is merged: it verifies the merged commit unless the
  pull request's checks already covered its exact tree, waits for the `release` environment, builds
  and publishes every package in [`ci/release/packages`](../ci/release/packages) from the merged commit,
  then tags `vX.Y.Z` and writes the GitHub Release. Every step is safe to rerun.

What the repository needs before the first release: the secret `RELEASE_BOT_TOKEN` (a token that may
push branches and open pull requests — one made with the workflow's own token would start no
checks) and the variables `RELEASE_BOT_NAME` and `RELEASE_BOT_EMAIL`; an environment named `release`,
with required reviewers if publication should wait for a person; and a ruleset on `main` requiring
`Verify / Build and check whole repository` and `Verify / Build release artifacts`. The first release
is prepared with `version` set to the `0.1.0` that `VERSION` already holds.

A `[skip verify]` in a pull request's body skips its checks. On a release, publication then verifies
the merged commit itself before anything goes out.

## Building from a network-isolated environment

The environments the code is written in cannot reach Maven Central, so Gradle runs on the
developer's machine through a worker:

```bash
bash build/watch.sh          # once, in its own terminal
echo "build" > build/request # queue a task
cat build/status             # exit=0 means done
less build/last-build.log    # the whole output
```

The worker kills a build that exceeds `DEADLINE_SECONDS` (600 by default) so a hung test cannot
block the queue.

## Versions

`VERSION` holds the repository version (currently `0.1.0`) and every module takes it. Coordinates
are derived from the path — `dev.voir.reflector.client`, `dev.voir.reflector.server`,
`dev.voir.reflector` for the contracts — because identically named modules on both sides otherwise
collapse into one artifact during resolution.

Kotlin 2.4.10 / Gradle 9.7.1 / AGP 9.4.0 is a point inside the Kotlin Gradle Plugin's official
compatibility matrix, even though newer Gradle and AGP releases exist.

Static analysis is limited to formatting: detekt's stable 1.23.x is built against Kotlin 2.0.21, and
the 2.0 branch (group `dev.detekt`, built against 2.4.10) is still in alpha. The version is in the
catalogue, ready to be switched on — [TODO.md](../TODO.md) §5.
