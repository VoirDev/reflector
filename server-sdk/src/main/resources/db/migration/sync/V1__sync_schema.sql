-- Schema of the synchronisation module.
--
-- The module owns this schema and its migration history: they live apart from the host's own
-- schema so that a version of the library can never collide with a version of the application.

CREATE SCHEMA IF NOT EXISTS sync;

-- One row per collection. `next_seq` is the counter that orders everything inside it; it is locked
-- for the whole transaction that inserts a batch, which is what makes the log gapless.
CREATE TABLE sync.collections (
    id                  uuid         NOT NULL,
    scope_id            varchar(200) NOT NULL,
    collection_id       varchar(100) NOT NULL,
    next_seq            bigint       NOT NULL,
    retention_floor_seq bigint       NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_collections PRIMARY KEY (id),
    CONSTRAINT uq_collections__scope_id__collection_id UNIQUE (scope_id, collection_id)
);

-- One row per committed server transaction. The cursor points at a batch, never inside one, so a
-- page of the log can only ever be cut on a transaction boundary.
CREATE TABLE sync.batches (
    id               uuid        NOT NULL,
    collection_id    uuid        NOT NULL,
    seq              bigint      NOT NULL,
    origin_client_id uuid        NULL,
    client_group_id  uuid        NULL,
    committed_at     timestamptz NOT NULL,
    CONSTRAINT pk_batches PRIMARY KEY (id),
    CONSTRAINT fk_batches__collection_id__collections FOREIGN KEY (collection_id)
        REFERENCES sync.collections (id) ON DELETE CASCADE,
    CONSTRAINT uq_batches__collection_id__seq UNIQUE (collection_id, seq)
);

-- Changes of a batch, ordered by `ordinal`. `data` holds the state as of that batch rather than the
-- current one: that is what makes reading the log incremental and resumable.
CREATE TABLE sync.changes (
    id          uuid         NOT NULL,
    batch_id    uuid         NOT NULL,
    ordinal     int          NOT NULL,
    entity_type varchar(100) NOT NULL,
    entity_id   uuid         NOT NULL,
    op          varchar(10)  NOT NULL,
    version     bigint       NOT NULL,
    data        jsonb        NULL,
    CONSTRAINT pk_changes PRIMARY KEY (id),
    CONSTRAINT fk_changes__batch_id__batches FOREIGN KEY (batch_id)
        REFERENCES sync.batches (id) ON DELETE CASCADE,
    CONSTRAINT uq_changes__batch_id__ordinal UNIQUE (batch_id, ordinal)
);

-- Current state of every entity. `version` is the sequence of the batch that last changed it: a
-- separate version counter would carry no more information, because an entity cannot change twice
-- within one batch.
CREATE TABLE sync.entities (
    id            uuid         NOT NULL,
    collection_id uuid         NOT NULL,
    entity_type   varchar(100) NOT NULL,
    entity_id     uuid         NOT NULL,
    version       bigint       NOT NULL,
    data          jsonb        NULL,
    is_deleted    boolean      NOT NULL,
    last_seq      bigint       NOT NULL,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT pk_entities PRIMARY KEY (id),
    CONSTRAINT fk_entities__collection_id__collections FOREIGN KEY (collection_id)
        REFERENCES sync.collections (id) ON DELETE CASCADE,
    CONSTRAINT uq_entities__collection_id__entity_type__entity_id
        UNIQUE (collection_id, entity_type, entity_id)
);

-- Retention removes tombstones by their last sequence; the partial index keeps that sweep from
-- scanning the live entities, which are the overwhelming majority.
CREATE INDEX ix_entities__collection_id__last_seq__deleted
    ON sync.entities (collection_id, last_seq) WHERE is_deleted;

