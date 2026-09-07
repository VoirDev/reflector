# Kotlin Exposed Backend Guidelines

> **Status:** Active  
> **Revision:** 1.0  
> **Revision date:** 2026-08-18  
> **Audience:** Kotlin backend developers and coding agents

This document extends the [Kotlin Development Guidelines](kotlin.md). Read and apply
that document first. The rules below cover only Kotlin Exposed and relational persistence; they do
not repeat the generic rules for file organization, naming Kotlin declarations, KDoc, visibility,
testing, or change scope.

These rules target the repository's current baseline: Kotlin 2.4, Exposed 1.3.1, JDBC, PostgreSQL,
HikariCP, and Flyway. Revalidate API-specific examples when upgrading Exposed or Kotlin.

## Core approach

- Model the database explicitly. Table definitions, constraints, indexes, transactions, and query
  shapes are part of the backend contract, not incidental implementation details.
- Create an Exposed DAO entity for every application-owned table, including association and outbox
  tables. An entity's existence does not require every operation to use the DAO API.
- Use both the DSL and DAO APIs. Select the API that produces the clearest domain code and the most
  appropriate SQL for the operation.
- Prefer one well-shaped query with joins, projections, aggregation, or subqueries over multiple
  queries that assemble the same result in memory.
- Keep Exposed types inside the persistence boundary. Repositories return domain models or explicit
  projections, never `Entity`, `EntityID`, `ResultRow`, `Query`, or `SizedIterable`.
- Keep business rules in the domain or application layer. Tables and entities describe persistence;
  repositories coordinate storage.

## Kotlin-native identifiers and time

### UUIDs

- Use `kotlin.uuid.Uuid` everywhere in application, domain, contract, and persistence code.
- Use Exposed's Kotlin UUID APIs: `uuid()`, `UuidTable`, `UuidEntity`, and `UuidEntityClass`.
- Do not use `java.util.UUID`, `javaUUID()`, or the Exposed types under `.java` UUID packages.
- Use a database-native UUID column. Do not store UUIDs as `varchar`, `text`, or byte arrays.
- Generate identifiers in the application when the identifier is needed before insertion, such as
  for an outbox event or aggregate construction. Do not round-trip through strings internally.
- Parse and format UUID text only at a transport boundary.

The relevant imports are:

```kotlin
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
```

### Instants

- Use `kotlin.time.Instant` for moments on the timeline, including `createdAt`, `updatedAt`,
  `expiresAt`, `acceptedAt`, and `revokedAt`.
- Use `kotlin.time.Clock` as an injected dependency when application behavior needs the current time.
  Do not call the system clock directly inside business logic.
- With `exposed-kotlin-datetime`, use `timestamp()` from `org.jetbrains.exposed.v1.datetime`; in
  Exposed 1.3.1 it maps to `kotlin.time.Instant`.
- In every PostgreSQL Flyway migration, declare a column mapped to `kotlin.time.Instant` as
  `TIMESTAMPTZ` (`TIMESTAMP WITH TIME ZONE`). Never use `TIMESTAMP` or
  `TIMESTAMP WITHOUT TIME ZONE` for an instant.
- Do not use `java.time.Instant`, `java.sql.Timestamp`, or the `exposed-java-time` timestamp API.
- Use `kotlinx.datetime.LocalDate` or another calendar type only when the domain value is genuinely a
  date or local wall-clock value rather than a moment. Do not use a local date-time for audit fields.
- Persist instants consistently as PostgreSQL `TIMESTAMPTZ`, normalize application values to UTC,
  and serialize them as RFC 3339 strings at wire boundaries. PostgreSQL stores `TIMESTAMPTZ` as an
  absolute moment; the session timezone affects presentation, not the stored instant.
- Choose timestamp precision deliberately and keep the Kotlin value, PostgreSQL column, migration,
  and tests aligned. Do not rely on precision that the database column discards.

Example column:

```kotlin
import org.jetbrains.exposed.v1.datetime.timestamp

val createdAt = timestamp("created_at")
```

Prefer assigning an instant obtained from an injected `Clock` so a multi-row operation can use the
same deterministic timestamp. Use a database-generated timestamp only when database ownership of
the value is an intentional contract.

## Tables and entities

### One table and one entity per pair

