# Kotlin Development Guidelines

> **Status:** Active · **Revision:** 1.2 · **Revision date:** 2026-09-05 · **Audience:** Kotlin
> developers and coding agents

This is the canonical Kotlin guidance for every module and source set. More-specific handbook or
repository instructions may only make these rules stricter.

## Core rules

- Keep changes focused, explicit, and consistent with the surrounding architecture.
- Prefer simple Kotlin to clever abstractions; preserve behavior unless the task changes it.
- Prefer immutable values and Kotlin-native APIs.
- Do not mix unrelated cleanup or broad refactoring into a focused change.
- Put code in the module and source set that owns its responsibility.
- **Document all production code completely. Partial documentation is not acceptable.**
- Before finishing, format and compile affected targets and run the smallest relevant test suite.

## Files, packages, and source sets

Give every independent top-level class, interface, object, and enum its own file named after the
type. The exception is a sealed hierarchy: its sealed class and all subtypes belong in one file.
Do not collect unrelated declarations in `Models.kt`, `Utils.kt`, `Common.kt`, or similar files.

A declaration may stay with its owner only when it has no useful independent meaning: for example,
a private or nested implementation type, scoped sealed subtype, single-test fixture, or small
supporting extension. Move it when it gains reuse or independent behavior, or obscures the primary
type.

Order files as package, imports, primary declaration, then closely related extensions or private
helpers. Within a class, use companion/constants, properties, public API, internal API, protected
API, then private details. Many regions or unrelated method groups usually indicate too many
responsibilities.

- Keep package and directory paths aligned. Organize by feature, use case, or owned capability
  first; add technical layers inside a feature only when they make navigation clearer.
- Never collect unrelated models in a repository-wide `models`, `dto`, `entities`, `common`, or
  `shared` directory. Keep API inputs, commands, domain models, and persistence entities near the
  feature that owns them. Shared code must have a real shared owner.
- Follow neighboring naming and package patterns before introducing a new structure.
- Keep dependency direction intentional; lower layers must not depend on application or
  presentation details.
- In multiplatform code, use `commonMain` only for genuinely portable behavior. Put platform code
  in its platform source set and use `expect`/`actual` only for a clear boundary.
- Mirror production packages in tests.
- Do not move files or packages incidentally. Necessary moves must update imports, tests,
  dependency injection, serialization names, and platform counterparts together.

```text
participants/
  api/CreateParticipantInput.kt
  application/ProvisionParticipant.kt
  domain/Participant.kt
  persistence/ParticipantEntity.kt
```

## Kotlin APIs and idioms

For time, UUIDs, and serialization, always use `kotlin.time`, `kotlin.uuid`, and Kotlin
serialization (`kotlinx.serialization`) in handwritten Kotlin code:

- use `kotlin.time.Duration`, `Instant`, and `Clock` instead of Java time types;
- use `kotlin.uuid.Uuid` instead of `java.util.UUID`;
- use `kotlinx.serialization` instead of reflection-based or Java-oriented serializers.

Convert Java, framework, or vendor types only in the adapter that owns that boundary. Do not let
them spread into the domain. The serialization package is named `kotlinx.serialization`, not
`kotlin.serialization`.

- Prefer `val`, immutable `List`/`Set`/`Map`, and creating a new value to shared mutable state.
- Use `?.`, `?:`, `when`, expression functions, and scope functions when they improve clarity.
  Avoid Java-style getters, setters, utility classes, and decorative `let`/`run`/`also`/`apply`
  chains.
- Prefer early returns to deep nesting and named arguments for ambiguous values, especially
  booleans.
- Prefer collection operations to manual mutation when the resulting pipeline stays readable; use
  a loop when a long transformation chain would obscure the intent.
- Keep functions focused on one meaningful operation, not an arbitrary line limit. Do not fragment
  coherent behavior into many tiny private functions.
- Use extension functions only when they make the receiver's API more natural. Extensions must not
  hide surprising I/O, persistence, messaging, or other side effects.

## Domain modeling

