# Files

Synchronising the files your documents point at — photographs, receipts, voice messages. Files are
**opt-in on both sides**: a deployment that does not configure them serves none, says so in its
published limits, and nothing on this page applies. An application that synchronises no files
changes nothing.

The model in brief (the reasoning is in [How it works](concepts.md#files-travel-beside-the-log-never-through-it),
the mechanism in the [files specification](sync-files-design.md)):

- A file is a **blob**: an immutable sequence of bytes with a client-generated `BlobId`. A document
  references it by an ordinary field of its own, and your adapter says which fields those are.
- **Neither SDK is in the data path.** The server hands out presigned tickets and the bytes travel
  between the device and *your* storage directly.
- **A record may be published before its file** (`BlobBinding.DEFERRED`, the default), and **a
  device need not hold every file it knows about** (`BlobFetch.ON_DEMAND`).
- **Deleting is the host's.** The module tells you which files nothing points at any more; what
  disposal means is your policy.

**Contents:** [Server](#server) · [Client](#client) · [The binding](#the-binding-which-is-the-one-editorial-decision) ·
[The fetch policy](#the-fetch-policy-which-is-the-other-one) · [What your screens have to render](#what-your-screens-have-to-render) ·
[Checklist](#checklist)

## Server

The module never touches a byte. It hands out permission to move them, is told afterwards what
arrived, and keeps the metadata that says which files exist and whether anything still points at
them. The bytes travel between the device and **your** storage, directly:

```kotlin
class S3BlobStorage(private val s3: S3Presigner, private val bucket: String) : BlobStorage {
    // Called once per file, before it is registered. Must be cheap and local: the module asks
    // before it knows whether it will need the answer. Put deduplication here if you want it.
    override fun keyFor(scope: ScopeId, collection: CollectionId, blob: BlobDescriptor) =
        BlobStorageKey("${'$'}{scope.value}/${'$'}{collection.value}/${'$'}{blob.blobId.value}")

    override fun createUpload(scope, collection, blob) = s3.presignPut(bucket, blob.storageKey) // …
    override fun createDownload(scope, collection, blob) = s3.presignGet(bucket, blob.storageKey)

    // A HEAD. You observe; the module compares the answer with what the client declared and
    // decides. Reporting no checksum is fine and never fails a verification on its own.
    override fun verify(scope, collection, blob) = s3.head(bucket, blob.storageKey) // …

    // Not an instruction. The module has already dropped its rows: nothing points at these any
    // more, here are their keys, they are yours. Delete now, tag them for a lifecycle rule, or keep
    // them for a retention period somebody legislated — the module has no opinion and asks nothing
    // back. Whatever you do, record it: that record is the erasure's evidence, because the module
    // keeps none.
    override fun onReleased(scope, collection, blobs) = blobs.forEach { s3.delete(bucket, it.storageKey) }
}
```

Wire it in, and publish the three endpoints over `module.blobs`:

```kotlin
SyncModule.create(
    database = database,
    config = syncConfig(collections = setOf(ledger), blobs = BlobConfig(maxBlobBytes = 25.mb)),
    blobStorage = S3BlobStorage(presigner, bucket),
    blobListeners = listOf(BlobListener { scope, collection, blob -> thumbnails.submit(blob) }),
)
```

`config.blobs` and `blobStorage` go together or not at all; either half alone is refused at
assembly, because either half alone surfaces as a failure on a user's first attachment rather than
on the line that was wrong.

**`BlobListener` is the acknowledgement you asked for.** It fires once per file, after the commit
that made it usable, and it is where thumbnailing, scanning and extraction belong. It is reached
however the file was confirmed — the device said so, your storage's own event notification said so,
or the maintenance sweep found an upload nobody ever confirmed. Two rules: never overwrite the
object you are given, because a file is immutable and every device that already fetched it holds
bytes that would no longer match; and if the result has to reach the clients, register the
derivative as a **new** file and name it in a document through an ordinary `SyncService.push` of
your own.

Three endpoints, and they carry your credentials like every other route:

```
POST /v1/sync/{scope}/{collection}/blobs                   → module.blobs.register(…)
POST /v1/sync/{scope}/{collection}/blobs/{id}/complete     → module.blobs.markUploaded(…)
GET  /v1/sync/{scope}/{collection}/blobs/{id}              → module.blobs.download(…)
```

`markUploaded` is also what your storage's event notification should call — a device can die between
writing the object and reporting it, and then the bytes sit there unfetchable forever. Publish
`blobReady` on the scope's socket after it, or the other devices discover the file whenever their
own backoff next fires.

Two more things on your schedule, beside `trim()`:

```kotlin
module.maintenance.confirmPendingUploads()          // uploads nobody confirmed
module.maintenance.collectBlobs(dryRun = true)      // read this before switching the next line on
module.maintenance.collectBlobs()                   // files nothing has pointed at for the window
```

Run the dry run first on any collection whose clients are not all known to declare their references:
a client older than files says nothing and is respected, but an application that *has* files and
forgets to answer for one entity type says "references nothing", and from the server the two are
identical.

## Client

Three things: say where the bytes live, say which files a document points at, and hand the engine a
transport for them.

```kotlin
class LedgerFiles(private val directory: Path) : BlobStore {
    override suspend fun stat(blobId: BlobId): BlobStat? = // size, media type, checksum if you have one
    override suspend fun read(blobId: BlobId): RawSource = // for an upload
    override suspend fun write(blobId: BlobId, stat: BlobStat): RawSink = // somewhere temporary
    override suspend fun finish(blobId: BlobId, complete: Boolean) { /* move it into place, or discard */ }
    override suspend fun remove(blobId: BlobId) { /* nothing points at it any more — your call */ }
    override suspend fun onFailed(blobId: BlobId, failure: BlobFailure) { /* it will not arrive */ }
}
```

`write` hands back a sink and `finish` says whether to keep it. Write somewhere temporary and move
the file into place only when `finish` says the bytes arrived in full — otherwise an interrupted
download leaves a truncated photograph exactly where your own code will find it and believe it.

`remove` is an offer, not an instruction: the library will not delete a user's bytes on a judgement
of its own, and keeping the file for an undo stack is a correct implementation that does nothing
here. Sign-out, a revoked scope and a purged collection are the exceptions — there it is called for
everything, and it is not eviction.

Then say what a document points at:

```kotlin
override fun blobs(entityType: EntityType, id: EntityId, document: JsonObject): Set<BlobRef> =
    when (entityType) {
        WALLET -> decode(WalletDocument.serializer(), document).photoBlobId
            ?.let { setOf(BlobRef(BlobId(it))) }.orEmpty()
        else -> emptySet()
    }
```

The whole set, every time: the answer **replaces** what was stored, so a document that still names a
file must still name it here, or the file is offered up for deletion while it is in use.

```kotlin
ledgerSync(
    database = database,
    transport = KtorSyncTransport(client, host, tokens),
    blobStore = LedgerFiles(filesDirectory),
    blobTransport = KtorBlobTransport(client, host, tokens),
    // EAGER is the default: every file a document names is brought to this device. See below.
    blobFetch = BlobFetch.EAGER,
    coroutineScope = scope,
)
```

Both or neither — the engine refuses to be built with one and not the other, because a store with
no transport leaves every record naming a file waiting for a registration nothing can perform.

**Write the bytes before the `mutate` that names them.** A document may not point at a file whose
bytes are not there yet:

```kotlin
files.put(photoId, bytes)
collection.mutate {
    dao.upsertWallet(Wallet(id, "Holiday", "EUR", photoId.value))
    markUpserted(WALLET, EntityId(id))
}
```

### The binding, which is the one editorial decision

`BlobRef(id)` defaults to `BlobBinding.DEFERRED`: the record goes out as soon as the file has been
**registered** — a request and an answer, not a transfer — and the bytes follow on their own. Use it
for a receipt, an avatar, a photograph of a wallet: anything the record survives without. A
transaction must not wait on a photograph stuck behind a hotel's captive portal.

`BlobBinding.REQUIRED` holds the record until the bytes are on the server. Use it only when the
record without its file would mislead — a photo post, a voice message — and know what it costs: the
group waits, and the queue is FIFO, so everything behind it waits too.

### The fetch policy, which is the other one

The binding is about the device that has the file. The policy is about every device that receives
the record, and the question is the same one turned around: is this file worth holding before
anybody asks for it?

`BlobFetch.EAGER` is the default and is right for anything a list draws — a thumbnail, an avatar, a
photograph of a wallet. `BlobFetch.ON_DEMAND` records the reference, fetches nothing, and waits:

```kotlin
// One switch for the whole application…
ledgerSync(…, blobFetch = BlobFetch.ON_DEMAND)

// …and an override per reference, where the choice actually differs.
override fun blobs(entityType: EntityType, id: EntityId, document: JsonObject): Set<BlobRef> =
    when (entityType) {
        SCAN -> {
            val scan = decode(ScanDocument.serializer(), document)
            setOfNotNull(
                // small, drawn in every list: hold it
                scan.thumbnailBlobId?.let { BlobRef(BlobId(it), fetch = BlobFetch.EAGER) },
                // forty megabytes, opened by one screen: fetch it there
                scan.originalBlobId?.let { BlobRef(BlobId(it), fetch = BlobFetch.ON_DEMAND) },
            )
        }
        else -> emptySet()
    }
```

Leave `fetch` unset and the engine's policy applies, which is how you answer once for almost every
file. Where two documents name the same file and disagree, eager wins.

Then the screen that opens the record asks for it, and gives it up when it wants the space back:

```kotlin
suspend fun onScanOpened(original: BlobId) = collection.fetch(original)   // idempotent
suspend fun onCacheCleared(original: BlobId) = collection.evict(original) // bytes go, file stays
```

`fetch` records the wish and returns — the transfer is the worker's, and you watch it through
`collection.blob(id)`. The wish is durable, so a download interrupted by the process being killed
resumes rather than waiting to be asked again, and what arrives stays until you `evict` it: the
second time that record is opened, the file is simply there. Calling `fetch` on a file the library
gave up on starts it again from zero attempts, which is what you put behind a retry button.

Two things to know before you choose it:

- **A file you have not fetched has never been described.** Its size and media type come from the
  download ticket, and there has not been one — so `BlobSyncState.size` is `null`. If a screen wants
  to say *2.4 MB* before offering the download, keep that in your own document, where you put it
  when you attached the file.
- **`evict` refuses a file whose bytes are the only copy.** A file this device created and has not
  finished uploading is not a cache, and the library will not discard it.

### What your screens have to render

```kotlin
collection.blob(photoId).collect { state ->
    when {
        state == null -> showNothing()                        // no document names it yet
        state.state == READY -> showPhoto()
        state.state == UNAVAILABLE -> showBroken()            // onFailed has already told you why
        state.wanted -> showProgress(state.transferred, state.size)   // REMOTE or DOWNLOADING
        else -> showDownloadOffer()                           // REMOTE, and nobody asked
    }
}
```

`REMOTE` and `DOWNLOADING` are what an attachment looks like on a device whose record arrived ahead
of its bytes — which is what the default binding produces on purpose. A screen that treats either as
a failure will report photographs lost every time somebody goes into a tunnel.

`wanted` is what tells a wait apart from an offer: `REMOTE` with `wanted = false` is a file this
device has decided not to hold, and the only thing that will change that is your call to `fetch`. If
you never use `ON_DEMAND`, it is always `true` and you can ignore it.

`CollectionSyncState` counts files apart from records (`pendingBlobs`, `incomingBlobs`), because a
record that has not reached the server is work that could be lost and a photograph that has not is a
slow upload. `incomingBlobs` counts what this device wants and has not got yet, so a file left
behind under `ON_DEMAND` is not reported as an arrival nothing is waiting for.

`BlobStore.onFailed` is the only notice you get that a file will never arrive, and
under the default binding the record naming it is already on every device — so show a broken
attachment and offer to retry or detach it. The library will not drop the reference for you: that is
your document, and a receipt worth asking a user about should not vanish silently.

## Checklist

Server:

1. `BlobStorage` implemented, and `config.blobs` set — both or neither.
2. The three endpoints published over `module.blobs`.
3. `markUploaded` reachable from your storage's event notifications, and `blobReady` published on the
   scope's socket after it.
4. `confirmPendingUploads` and `collectBlobs` on your schedule — the dry run read first.

Client:

1. A `BlobStore` and a `BlobTransport` passed to the engine — both or neither.
2. `blobs()` answered for every entity type that can name a file, with the whole set every time.
3. Bytes written before the `mutate` that names them.
4. `REMOTE` / `DOWNLOADING` rendered as ordinary states rather than as errors.
5. If your files are large enough that holding them all is a cost: `blobFetch = ON_DEMAND`, a
   `fetch(blobId)` from the screen that opens the record, `REMOTE` with `wanted = false` drawn as an
   offer rather than a wait, and each file's size kept in your own document — a file nobody has
   fetched has never been described.