- Define every table as a named Exposed `Table` or `IdTable` object.
- Define a matching DAO entity and entity class for every table.
- Put the table object and entity class in separate files, following the generic one-top-level-type
  rule.
- Name table declarations `<PluralResource>Table`, such as `OrganizationsTable` and
  `PermissionGrantsTable`.
- Name DAO entities with a singular `<Resource>Entity`, such as `OrganizationEntity` and
  `PermissionGrantEntity`.
- Mark tables and entities `internal` unless another module genuinely consumes them, which should be
  uncommon because persistence is owned by its feature module.
- Keep entities persistence-focused. They may expose mapped fields and Exposed relationships but
  must not perform authorization, publish messages, call other services, or contain aggregate
  workflows.
- Use `UuidTable` by default for independently addressable rows. Association tables should also have
  a UUID identifier when the row has lifecycle or audit meaning; protect the logical association
  with a separate unique constraint.
- Use a composite primary key only when it is a real database identity and the added DAO complexity
  is justified. Do not choose one merely to avoid an identifier column.

Example split across two files:

```kotlin
// OrganizationsTable.kt
package dev.voir.timelyti.organization.persistence

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/** Relational schema for organization lifecycle state. */
internal object OrganizationsTable : UuidTable("organizations") {
    val name = varchar("name", 200)
    val slug = varchar("slug", 100)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id, name = "pk_organizations")

    init {
        uniqueIndex("uq_organizations__slug", slug)
    }
}
```

```kotlin
// OrganizationEntity.kt
package dev.voir.timelyti.organization.persistence

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass

/** DAO representation of one row in [OrganizationsTable]. */
internal class OrganizationEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<OrganizationEntity>(OrganizationsTable)

    var name by OrganizationsTable.name
    var slug by OrganizationsTable.slug
    var createdAt by OrganizationsTable.createdAt
}
```

## Physical naming

Always pass explicit physical names. Do not rely on Exposed deriving SQL identifiers from Kotlin
declaration names.

### Tables and columns

- Use plural `snake_case` table names: `organizations`, `projects`, `permission_grants`.
- Use singular `snake_case` column names.
- Name primary-key columns `id`.
- Name foreign-key columns `<referenced_resource>_id`, such as `organization_id` and `project_id`.
- Use `_at` for stored instants and `_by` for actor identifiers: `created_at`, `created_by`.
- Name boolean columns as predicates: `is_active`, `has_access`, `should_retry`.
- Avoid reserved SQL words, quoted mixed-case identifiers, and unexplained abbreviations.
- Give `varchar` columns a domain-based maximum length. Do not use `text` to avoid choosing a limit.
- Make nullability intentional. A nullable column must have a documented domain meaning; do not use
  null as an unmodelled state flag.
- Store stable enum or state codes by name, never by ordinal. Renaming a persisted code requires a
  migration and compatibility plan.

### Keys, constraints, and indexes

Use explicit lowercase names with double underscores separating logical sections:

| Database object | Pattern | Example |
| --- | --- | --- |
| Primary key | `pk_<table>` | `pk_permission_grants` |
| Foreign key | `fk_<table>__<columns>__<target>` | `fk_projects__organization_id__organizations` |
| Unique constraint/index | `uq_<table>__<columns>` | `uq_projects__organization_id__key` |
| Non-unique index | `ix_<table>__<columns>` | `ix_permission_grants__user_id__project_id` |
| Check constraint | `ck_<table>__<rule>` | `ck_invitations__scope` |

- Keep names within PostgreSQL's identifier limit. Shorten consistently rather than allowing silent
  truncation and collisions.
- Define every primary key, foreign key, uniqueness rule, and domain-valid check in the database as
  well as in application validation.
- Specify `onDelete` and `onUpdate` behavior deliberately for every foreign key. Default to
  `RESTRICT`/`NO_ACTION` for lifecycle and audit records. Use `CASCADE` only when the child cannot
  exist independently and hard deletion is part of the intended lifecycle.
- Add indexes for foreign keys used in joins and for frequent filter/order patterns. PostgreSQL does
  not automatically index referencing foreign-key columns.
- Order composite index columns to match real equality, range, and ordering predicates. Do not add
  speculative indexes without a query they support.
- Use a unique constraint for business invariants such as one active logical assignment. Do not rely
  on a read-before-write check, which races under concurrency.