Use value classes and domain types instead of passing interchangeable `String`, `Int`, or `Uuid`
values. Use data classes for data; do not make behavior-heavy objects data classes automatically.

```kotlin
/**
 * Stable identifier of a study.
 *
 * @property value UUID value preserved across system boundaries.
 */
@JvmInline
value class StudyId(val value: Uuid)
```

Use `null` only for true optionality. Model meaningful absence, failure, and state explicitly so
invalid combinations are difficult to construct.

Use a sealed class—not an interface or sealed interface—when the variants form a closed set of
states, results, events, commands, or domain errors. Keep the base class and every subtype in the
same file so the complete model is visible and `when` remains exhaustive.

```kotlin
// ProvisioningResult.kt
/** Result of provisioning one participant. */
sealed class ProvisioningResult {
    /**
     * Successfully provisioned participant.
     *
     * @property participantId Identifier assigned to the participant.
     */
    data class Success(val participantId: ParticipantId) : ProvisioningResult()

    /**
     * Rejected provisioning request.
     *
     * @property reason Business reason for rejecting the request.
     */
    data class Rejected(val reason: FailureReason) : ProvisioningResult()
}
```

Keep interfaces for open behavior contracts whose implementations vary independently, such as a
repository port or external adapter:

```kotlin
/** Persistence operations required by participant provisioning. */
interface ParticipantRepository {
    /**
     * Persists the complete participant state.
     *
     * @param participant Participant state to persist.
     */
    suspend fun save(participant: Participant)
}
```

Avoid `!!`. Prefer `requireNotNull` for invalid caller input, `checkNotNull` for an impossible
internal state, an early return for an allowed absence, or a meaningful domain exception for an
expected business failure. Use `require` for caller contract violations and `check` for invalid
application state; do not default to generic runtime exceptions.

## Application boundaries

- Keep transport DTOs, application commands/queries, domain models, and persistence entities
  distinct when their contracts differ. Do not pass one framework model through every layer.
- Map boundaries explicitly with focused functions such as `toCommand`, `toDomain`, and
  `toResponse`. Avoid generic reflection-based mappers for non-trivial mappings.
- Keep controllers and resolvers thin. Permissions, validation, transactions, and state
  transitions belong in the application or domain layer.
- Make a transaction cover one business operation. Do not assemble an implicit workflow from many
  nested transactional methods.
- Prefer composition to inheritance, especially for services. Do not create `BaseService`,
  `AbstractCrudService`, `GenericRepository`, or similar abstractions before a stable shared
  concept exists; small duplication is cheaper than the wrong abstraction.

## Coroutines

- Follow structured concurrency and propagate cancellation.
- Never use `GlobalScope`, create arbitrary scopes inside services, or call `runBlocking` from
  normal application code.
- Use `withContext` only for a real dispatcher boundary; do not wrap every operation in
  `Dispatchers.IO` mechanically.

## Documentation and comments

### Complete KDoc is mandatory

Every production declaration must have accurate KDoc, regardless of visibility or whether it is
new, changed, inherited, or pre-existing in a touched file. This includes:

- top-level and nested classes, interfaces, objects, enums, sealed types, and type aliases;
- constructors, functions, methods, extensions, and overrides;
- properties, constants, enum entries, and sealed implementations.

When changing a production file, leave the entire file documented; documenting only the changed
part is not sufficient. An inherited declaration may reference inherited documentation only when
its contract is exactly unchanged.

Complete documentation means that every declaration is covered and every important implementation
decision is explained; it does not mean narrating syntax line by line.

KDoc must describe the declaration's responsibility and complete contract, not restate its name.
Include, where applicable:

- invariants, lifecycle, state transitions, ordering, side effects, and concurrency expectations;
- units, formats, valid ranges, ownership, and the meaning of absence;
- every parameter or constructor property with `@param` or `@property`;
- every non-`Unit` result and its semantics with `@return`;
- expected failures callers handle with `@throws`.

