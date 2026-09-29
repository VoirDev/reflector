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
- **Publish Release** runs when that pull request is merged, in two jobs one after the other: it
  waits for the `release` environment, builds every package in
  [`ci/release/packages`](../ci/release/packages) from the merged commit and publishes it, then tags
  `vX.Y.Z` and writes the GitHub Release. It does not run the tests again — the release pull request's
  checks are the verification. The tag comes last because it marks a release complete, so rerunning
  a failed publication carries on where it stopped and never overwrites a published package.

  Not verifying the merged commit again departs from the handbook's `github-release-workflow`, on
  purpose: a release is one line from pull request to packages, with no job deciding whether another
  must run. What makes it safe is the ruleset on `main` requiring branches to be up to date before
  merging, so the merged commit is the one the pull request's checks tested.

What the repository needs before the first release: the secret `RELEASE_BOT_TOKEN` (a token that may
push branches and open pull requests — one made with the workflow's own token would start no
checks) and the variables `RELEASE_BOT_NAME` and `RELEASE_BOT_EMAIL`; an environment named `release`,
with required reviewers if publication should wait for a person; and a ruleset on `main` requiring
`Verify / Build and check whole repository` and `Verify / Build release artifacts`, with branches up
to date before merging. The first release
is prepared with `version` set to the version `VERSION` already holds.

A `[skip verify]` in a pull request's body skips its checks. On a release pull request that skips
`Build release artifacts` too, and nothing tests the release before it is published — so it is for a
release whose content was verified elsewhere, not a shortcut.

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

`VERSION` holds the repository version (currently `0.1.1`) and every module takes it. Coordinates
are derived from the path — `dev.voir.reflector.client`, `dev.voir.reflector.server`,
`dev.voir.reflector` for the contracts — because identically named modules on both sides otherwise
collapse into one artifact during resolution.

The build runs Kotlin 2.4.20 with Gradle 9.7.1 and AGP 9.4.1. Both are slightly past what the
Kotlin Gradle plugin's official compatibility table lists for 2.4.20 (Gradle up to 9.7.0, AGP up to
9.3.1, as of September 2026). The Kotlin documentation allows newer Gradle and AGP releases but
warns of deprecation warnings and features that may not work, so a Gradle or AGP warning is worth
checking against that table before anything else.

The build runs no formatter or static analysis. Spotless with ktlint was removed because it was
not working, and detekt's stable 1.23.x is built against Kotlin 2.0.21 while the 2.0 branch (group
`dev.detekt`) is still in alpha — [TODO.md](../TODO.md) §5.