- Keep Exposed definitions and Flyway migrations identical in physical names, types, nullability,
  defaults, constraints, and indexes.

## Choosing DAO or DSL

Creating an entity for every table provides a consistent object mapping; it does not make DAO the
default for every query.

### Prefer DAO when

- creating or updating a small number of individual rows;
- navigating a simple relationship within an already bounded transaction;
- the entity lifecycle closely matches the use case;
- dirty tracking makes a focused update clearer;
- the operation does not require a projection, aggregation, complex join, or bulk mutation.

### Prefer DSL when

- joining tables or returning a purpose-built projection;
- filtering, sorting, paginating, grouping, or aggregating in SQL;
- performing existence checks or counts;
- inserting, updating, revoking, or deleting rows in bulk;
- implementing atomic conditional updates, optimistic locking, or upserts;
- selecting only a subset of columns;
- avoiding lazy-loading and N+1 queries;
- the desired SQL is clearer than an equivalent entity graph.

DAO and DSL may be used in the same transaction when that is the clearest implementation. Use
`EntityClass.wrapRows()` only when a join-backed query genuinely needs entity behavior; otherwise map
the DSL projection directly to the return model.

## Query design and joins

- Start from the result shape and design the SQL needed to produce it.
- Use `innerJoin`, `leftJoin`, explicit `join`, subqueries, and aggregation instead of issuing one
  query per related row.
- Select only the columns required by the operation. Avoid `selectAll()` for multi-table or
  externally returned projections unless all columns are genuinely needed.
- Push filtering, sorting, distinctness, grouping, limits, and existence checks into the database.
  Do not load a collection to filter, sort, count, or deduplicate it in Kotlin.
- Never perform a database query inside `map`, `forEach`, an entity property loop, or a serializer.
- Use `batchInsert` and set-based update/delete operations for collections. Do not issue one statement
  per item when one batch or set operation expresses the same rule.
- Prefer keyset pagination for large, ordered collections. If offset pagination is used, the query
  must have a deterministic order ending in a unique tie-breaker.
- Align indexes with join keys and the predicates used by important queries.
- Inspect generated SQL for non-trivial queries. Code that looks concise in DAO form can still issue
  many statements.

DAO relationships are lazy by default. If DAO is otherwise the right choice and related values are
known to be needed, use Exposed's eager-loading facilities such as `.load()` or `.with()`. Prefer an
explicit DSL join when one projection query can return the complete result. Eager loading is not an
excuse to ignore query count; verify the generated statements.

Do not force unrelated aggregates into one enormous query when doing so changes semantics, causes a
cartesian explosion, or makes locking unsafe. Multiple queries are acceptable when they represent
distinct operations and the reason is clear. The prohibited pattern is accidental query
multiplication when a single well-shaped query can do the work.

## Transactions and concurrency

- Execute every DSL and DAO operation inside an explicit transaction boundary.
- Define transaction ownership at the application service or repository unit-of-work boundary. Do
  not open an independent transaction inside each helper or loop iteration.
- Keep transactions short. Do not perform RabbitMQ publishing, user-service calls, HTTP requests, or
  other remote I/O while a database transaction and connection are open.
- Persist outbound integration events in an outbox row in the same transaction as the state change;
  publish them after commit.
- JDBC is blocking. Do not call JDBC Exposed transactions directly from an unconstrained coroutine
  context; use the application's configured blocking execution and transaction integration.
- DAO entities and lazy relationships are transaction-bound. Read and map every required value
  before leaving the transaction.
- Pass a `Database` explicitly when more than one database can exist. Do not depend on a mutable
  global default database.
- Choose isolation level, read-only mode, retry policy, and timeout deliberately for the use case.
  Retrying a transaction is safe only when the entire block is idempotent and contains no external
  side effects.
- Use database uniqueness and foreign keys for invariants under concurrency.
- For optimistic concurrency, update with both identifier and expected version in the predicate and
  increment the version atomically. Treat an affected-row count of zero as a version conflict.
- Use row locks only for a documented invariant that cannot be protected more simply. Lock rows in a
  consistent order to reduce deadlock risk.

## Migrations and schema ownership

- Flyway migrations are the production source of truth for schema changes.
- Exposed table declarations must describe the same resulting schema and are reviewed together with
  the migration.
