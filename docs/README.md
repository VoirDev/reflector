# Reflector documentation

New here? Start with the [README](../README.md) for what Reflector is, how to install it and a quick
start, then read [How it works](concepts.md) before integrating.

## Guides

| | |
|---|---|
| [How it works](concepts.md) | The problem, the model, and the reasoning behind every choice |
| [Server guide](server-guide.md) | Embedding the module in your backend: migrations, collections, routes, access control, events, projections, metrics, logs, erasure |
| [Client guide](client-guide.md) | Integrating the SDK into a KMP app: database, adapter, transport, engine, writes, state, conflicts, triggers, sign-out |
| [Files](files-guide.md) | Optional blob synchronisation, server and client |
| [Wire protocol](protocol.md) | Endpoints, status codes, and the rules a server must honour |
| [Testing](testing.md) | Testing an integration against a scripted transport and a real host |

## Specifications

The source of truth for the mechanism: the storage schemas, the state machines, and every decision
with its reasoning. The guides tell you what to do; these say exactly what happens.

| | |
|---|---|
| [Client specification](sync-client-design.md) | Push groups, push, pull, conflicts, bootstrap, transport and triggers, the public API, and the contract required from the server |
| [Server specification](sync-server-design.md) | The module's boundaries, schema, invariants, ordering, retention and purge, events, migrations, concurrency |
| [Files specification](sync-files-design.md) | Blobs, references, ordering, the file protocol, and who owns the bytes |

## Working on Reflector

[Development](development.md) — the repository layout, the build, CI and releases.
