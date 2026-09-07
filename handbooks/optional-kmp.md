# Optional KMP Partial-Update Guidelines

> **Status:** Active  
> **Revision:** 1.0  
> **Revision date:** 2026-08-18  
> **Audience:** Kotlin API, client, and backend developers and coding agents

This document extends the [Kotlin Development Guidelines](kotlin.md). Read and
apply that document first. These rules cover using
[Optional KMP](https://github.com/VoirDev/optional-kmp) in request DTOs and update flows where an
omitted property, an explicit JSON `null`, and a non-null value have different meanings.

These examples target Optional KMP `1.0.2`, published as `dev.voir:optional`. That release provides
JVM, Android, iOS x64, iOS arm64, iOS simulator arm64, and macOS arm64 artifacts. Revalidate the
coordinates, targets, and serialization behavior when upgrading.

## When to use Optional

Use `Optional<T>` at a boundary that must preserve all three update states:

| Kotlin state | JSON property | Update meaning |
| --- | --- | --- |
| `Optional.Absent` | omitted | Leave the existing value unchanged |
| `Optional.PresentNull` | `"field": null` | Explicitly clear the value |
| `Optional.Present(value)` | `"field": value` | Replace the value |

Ordinary nullable properties cannot distinguish an omitted field from an explicit `null` after
deserialization. Use `Optional` for PATCH requests, partial form submissions, and update commands
only when that distinction is part of the contract.

Do not use `Optional`:

- as a general replacement for nullable Kotlin types;
- in create or replace DTOs where every field has ordinary required/default/null semantics;
- in stored domain entities merely because a field is nullable; or
- when the API intentionally treats omitted and explicit-null properties identically.

Keep the tri-state value at the transport or update-command boundary. Resolve it while applying the
update so ordinary read models and persisted entities retain domain-focused types.

## Installation

Add the library to the KMP source set that owns the DTOs:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.voir:optional:1.0.2")
        }
    }
}
```

The consuming module must apply the Kotlin serialization plugin to compile `@Serializable` DTOs.
Add `kotlinx-serialization-json` as well when the module encodes or decodes JSON. Keep both versions
compatible with the project's Kotlin baseline rather than copying the library build's versions
blindly.

## Define patch DTOs

Every patchable property must be non-nullable at the wrapper level and default to
`Optional.Absent`. The wrapped type is non-null; `PresentNull` carries the explicit-null state.

```kotlin
import dev.voir.optional.Optional
import kotlinx.serialization.Serializable

/** Fields a client may change on an existing user. */
@Serializable
data class UserPatch(
    val displayName: Optional<String> = Optional.Absent,
    val age: Optional<Int> = Optional.Absent,
)
```

Do not declare `Optional<T>?`, use `null` as its default, or add a separate `hasField` boolean.
Those shapes introduce a fourth state or duplicate the presence information.

For a nested partial update, wrap a nested patch DTO rather than the complete domain object:

```kotlin
@Serializable
data class AddressPatch(
    val city: Optional<String> = Optional.Absent,
    val postalCode: Optional<String> = Optional.Absent,
)

@Serializable
data class UserPatch(
    val address: Optional<AddressPatch> = Optional.Absent,
)
```

The endpoint contract must define what explicit null means for each field. Reject
`Optional.PresentNull` during validation when a field may be omitted or changed but may not be
cleared.

## Configure JSON correctly

Use `encodeDefaults = false` so an `Optional.Absent` default is omitted.

```kotlin
import kotlinx.serialization.json.Json

val apiJson = Json {
    encodeDefaults = false
}
```

`encodeDefaults = false` applies to every default-valued property in the serialized DTO, not only
to `Optional` properties. Define the API's default-value behavior deliberately and test the
complete JSON shape. `PresentNull` is a non-null wrapper value whose serializer writes JSON null;
it is not the same as a nullable DTO property governed directly by the JSON `explicitNulls`
setting.

Never serialize `Optional.Absent` as a standalone value. Absence is represented only by omission of
the surrounding property, and the library throws `SerializationException` if its serializer is
asked to encode `Absent`. Do not force default encoding for an `Optional` property.

The expected wire shapes are:

```json
{}
```

```json
{"displayName": null}
```

```json
{"displayName": "Ada"}
```

During decoding, the DTO's default supplies `Absent` for a missing property. The serializer maps a
present JSON null to `PresentNull` and a present non-null value to `Present`.

## Apply updates explicitly

Use `orElse(currentValue)` when all three states map directly to keep, clear, or replace:

```kotlin
import dev.voir.optional.orElse

