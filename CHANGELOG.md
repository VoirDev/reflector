# Changelog

Every notable change to the published artifacts, in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. Versions follow
[semantic versioning](https://semver.org/); before 1.0 a minor version may break the API.

## [0.2.0] - Unreleased

### Added

- `426 Upgrade Required` is part of the protocol. On any request to the synchronisation server — the
  configuration, documents, file endpoints — the client stops its workers without backing off, keeps
  the queue and the local data, and stops listening to the event channel, which ends the socket's
  reconnect loop. The next trigger or start asks again, and the first answer the server accepts
  returns the scope to `Online`.
- `SyncTransportFailure.UpgradeRequired(message: String)`, raised by the bundled transports on `426`.
- `SyncFailure.UpgradeRequired(message: String)`, reported as a collection's `lastFailure`.
- `ScopeState.UpgradeRequired`. Like `AuthRequired`, it is not changed by the connection going or
  coming back.
- `syncHttpClient(engine, log, tracing, defaultHeaders: Map<String, String> = emptyMap())`. The
  headers reach every request the transports send to the synchronisation server, the event
  channel's handshake included, and never a presigned file transfer. A header the library sets
  itself, such as `Authorization`, is never replaced.

### Changed

- `syncHttpClient` has a new trailing parameter, which changes its binary signature: code compiled
  against 0.1.x has to be compiled again.

### Migrating

- An exhaustive `when` over `SyncFailure`, `ScopeState` or `SyncTransportFailure` needs a branch for
  the new `UpgradeRequired` case. For a status line, it is the "update the app to sync" message.
- A custom `SyncTransport` or `BlobTransport` throws `SyncTransportFailure.UpgradeRequired` when the
  server answers `426`; without it the engine reads the status as a transient server error and
  retries it with backoff.
- Stored data is unchanged: no Room schema or wire format changed.