```kotlin
/**
 * Loads an account, refreshing stale cached data when necessary.
 *
 * Concurrent calls for the same account share one refresh.
 *
 * @param accountId Stable identifier of the account to load.
 * @param forceRefresh Whether to bypass a fresh cached value.
 * @return The latest account, or `null` when it does not exist.
 * @throws AccountAccessException When the caller cannot read the account.
 */
suspend fun loadAccount(
    accountId: AccountId,
    forceRefresh: Boolean = false,
): MusicAccount?
```

KDoc covers declarations; focused inline comments explain business rules, workarounds, algorithms,
and ordering constraints inside executable code. Every workaround must state why it exists and when
it can be removed. Update comments with behavior: stale documentation is a defect.

## Naming and visibility

- Use one domain term per concept across code, tests, APIs, and documentation.
- Follow Kotlin conventions: `UpperCamelCase` types, `lowerCamelCase` functions and properties,
  `UPPER_SNAKE_CASE` constants, and lowercase packages without underscores.
- Prefer domain intent (`refreshExpiredAccounts`) to vague names (`processData`, `Manager`,
  `Helper`, `Utils`).
- Name booleans as predicates such as `isLoading`, `hasAccess`, or `shouldRefresh`.
- Use established suffixes consistently, including `Repository`, `DataSource`, `Mapper`,
  `ViewModel`, `State`, and `Factory`.
- Do not expose implementation details through public names unless they are contractual.

Kotlin visibility is an API decision:

- mark module-only declarations `internal` and implementation details `private`;
- expose only declarations required by another module or external consumer;
- prefer a small public interface backed by an `internal` implementation;
- never make code public only for a test; test through the module contract or supported internal
  test visibility.

## Tests

Test meaningful changed behavior:

- unit-test business rules, decisions, state transitions, errors, and edge cases;
- integration-test important component boundaries, persistence, networking, dependency injection,
  and platform integrations;
- add regression tests that fail without the fix.

Do not test trivial data classes, accessors, constructors, constants, enum values, mocks' own setup,
uncustomized framework behavior, or standard serialization with no project-owned contract. Test
serialization when custom behavior, compatibility, polymorphism, defaults, or a fragile wire format
makes it meaningful.

Use descriptive Kotlin backtick names:

```kotlin
@Test
fun `fresh cached account is returned without a remote request`() = runTest {
    // Arrange
    // Act
    // Assert
}
```

Each test should express one scenario; several assertions may prove the same outcome. Keep tests
deterministic with fake clocks and controlled dependencies instead of time, randomness, live
services, global order, or arbitrary delays. Prefer fakes for stateful collaborators and mocks for
narrow interaction checks. Assert observable behavior, not a copy of the implementation.

## Change workflow

1. Read the surrounding code, tests, build file, and more-specific guidance.
2. Identify the owning module, package, and source set; reuse established terminology and patterns.
3. Keep code feature-oriented, model domain states explicitly, and preserve layer boundaries.
4. Keep public API and dependency changes minimal.
5. Fully document every production declaration in each touched file and comment important logic.
6. Test meaningful behavior with descriptive backtick names.
7. Format, remove introduced dead code, compile affected targets, and run relevant tests.
8. Review the diff for unrelated edits, misplaced files, visibility leaks, and stale or incomplete
   documentation.

## Completion checklist

- [ ] Files and packages are feature-oriented; unrelated models are not grouped by technical type.
- [ ] Time, UUIDs, and serialization use `kotlin.time`, `kotlin.uuid`, and `kotlinx.serialization`.
- [ ] Values and collections are immutable unless mutation is necessary and contained.
- [ ] Closed domain variants use one fully colocated sealed class hierarchy.
- [ ] DTO, application, domain, persistence, transaction, and coroutine boundaries are explicit.
- [ ] Every production declaration in every touched file has complete, accurate KDoc.
- [ ] KDoc covers all parameters, results, side effects, invariants, and expected failures.
- [ ] Important executable decisions have focused inline comments; no comments merely restate code.
- [ ] Visibility and names preserve a small, consistent domain API.
- [ ] Tests cover meaningful behavior, are deterministic, and use descriptive backtick names.
- [ ] Affected code is formatted, compiles, and passes relevant tests.
- [ ] The final diff contains no unrelated changes.