data class User(
    val displayName: String?,
    val age: Int?,
)

/** Applies only fields supplied by [patch]. */
fun User.apply(patch: UserPatch): User =
    copy(
        displayName = patch.displayName.orElse(displayName),
        age = patch.age.orElse(age),
    )
```

`orElse` uses its fallback only for `Absent`; it returns `null` for `PresentNull`. Do not replace it
with the Elvis operator, which would incorrectly turn an explicit null back into the current value.

Use `ifProvided` when applying a mutable update or calling an API only for supplied fields:

```kotlin
patch.displayName.ifProvided { suppliedName ->
    user.displayName = suppliedName
}
```

Use exhaustive `when` handling when the states require different validation, authorization, audit,
or persistence behavior:

```kotlin
when (val name = patch.displayName) {
    Optional.Absent -> Unit
    Optional.PresentNull -> user.clearDisplayName()
    is Optional.Present -> user.rename(name.value)
}
```

Do not use `isNullOrAbsent` or `ifNullOrAbsent` when absence and explicit null lead to different
business behavior; those helpers intentionally combine the two states.

## Construction and conversion

Choose factories according to the meaning of a nullable source:

```kotlin
val replacement = Optional.of("Ada")
val suppliedNullable = nullableName.toOptional()
val suppliedOnlyWhenNonNull = nullableName.toOptionalOrAbsent()
```

- `Optional.of(value)` creates `Present(value)` and accepts only non-null values.
- `Optional.ofNullable(value)` and `toOptional()` convert null to `PresentNull`.
- `toOptionalOrAbsent()` converts null to `Absent`.

Do not choose between the nullable conversions for convenience. A null converted with
`toOptional()` requests a clear; the same null converted with `toOptionalOrAbsent()` requests no
change.

## Validation and API contracts

- Validate only provided values for ordinary value constraints. An absent field carries no new
  value to validate.
- Validate clear operations separately. A non-nullable domain property may accept `Absent` and
  `Present`, while rejecting `PresentNull` with a field-specific client error.
- Apply authorization to the requested operation, including clearing a value. Do not skip checks
  merely because the supplied value is null.
- Define collection semantics explicitly. `Absent`, `PresentNull`, `Present(emptyList())`, and
  `Present(nonEmptyList())` are four observably different inputs even though `Optional` itself has
  three states.
- Preserve presence through transport-to-command mapping. Converting to `T?` before validation or
  application loses the distinction the library exists to retain.
- Keep update application atomic when several provided fields form one business operation.

## Testing

For each patch DTO, cover the three-state serialization contract and the update behavior it drives:

```kotlin
@Test
fun `omitted display name remains absent`() {
    val patch = apiJson.decodeFromString<UserPatch>("{}")

    assertEquals(Optional.Absent, patch.displayName)
}

@Test
fun `explicit null clears display name`() {
    val patch = apiJson.decodeFromString<UserPatch>("""{"displayName":null}""")
    val updated = User(displayName = "Ada", age = 42).apply(patch)

    assertNull(updated.displayName)
}

@Test
fun `absent defaults are omitted while explicit null is encoded`() {
    assertEquals("{}", apiJson.encodeToString(UserPatch()))
    assertEquals(
        """{"displayName":null}""",
        apiJson.encodeToString(UserPatch(displayName = Optional.PresentNull)),
    )
}
```

Also test non-null replacement, invalid explicit clears, nested patch behavior, and collection
semantics when they are part of the endpoint. These are project-owned wire and business contracts,
so they warrant tests even though the serializer itself belongs to the library.

## Reference

- [Optional KMP repository and README](https://github.com/VoirDev/optional-kmp)
- [Optional KMP 1.0.2 release](https://github.com/VoirDev/optional-kmp/releases/tag/v1.0.2)
- [Maven Central coordinates](https://central.sonatype.com/artifact/dev.voir/optional/1.0.2)
