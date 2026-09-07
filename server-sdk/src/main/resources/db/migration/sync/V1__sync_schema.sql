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
