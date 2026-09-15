-- Index the foreign key that purging deletes by.
--
-- A purge removes a scope's or a collection's rows outright, and push results are the one child
-- table with no index leading on `collection_id`: the constraint it carries starts with `client_id`,
-- because until now nothing ever read or deleted these rows by collection. Both the purge's own
-- delete and the cascade from `sync.collections` had to scan the whole table for each collection.

CREATE INDEX ix_push_results__collection_id ON sync.push_results (collection_id);
