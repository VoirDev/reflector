# Wire protocol

What travels between the client SDK and your host. You need this page if you write the host's routes
yourself (everybody does — see the [server guide](server-guide.md#publishing-the-endpoints)), or if
you replace the bundled Ktor transport with your own `SyncTransport`.

The DTOs, identifiers and serializers are in `dev.voir.reflector:sync-protocol`, which both SDKs
depend on. Serialise with `SyncProtocolJson.format` on both sides.

## Endpoints

```text
GET  /v1/sync/config                               → limits and retention window
POST /v1/sync/{scope}/{collection}/push            → {applied | conflict | rejected} per group
GET  /v1/sync/{scope}/{collection}/changes?cursor= → batches, nextCursor, hasMore, epoch
GET  /v1/sync/{scope}/{collection}/snapshot?page=  → cursor, items, nextPage, hasMore, epoch
WS   /v1/sync/{scope}/events                       → invalidate | resync | revoked | blobReady

POST /v1/sync/{scope}/{collection}/blobs                   → register, and an upload ticket
POST /v1/sync/{scope}/{collection}/blobs/{id}/complete     → verify the bytes and accept them
GET  /v1/sync/{scope}/{collection}/blobs/{id}              → a download ticket
```

`changes` and `snapshot` also take `limit`. Every request carries `Authorization: Bearer <token>`,
the token coming from the application's `TokenProvider`; the socket is authorised the same way.

The three file endpoints are served only by a deployment that configures storage. One that does not
publishes no file limits, and a client configured for files learns that at start-up rather than on a
user's first attachment.

## Status codes

The codes are the client's recovery instructions, so the host must keep them distinguishable:

| Code | Meaning | What the client does |
|---|---|---|
| `401` | No valid credentials | Refreshes the token once; failing that, the scope becomes `AuthRequired` with data and queue intact |
| `403` | The scope was revoked | Wipes the scope's data |
| `409` | The collection was purged and began again | Discards the collection, unsent changes included, and rebuilds from a snapshot |
| `410` | The cursor is older than the retention window | Bootstraps from a snapshot, keeping unsent changes |
| `429` | Slow down, with `Retry-After` | Waits as told |

Collapsing any two of them costs the client one of its recoveries — collapsing `409` and `410`
either loses a user's offline edits after an ordinary retention gap, or puts an erased collection
back after a purge.

## Rules the server must honour

The full list is §11 of the [client specification](sync-client-design.md). The ones that bite
hardest:

- `upsert` is a **patch-merge by present top-level keys**: a missing field is left alone, an
  explicit `null` clears it, and nested objects are replaced whole. The client's serializer must
  therefore write nulls out (`explicitNulls = true`, which `SyncProtocolJson` sets).
- A push is **idempotent by `groupId`**: re-sending returns the stored result instead of applying
  the group twice.
- A group is applied **atomically**; groups in one request are independent.
- Cursors and versions are **opaque strings**. The client only stores a cursor and hands it back,
  and compares versions only for equality.
- The change log has **no gaps and no visibility holes**: a batch committed later can never get a
  sequence number below one already served.
- Entity identifiers are the **application's** to generate, and the server stores them as it
  receives them. UUIDv7 is what the design assumes, because a time-ordered key keeps the server's
  indexes local; nothing enforces it.
- The set of operation codes (`upsert`, `delete`) is fixed. A client that meets an unknown code
  resyncs the collection, so adding one is a breaking protocol change.

The server module implements all of these; they matter to you only if you put something of your own
between the module and the client.
