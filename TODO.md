# Open work

What is known to be missing, with the options considered and where each one leans. Grouped by what
it costs to leave alone rather than by how much work it is.

The specifications in [`docs/`](docs/) are the source of truth for the mechanism. This file
holds work that is understood and not yet done; when an item is finished it leaves the file, and
whatever reasoning is worth keeping moves into the specification it belongs to.

**Suggested order:** 3 → 4 → 1 → 2. The first is half an hour, and continuous integration now
runs the test it is about on every pull request, so until it is done the first thing CI teaches
everybody is to re-run a red build. The second is the other half of the platform integration, where
Room on the main thread is the part nothing has tried. The third is the search that would look for
what the scripted tests do not think to ask. The last matters only once somebody outside the
organisation needs the library.

---

## 1. No property test for convergence under arbitrary interleaving

A generator of push/pull/crash sequences, asserting that every client ends at the same state.

**What it already has.** A deterministic clock and a deterministic transport (`TestDoubles` in
`client-sdk`), a way to cut the process at an arbitrary point — the adapter's `beforeApply` hook,
which throws inside the transaction that applies a batch — and two whole clients that converge
against a real host, which `TwoClientConvergenceTest` drives by hand. All of it was built for the
scripted tests and is exactly what a generator would drive.

**What is missing.** The generator, and a decision about what it asserts: every client ending at the
same state is the easy half, and saying so without either enumerating states or storing them all is
the part that needs thought.

**Leaning.** After the scripted two-client test, which is the case this would search around: a
property test that fails with no deterministic case to compare it against is a report nobody can act
on.

## 2. Published to GitHub Packages only

Releases go to GitHub Packages, which asks every consumer for a token even to read — fine while the
library is used inside the organisation and the API is still moving, and a wall for anybody else.

**What Maven Central needs first.** A licence file and a `licenses` block, and a `developers` block —
Central rejects a POM without them, and the repository has no licence. Signing keys and a Javadoc jar
belong to the same step. None of it is invented on the way past: a POM is the wrong place to guess
at a licence. The `scm` block and the URL are already there.

## 3. The client's flakiest test is a wall-clock race

`SyncEngineTest > refused credentials are reported as needing the user` waits ten seconds of real
time for a published state, and under a full parallel build it has twice reached that ceiling —
once as a timeout and once with the state having moved past what it asserted. It passes on its own
every time, and it passed before the file work as well, so it is the test rather than the engine.

**What it should wait for instead.** The engine already reports every decision it takes through
`SyncLog`, and `SyncEngineTest` already has a `RecordingSyncLog` and an `awaitRecord` helper built
on it. A test that waits for the record the engine emits rather than for a state to settle within a
deadline is waiting for the thing it is about.

**Why it matters more now than it did.** `Verify` runs this test on every pull request, so a flake
is no longer an eyebrow on a developer's machine but a build that fails for nobody's reason on
somebody else's commit.

## 4. No iOS sample application

`samples/ledger/android` covers the Android half — the database file, the OkHttp engine, the three
platform triggers and enough user interface to see the collection's state — and was run on an
emulator against `samples/ledger-server` on a real PostgreSQL rather than merely compiled.

The iOS application is the other half, and the interesting parts there are the ones nothing has
exercised: Room's behaviour on the main thread, and the foreground and background notifications as
they arrive from `NotificationCenter` and `BGAppRefreshTask`.

Files need nothing platform-specific for it — `LedgerFiles` is written against `kotlinx-io`'s
`SystemFileSystem` and already compiles for both Apple targets — but a photograph picked from the
camera roll is exactly the case where a device's own storage rules bite, and nothing has tried that
either.

## 5. detekt is not enabled

The stable 1.23.x branch is compiled against Kotlin 2.0.21; the 2.0 branch (group `dev.detekt`) is
built against 2.4.10 but still in alpha. The version is already in the catalogue.

**Leaning.** Switch it on when 2.0 goes stable, in one change, with the initial baseline empty
rather than generated.