-- Stored outcome of every push group. Idempotency depends on it: a client that lost the answer
-- re-sends the same group, and it must get the same answer instead of applying the work twice.
CREATE TABLE sync.push_results (
    id            uuid        NOT NULL,
    collection_id uuid        NOT NULL,
    client_id     uuid        NOT NULL,
    group_id      uuid        NOT NULL,
    status        varchar(20) NOT NULL,
    response      jsonb       NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT pk_push_results PRIMARY KEY (id),
    CONSTRAINT fk_push_results__collection_id__collections FOREIGN KEY (collection_id)
        REFERENCES sync.collections (id) ON DELETE CASCADE,
    CONSTRAINT uq_push_results__client_id__group_id UNIQUE (client_id, group_id)
);

-- Purging reads these rows by collection, and the unique constraint above cannot serve it: that one
-- leads on `client_id`, because idempotency looks a group up by the client that sent it. Without an
-- index leading on `collection_id`, both the purge's own delete and the cascade from
-- `sync.collections` scan the whole table once per collection.
CREATE INDEX ix_push_results__collection_id ON sync.push_results (collection_id);

-- Files.
--
-- The module orchestrates blobs without ever seeing one: the bytes travel between the device and
-- the host's storage directly, and what is kept here is the metadata that says a blob exists, what
-- was declared about it, whether it can be served yet, and whether anything still points at it.
-- `storage_key` is whatever the host called the object; nothing here interprets it.
CREATE TABLE sync.blobs (
    id                 uuid          NOT NULL,
    collection_id      uuid          NOT NULL,
    blob_id            uuid          NOT NULL,
    state              varchar(20)   NOT NULL,
    storage_key        varchar(1024) NOT NULL,
    content_type       varchar(255)  NOT NULL,
    size               bigint        NOT NULL,
    checksum           varchar(200)  NULL,
    created_at         timestamptz   NOT NULL,
    ready_at           timestamptz   NULL,
    unreferenced_since timestamptz   NULL,
    CONSTRAINT pk_blobs PRIMARY KEY (id),
    CONSTRAINT fk_blobs__collection_id__collections FOREIGN KEY (collection_id)
        REFERENCES sync.collections (id) ON DELETE CASCADE,
    CONSTRAINT uq_blobs__collection_id__blob_id UNIQUE (collection_id, blob_id),
    -- A blob becomes usable exactly once, and the moment it did is part of that fact. Without this
    -- the two halves can drift, and the sweep that promotes stale uploads reads both.
    CONSTRAINT ck_blobs__ready_at_matches_state CHECK ((state = 'READY') = (ready_at IS NOT NULL)),
    CONSTRAINT ck_blobs__size_is_not_negative CHECK (size >= 0)
);

-- The collector reads exactly this: within one collection, the blobs nothing has pointed at since
-- long enough ago. A partial index would be tempting, but `unreferenced_since` is set and cleared
-- as references come and go, so most rows are null only while they are in use.
CREATE INDEX ix_blobs__collection_id__unreferenced_since
    ON sync.blobs (collection_id, unreferenced_since);

-- What each entity's document points at, replaced whole every time that document is written. The
-- set is the client's declaration rather than anything the module derives: it does not read
-- documents, and the reference is a fact only the application's own schema knows.
CREATE TABLE sync.blob_refs (
    collection_id uuid         NOT NULL,
    entity_type   varchar(100) NOT NULL,
    entity_id     uuid         NOT NULL,
    blob_id       uuid         NOT NULL,
    CONSTRAINT pk_blob_refs PRIMARY KEY (collection_id, entity_type, entity_id, blob_id),
    -- A reference genuinely cannot exist without its blob, which is the one case for CASCADE. It
    -- is not what stops a blob in use from being deleted — `unreferenced_since` is — and it also
    -- keeps a purge simple, since the cascade from `sync.collections` reaches both tables.
    CONSTRAINT fk_blob_refs__collection_id__blob_id__blobs FOREIGN KEY (collection_id, blob_id)
        REFERENCES sync.blobs (collection_id, blob_id) ON DELETE CASCADE
);

-- Answering "what still points at this blob", which is how a write decides whether the blob it
-- just dropped became garbage. The primary key leads on the entity and cannot serve it.
CREATE INDEX ix_blob_refs__collection_id__blob_id
    ON sync.blob_refs (collection_id, blob_id);