- Every Exposed `kotlin.time.Instant` property must map to a `TIMESTAMPTZ` column in its Flyway
  migration. Treat `TIMESTAMP WITHOUT TIME ZONE` as a schema defect for instant-valued fields.
- Never run `SchemaUtils.create`, `createMissingTablesAndColumns`, or automatic destructive schema
  alignment during production startup.
- `SchemaUtils` may create isolated test schemas. Tests that validate production migrations must run
  the Flyway scripts instead.
- Generated migration SQL is a starting point, not an approved migration. Review locks, data
  backfills, defaults, destructive statements, and rollback/forward-fix behavior manually.
- Prefer expand-and-contract migrations for deployed systems: add compatible schema, deploy code
  that supports both shapes, migrate data, then remove the old shape in a later release.
- Never rename a table, column, constraint, or persisted code only in the Exposed declaration.
- Keep destructive data changes explicit and separately reviewed.

## Repository mapping

- Keep table and entity types in persistence packages.
- Map entities and `ResultRow` values to domain models or explicit read projections inside the
  transaction.
- Keep mapping functions focused and test non-trivial conversions. Do not make domain models depend
  on Exposed.
- Repositories expose operations in domain terms, not generic table access such as `save(any)` or
  `findAll()` when the feature has more specific behavior.
- Query methods should make scope and loading behavior clear, such as
  `findActiveGrantsWithDefinitions()` rather than `getData()`.
- Avoid a generic base repository that hides SQL, transaction boundaries, affected-row counts, or
  query shape.

## Persistence tests

- Use PostgreSQL Testcontainers for behavior that depends on PostgreSQL types, constraints,
  transactions, indexes, locking, or SQL semantics. An in-memory database is not a substitute for
  those tests.
- Test every meaningful uniqueness, foreign-key, status, and concurrency invariant at the database
  boundary.
- For important join-backed reads, assert the returned projection and guard against N+1 behavior by
  inspecting or counting generated statements.
- Test DAO and DSL paths that cooperate in the same transaction.
- Run Flyway from an empty database in integration tests and validate that Exposed operations work
  against the migrated schema.
- Keep test data deterministic by supplying `Uuid` values and an injected `Clock`.

## Review checklist

In addition to the generic Kotlin checklist, verify:

- [ ] Every table has a matching DAO entity in its own file.
- [ ] Identifiers use `kotlin.uuid.Uuid` and Kotlin-native Exposed UUID APIs.
- [ ] Timeline values use `kotlin.time.Instant` and `exposed-kotlin-datetime`.
- [ ] Every `kotlin.time.Instant` column is declared as `TIMESTAMPTZ` in Flyway migrations.
- [ ] Physical tables, columns, constraints, and indexes have explicit consistent names.
- [ ] Foreign-key actions, nullability, lengths, defaults, and enum/state storage are intentional.
- [ ] DAO or DSL was selected based on query shape rather than habit.
- [ ] Joins or eager loading prevent avoidable N+1 queries.
- [ ] Collection writes use batches or set-based mutations where possible.
- [ ] Transactions do not contain remote I/O and do not leak Exposed objects.
- [ ] Flyway migrations and Exposed declarations describe the same schema.
- [ ] PostgreSQL integration tests cover meaningful persistence behavior.

## Primary references

- [Exposed 1.3.1 overview](https://www.jetbrains.com/help/exposed/about.html)
- [Exposed UUID migration guide](https://www.jetbrains.com/help/exposed/migration-guide-1-0-0.html)
- [Exposed date and time types](https://www.jetbrains.com/help/exposed/date-and-time-types.html)
- [Exposed table definitions and constraints](https://www.jetbrains.com/help/exposed/working-with-tables.html)
- [Exposed joins](https://www.jetbrains.com/help/exposed/dsl-joining-tables.html)
- [Exposed DAO relationships and eager loading](https://www.jetbrains.com/help/exposed/dao-relationships.html)
- [Exposed transactions](https://www.jetbrains.com/help/exposed/transactions.html)
- [Exposed migrations](https://www.jetbrains.com/help/exposed/migrations.html)
- [Kotlin `Uuid`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.uuid/-uuid/)
- [Kotlin `Instant`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.time/-instant/)
