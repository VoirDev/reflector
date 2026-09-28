# Testing your integration

Two levels, both used in this repository, and both worth having in yours: one that exercises your
adapter and your tables against a server that says exactly what the case needs, and one that proves
your client and your host speak the same language.

## A scripted transport

Implement `SyncTransport` with fields you set per test. This is how
[`LedgerSyncTest`](../samples/ledger/shared/src/jvmTest/kotlin/dev/voir/reflector/sample/ledger/LedgerSyncTest.kt)
drives a real Room database and the real engine against a server that answers exactly what the case
needs:

```kotlin
val collection = ledgerSync(database, server, workers).scope(scopeId).collection(LEDGER)

collection.mutate {
    database.ledgerDao().upsertWallet(Wallet(walletId.value, "Cash", "EUR"))
    markUpserted(LedgerAdapter.WALLET, walletId)
}
collection.state.awaitQueueDrained()
```

Use `runBlocking` with an explicit timeout rather than `runTest`: the engine runs on real
dispatchers, and `runTest`'s virtual clock does not advance them.

This is the level for your adapter's decisions: that a remote upsert lands in the right row, that a
conflict on a given entity type resolves the way you meant, that a rejection reaches the user.

## The real host

[`samples/ledger-server`](../samples/ledger-server) boots the module against a Testcontainers
PostgreSQL and drives it with the actual `KtorSyncTransport` —
[`TwoClientConvergenceTest`](../samples/ledger-server/src/test/kotlin/dev/voir/reflector/sample/ledger/server/TwoClientConvergenceTest.kt)
runs two whole clients against it until they agree. That is the only way to find out whether client
and server speak one language rather than two similar ones: a serialization mismatch is invisible to
tests that mock either side.

These tests need a running Docker daemon.
