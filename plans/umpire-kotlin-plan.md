# Umpire — Kotlin Port Plan

**Do NOT commit this plan to the umpire repo** — keep it private in daywatch.

Cross-reference: `umpire-json-prisma-plan.md` — the JSON portability spec is the foundation this plan builds on.
Cross-reference: `umpire-dart-plan.md` — parallel port; divergence table at bottom of each plan.

---

## Overview

Two artifacts mirroring the `@umpire/core` + `@umpire/react` split:

- **`umpire-core`** — pure Kotlin, no Android/Compose dependency. Schema loading, rule evaluation, availability computation, play/foul logic. Works in any Kotlin environment (Android, server via Ktor, KMP).
- **`umpire-compose`** — Jetpack Compose integration. `rememberUmpire` and `UmpireState` wired to Compose's reactive model. Depends on `umpire-core`.

Published to **Maven Central** under the group `dev.umpire`.
Artifact IDs: `dev.umpire:umpire-core`, `dev.umpire:umpire-compose`.

Kotlin's DSL capabilities are the headline difference from the Dart port. Where Dart mirrors the TypeScript API closely (named params), Kotlin can express the same concepts with a builder DSL that is more expressive and more idiomatic. Both styles are provided — function-based for familiarity, DSL builder as the recommended path.

---

## Package 1: `umpire-core`

### Public API — two styles

#### Function-based (mirrors TypeScript API)

```kotlin
val userUmp = umpire(
    fields = mapOf(
        "email"       to FieldDef(required = true,  isEmpty = IsEmptyStrategy.String),
        "accountType" to FieldDef(required = true,  defaultValue = "personal"),
        "companyName" to FieldDef(required = false, isEmpty = IsEmptyStrategy.String),
        "planId"      to FieldDef(required = true,  isEmpty = IsEmptyStrategy.String),
    ),
    rules = listOf(
        requires("companyName",
            when = { v, _ -> v["accountType"] == "business" },
            reason = "Company name is required for business accounts"),
        enabledWhen("companyVatNumber",
            when = { v, _ -> v["accountType"] == "business" },
            reason = "VAT number only applies to business accounts"),
        enabledWhen("discountOverride",
            when = { _, c -> c["isAdmin"] == true },
            reason = "Admin only"),
        fairWhen("planId",
            when = { v, c -> @Suppress("UNCHECKED_CAST") (c["validPlans"] as List<String>).contains(v["planId"]) },
            reason = "Plan is not available for this account"),
        check("email", checkOps.email()),
        check("password", checkOps.minLength(8)),
    ),
)
```

#### DSL builder (recommended)

```kotlin
val userUmp = umpire {
    fields {
        "email"       { required = true;  isEmpty = IsEmptyStrategy.String }
        "accountType" { required = true;  defaultValue = "personal" }
        "companyName" { required = false; isEmpty = IsEmptyStrategy.String }
        "planId"      { required = true;  isEmpty = IsEmptyStrategy.String }
    }
    rules {
        requires("companyName",
            when = { v, _ -> v["accountType"] == "business" },
            reason = "Company name is required for business accounts")

        enabledWhen("companyVatNumber",
            when = { v, _ -> v["accountType"] == "business" },
            reason = "VAT number only applies to business accounts")

        enabledWhen("discountOverride",
            when = { _, c -> c["isAdmin"] == true },
            reason = "Admin only")

        fairWhen("planId",
            when = { v, c -> validPlansFor(c).contains(v["planId"]) },
            reason = "Plan is not available for this account")

        check("email", checkOps.email())
        check("password", checkOps.minLength(8))
        check("username", checkOps.matches(Regex("^[a-z0-9_]+$"),
            reason = "Lowercase, numbers, underscores only"))
    }
}
```

Both produce the same `Umpire` instance. The DSL builder is preferred because it reads naturally, eliminates the `listOf`/`mapOf` noise, and supports extension functions that can add domain-specific rule helpers.

---

### Build from JSON schema

```kotlin
import dev.umpire.UmpireSchema

val schema = UmpireSchema.fromJson(jsonString)  // kotlinx.serialization

val userUmp = schema.build {
    // Conditions declared in the schema — provide runtime implementations
    conditions {
        "isAdmin"    { false }                       // static
        "validPlans" { fetchValidPlans(userId) }     // suspend lambda — see async section
    }
    // Extend with native rules the JSON spec can't express
    rules {
        fairWhen("planId",
            when = { v, c -> validPlansFor(c).contains(v["planId"]) },
            reason = "Plan is not available for this account")
    }
}
```

`UmpireSchema.fromJson` validates, then `build` compiles DSL expressions to native lambdas and wires condition providers.

---

### Core types

```kotlin
data class FieldDef(
    val required: Boolean = false,
    val defaultValue: Any? = null,
    val isEmpty: IsEmptyStrategy = IsEmptyStrategy.Present,
)

enum class IsEmptyStrategy { String, Number, Boolean, Array, Present }

data class FieldAvailability(
    val enabled: Boolean,
    val required: Boolean,
    val fair: Boolean,
    val checkErrors: List<CheckError> = emptyList(),
)

typealias AvailabilityMap = Map<String, FieldAvailability>
typealias Values = Map<String, Any?>
typealias Conditions = Map<String, Any?>
typealias Predicate = (values: Values, conditions: Conditions) -> Boolean

data class Foul(
    val field: String,
    val reason: String,
    val suggestedValue: Any? = null,
)

data class CheckError(
    val field: String,
    val reason: String,
)
```

---

### Rule functions

```kotlin
// Field dependency
fun requires(field: String, dependency: String? = null, `when`: Predicate? = null, reason: String? = null): Rule
fun enabledWhen(field: String, `when`: Predicate, reason: String? = null): Rule
fun disables(source: String, targets: List<String>, `when`: Predicate? = null, reason: String? = null): Rule
fun oneOf(group: String, branches: Map<String, List<String>>): Rule
fun anyOf(vararg rules: Rule): Rule
fun fairWhen(field: String, `when`: Predicate, reason: String? = null): Rule
fun check(field: String, op: CheckOp): Rule
```

Note: `when` is a reserved word in Kotlin — backtick-escaping is idiomatic for this case. The DSL builder uses a lambda receiver instead to avoid it:

```kotlin
// In DSL context — no backtick needed
requires("companyName") { v, _ -> v["accountType"] == "business" }
// vs function-based
requires("companyName", `when` = { v, _ -> v["accountType"] == "business" })
```

---

### Availability + play

```kotlin
class Umpire internal constructor(/* ... */) {

    fun check(values: Values, conditions: Conditions = emptyMap()): AvailabilityMap

    suspend fun checkAsync(values: Values): AvailabilityMap  // resolves suspend condition providers

    fun play(
        before: AvailabilityMap,
        after: AvailabilityMap,
        values: Values,
    ): List<Foul>
}
```

`check()` is synchronous — expects pre-resolved conditions. `checkAsync()` resolves `suspend` condition providers then evaluates. `play()` is always synchronous.

---

### Named check ops

```kotlin
object checkOps {
    fun email(reason: String? = null): CheckOp
    fun url(reason: String? = null): CheckOp
    fun matches(pattern: Regex, reason: String? = null): CheckOp
    fun minLength(min: Int, reason: String? = null): CheckOp
    fun maxLength(max: Int, reason: String? = null): CheckOp
    fun min(min: Number, reason: String? = null): CheckOp
    fun max(max: Number, reason: String? = null): CheckOp
    fun range(min: Number, max: Number, reason: String? = null): CheckOp
    fun integer(reason: String? = null): CheckOp
}
```

When loading from a JSON schema, the named op strings are compiled to these native implementations via `kotlinx.serialization` polymorphic deserialization.

---

### JSON schema loading

`kotlinx.serialization` is the Kotlin-native choice. No Gson/Moshi.

```kotlin
@Serializable
data class UmpireJsonSchema(
    val version: Int,
    val conditions: Map<String, ConditionDef> = emptyMap(),
    val fields: Map<String, JsonFieldDef>,
    val rules: List<JsonRule>,
)

object UmpireSchema {
    fun fromJson(json: String): CompiledSchema
    fun fromJson(map: Map<String, Any?>): CompiledSchema
}

class CompiledSchema internal constructor(/* ... */) {
    fun build(block: SchemaBuilder.() -> Unit = {}): Umpire
}
```

Validation at parse time matches the spec: unknown ops throw `UmpireSchemaException`, missing field references throw, bad condition references throw.

---

### Async / coroutines

Condition providers can be regular lambdas or `suspend` lambdas:

```kotlin
conditions {
    "isAdmin"    { sessionStore.isAdmin() }          // suspend — DB/cache lookup
    "validPlans" { planService.fetchValid(userId) }  // suspend
}
```

`schema.build { conditions { ... } }` stores providers as `suspend () -> Any?`. `checkAsync()` launches them in parallel via `coroutineScope { async { ... } }` and awaits all before evaluation. `check()` throws if any provider is a suspend function — forces you to use `checkAsync()` when needed.

---

### Package structure

```
umpire-core/
  src/
    main/kotlin/dev/umpire/
      Umpire.kt              # Umpire class, umpire() builder function
      FieldDef.kt            # FieldDef, IsEmptyStrategy
      Rule.kt                # Rule sealed class, Predicate typealias
      rules/
        Requires.kt
        EnabledWhen.kt
        Disables.kt
        OneOf.kt
        AnyOf.kt
        FairWhen.kt
        Check.kt
      CheckOps.kt            # checkOps object, CheckOp sealed class
      Availability.kt        # FieldAvailability, AvailabilityMap, Foul
      dsl/
        UmpireDsl.kt         # DSL builder scope classes
        FieldsDsl.kt
        RulesDsl.kt
      json/
        Schema.kt            # UmpireSchema, UmpireJsonSchema, CompiledSchema
        Expr.kt              # DSL expression compiler
        ConditionProvider.kt
  src/
    test/kotlin/dev/umpire/
      UmpireTest.kt
      CheckOpsTest.kt
      json/
        SchemaTest.kt
        ExprTest.kt
  build.gradle.kts
```

---

## Package 2: `umpire-compose`

### Philosophy

Thin reactive bridge between an `Umpire` instance and Compose's state system. One primary path — `rememberUmpire` — rather than multiple integration options. Compose's own reactive model is good enough that a single hook-like API covers the cases that require three separate integrations in Flutter.

---

### `rememberUmpire`

```kotlin
@Composable
fun rememberUmpire(
    ump: Umpire,
    initialValues: Values = emptyMap(),
    conditions: Conditions = emptyMap(),
): UmpireState
```

Returns a `UmpireState` that is stable across recompositions. Internally uses `remember` + `mutableStateOf`. When values or conditions change, availability recomputes via `derivedStateOf` — only fields whose availability actually changes trigger recomposition.

```kotlin
@Composable
fun UserForm(conditions: Conditions) {
    val form = rememberUmpire(userUmp, conditions = conditions)

    Column {
        OutlinedTextField(
            value = form.values["email"] as? String ?: "",
            onValueChange = { form.setValue("email", it) },
            isError = form.availability["email"]?.checkErrors?.isNotEmpty() == true,
            supportingText = {
                form.availability["email"]?.checkErrors?.firstOrNull()?.let {
                    Text(it.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        )

        AnimatedVisibility(visible = form.availability["companyName"]?.enabled == true) {
            OutlinedTextField(
                value = form.values["companyName"] as? String ?: "",
                onValueChange = { form.setValue("companyName", it) },
            )
        }

        if (form.fouls.isNotEmpty()) {
            form.fouls.forEach { foul ->
                Text("${foul.field}: ${foul.reason}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

---

### `UmpireState`

```kotlin
@Stable
class UmpireState internal constructor(
    ump: Umpire,
    initialValues: Values,
    initialConditions: Conditions,
) {
    val values: Values
        get() = _values

    val availability: AvailabilityMap
        get() = _availability   // derivedStateOf — stable reference, granular recomposition

    val fouls: List<Foul>
        get() = _fouls

    val isValid: Boolean
        get() = fouls.isEmpty() && _availability.values.none {
            (it.enabled && it.required && isEmpty(it)) || it.checkErrors.isNotEmpty()
        }

    fun setValue(field: String, value: Any?)
    fun setValues(patch: Values)
    fun setConditions(conditions: Conditions)
    fun reset()
}
```

`@Stable` tells Compose the class controls its own change notifications — prevents unnecessary recomposition of callers.

---

### Async conditions in Compose

When conditions require async resolution (auth, feature flags), load them before passing to `rememberUmpire`:

```kotlin
@Composable
fun UserFormScreen(userId: String) {
    // Resolve conditions outside the form — standard Compose async pattern
    val conditions by produceState<Conditions>(emptyMap()) {
        value = mapOf(
            "isAdmin"    to sessionService.isAdmin(),
            "validPlans" to planService.fetchValid(userId),
        )
    }

    rememberUmpire(userUmp, conditions = conditions)
    // Form renders with empty conditions initially, recomposes when conditions load
}
```

Alternatively, pass an `Umpire` built with `checkAsync()` and use `LaunchedEffect` to resolve. Either pattern works — `umpire-compose` doesn't prescribe how you load conditions.

---

### Package structure

```
umpire-compose/
  src/
    main/kotlin/dev/umpire/compose/
      RememberUmpire.kt      # rememberUmpire composable
      UmpireState.kt         # UmpireState @Stable class
  src/
    test/kotlin/dev/umpire/compose/
      RememberUmpireTest.kt  # Compose testing via composeTestRule
  build.gradle.kts
```

---

## Publishing — Maven Central

Maven Central requires more ceremony than pub.dev. Standard Sonatype OSSRH workflow.

`build.gradle.kts` (root):
```kotlin
plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.29.0"  // simplifies Central publishing
}

// Group and version
group = "dev.umpire"
version = "0.1.0"
```

`build.gradle.kts` (umpire-core):
```kotlin
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("dev.umpire", "umpire-core", version.toString())
    pom {
        name = "Umpire Core"
        description = "Declarative form validation with conditional field availability."
        url = "https://umpire.dev"
        licenses { license { name = "MIT" } }
        developers { developer { id = "umpire"; name = "Umpire" } }
        scm { url = "https://github.com/[org]/umpire-kotlin" }
    }
}
```

Dependencies:
- `umpire-core`: `org.jetbrains.kotlinx:kotlinx-serialization-json`, `org.jetbrains.kotlinx:kotlinx-coroutines-core`
- `umpire-compose`: `umpire-core`, `androidx.compose.runtime:runtime`

---

## Implementation guidance for LLM jump-start

These sections exist specifically to give an LLM enough internal specificity to produce correct code on the first pass, rather than requiring iteration on the hard parts.

### DSL builder internals

Use `@DslMarker` to prevent scope leakage — without it, inner lambda scopes can accidentally call methods from outer scopes, producing confusing APIs.

```kotlin
@DslMarker
annotation class UmpireDsl

@UmpireDsl
class UmpireBuilder {
    private val fieldDefs = mutableMapOf<String, FieldDef>()
    private val rules = mutableListOf<Rule>()

    fun fields(block: FieldsScope.() -> Unit) {
        FieldsScope(fieldDefs).block()
    }

    fun rules(block: RulesScope.() -> Unit) {
        RulesScope(rules).block()
    }

    internal fun build(): Umpire = Umpire(fieldDefs.toMap(), rules.toList())
}

@UmpireDsl
class FieldsScope(private val defs: MutableMap<String, FieldDef>) {
    // "email" { required = true } syntax
    operator fun String.invoke(block: FieldDefBuilder.() -> Unit) {
        defs[this] = FieldDefBuilder().apply(block).build()
    }
}

@UmpireDsl
class FieldDefBuilder {
    var required: Boolean = false
    var defaultValue: Any? = null
    var isEmpty: IsEmptyStrategy = IsEmptyStrategy.Present

    internal fun build() = FieldDef(required, defaultValue, isEmpty)
}

@UmpireDsl
class RulesScope(private val rules: MutableList<Rule>) {
    // All rule functions are methods on this scope — no `when` keyword conflict
    // because they take trailing lambda, not a named `when` parameter
    fun requires(field: String, reason: String? = null, predicate: (Predicate)? = null) {
        rules += RequiresRule(field, predicate, reason)
    }
    fun enabledWhen(field: String, reason: String? = null, predicate: Predicate) {
        rules += EnabledWhenRule(field, predicate, reason)
    }
    // ... etc for all rule types
}

// Top-level builder function
fun umpire(block: UmpireBuilder.() -> Unit): Umpire =
    UmpireBuilder().apply(block).build()
```

The `operator fun String.invoke` pattern is what enables `"email" { required = true }` syntax. This is idiomatic Kotlin DSL — used by Gradle, Ktor, and others. Do not use `put("email", ...)` or map-literal syntax.

---

### `UmpireState` and `rememberUmpire` internals

This is the section most likely to be implemented incorrectly. Follow this pattern exactly.

```kotlin
@Stable
class UmpireState internal constructor(
    private val ump: Umpire,
    initialValues: Values,
    initialConditions: Conditions,
) {
    // mutableStateOf — Compose tracks reads, triggers recomposition on write
    private var _values by mutableStateOf(initialValues)
    private var _conditions by mutableStateOf(initialConditions)

    // derivedStateOf — only recomputes when _values or _conditions actually change.
    // Components that read `availability` only recompose when availability changes,
    // NOT on every _values write if the availability result is the same.
    val availability: AvailabilityMap by derivedStateOf {
        ump.check(_values, _conditions)
    }

    // Fouls require a before/after transition — cannot be derivedStateOf alone.
    // Updated externally via SideEffect in rememberUmpire (see below).
    var fouls: List<Foul> by mutableStateOf(emptyList())
        internal set

    val values: Values get() = _values

    val isValid: Boolean by derivedStateOf {
        fouls.isEmpty() && availability.values.all { avail ->
            !avail.enabled || (!avail.required || !isEmpty(avail)) && avail.checkErrors.isEmpty()
        }
    }

    fun setValue(field: String, value: Any?) {
        _values = _values + (field to value)  // creates new map — triggers derivedStateOf recompute
    }

    fun setValues(patch: Values) {
        _values = _values + patch
    }

    fun setConditions(conditions: Conditions) {
        _conditions = conditions  // triggers derivedStateOf recompute
    }

    fun reset() {
        _values = emptyMap()
        fouls = emptyList()
    }

    // Internal — accessed by rememberUmpire for foul tracking
    internal var _prevAvailability: AvailabilityMap? = null
}
```

```kotlin
@Composable
fun rememberUmpire(
    ump: Umpire,
    initialValues: Values = emptyMap(),
    conditions: Conditions = emptyMap(),
): UmpireState {
    // remember(ump) — stable across recompositions, recreated only if ump instance changes.
    // Do NOT use remember(conditions) — that recreates state and loses form values
    // when conditions change (e.g. after async auth loads).
    val state = remember(ump) { UmpireState(ump, initialValues, conditions) }

    // Propagate external condition changes without recreating state.
    // LaunchedEffect(conditions) reruns when conditions reference changes.
    LaunchedEffect(conditions) {
        state.setConditions(conditions)
    }

    // SideEffect runs after every successful recomposition.
    // This is the correct place to capture the before→after availability transition
    // and compute fouls. It runs synchronously after the composition is committed.
    SideEffect {
        val current = state.availability
        val prev = state._prevAvailability
        if (prev != null && prev !== current) {  // reference check — AvailabilityMap is a new map on each recompute
            state.fouls = ump.play(before = prev, after = current, values = state.values)
        }
        state._prevAvailability = current
    }

    return state
}
```

**Why `SideEffect` and not `LaunchedEffect` for fouls:** `LaunchedEffect` is async (launches a coroutine). `SideEffect` is synchronous and guaranteed to run after every successful recomposition. Fouls are computed synchronously from two already-computed maps — no coroutine needed, and you want them available in the same frame.

**Why `prev !== current` (reference) not `prev != current` (equality):** `AvailabilityMap` is a `Map<String, FieldAvailability>`. `ump.check()` returns a new map instance on every call. Reference check is sufficient and faster than deep equality.

---

### Coroutine parallel condition resolution

When `checkAsync()` resolves `suspend` condition providers, run them in parallel — not sequentially:

```kotlin
// Inside Umpire.checkAsync()
suspend fun checkAsync(values: Values): AvailabilityMap = coroutineScope {
    // Launch all suspend providers in parallel
    val resolved = suspendProviders
        .map { (key, provider) -> key to async { provider() } }
        .associate { (key, deferred) -> key to deferred.await() }

    // Merge with pre-resolved static conditions
    val conditions = staticConditions + resolved
    check(values, conditions)
}
```

`coroutineScope { }` + `async { }` is the correct pattern. Do not use `withContext` (changes dispatcher, doesn't parallelize). Do not sequence with `map { provider() }` (that's sequential). The `coroutineScope` boundary ensures all async providers complete before evaluation proceeds and cancels all if any throws.

---

### DSL expression compiler — condition ops

The four condition ops have distinct semantics that are easy to confuse. Implement them exactly as follows:

```kotlin
// cond — boolean condition, truthy check
// { "op": "cond", "condition": "isAdmin" }
{ _, conditions -> conditions["isAdmin"] == true }

// condEq — condition SCALAR equals a static value
// { "op": "condEq", "condition": "accountTier", "value": "pro" }
{ _, conditions -> conditions["accountTier"] == "pro" }

// condIn — condition SCALAR is in a static set (NOT for array conditions)
// { "op": "condIn", "condition": "accountTier", "values": ["pro", "team"] }
{ _, conditions -> listOf("pro", "team").contains(conditions["accountTier"]) }

// fieldInCond — field value is in a condition ARRAY (the validPlans / dynamic options pattern)
// { "op": "fieldInCond", "field": "planId", "condition": "validPlans" }
{ values, conditions ->
    @Suppress("UNCHECKED_CAST")
    (conditions["validPlans"] as List<String>).contains(values["planId"])
}
```

**`condIn` vs `fieldInCond` — the critical distinction:**
- `condIn`: condition is a **scalar**, checked against a **static list in the schema** — e.g. "is accountTier one of pro/team?"
- `fieldInCond`: field is a **scalar**, condition is a **dynamic array from the runtime** — e.g. "is planId in the server-provided validPlans list?"

`fairWhen("planId")` almost always compiles to `fieldInCond`, not `condIn`. Confusing these produces a bug that won't surface unless you test with a condition array that has different values than the field.

---

### Schema loading — version check and `excluded` section

Two requirements from the portability contract that must be implemented in `UmpireSchema.fromJson`:

**Version check** — fail loudly on unsupported version:
```kotlin
if (schema.version != 1) {
    throw UmpireSchemaException(
        "Unsupported schema version ${schema.version}. This runtime supports version 1."
    )
}
```
Do not silently ignore an unknown version. A schema written for a future version may have ops or rule types this runtime doesn't understand.

**`excluded` section** — surface as warnings, not errors:
```kotlin
// After building the Umpire instance, check for excluded rules
if (schema.excluded.isNotEmpty()) {
    schema.excluded.forEach { entry ->
        Logger.w("umpire-core",
            "Rule '${entry.type}' for field '${entry.field}' was excluded from schema: ${entry.description}. " +
            "Implement an equivalent rule natively.")
    }
}
```
`excluded` entries are not runtime errors — the schema is still valid and evaluatable. They are developer-time signals that native rules need to be added. In a production build, consider suppressing the log or routing to a crash analytics breadcrumb rather than logcat.

---

## Divergence from Dart port

| Decision | Kotlin | Dart |
|---|---|---|
| API style | DSL builder (primary) + function-based (secondary) | Named params — close to TS original |
| Async | `suspend fun` / coroutines (parallel condition resolution via `async {}`) | `Future<T>` / `async`/`await` |
| Reactive integration | `rememberUmpire` / `derivedStateOf` — one primary path | ChangeNotifier, Riverpod, hooks — three first-class paths |
| Publishing | Maven Central (Sonatype, GPG signing, more setup) | pub.dev (simple, `dart pub publish`) |
| JSON | `kotlinx.serialization` | `dart:convert` (built-in) |
| Core/UI split | `umpire-core` + `umpire-compose` | `umpire` + `umpire_flutter` |
| Reserved word tension | `when` needs backtick in function-based API — DSL avoids it | No conflicts |
| Granular recomposition | `derivedStateOf` — only changed fields trigger recomposition | Depends on state management choice |

---

## Execution order

| Phase | Content |
|---|---|
| 1 | `umpire-core` — core types, rule functions, DSL builder, `ump.check()`, `ump.play()` |
| 2 | `umpire-core` — named check ops + `checkOps` object |
| 3 | `umpire-core` — JSON schema loading, DSL expression compiler, condition providers |
| 4 | `umpire-core` — async condition resolution (`checkAsync`, suspend providers) |
| 5 | `umpire-core` — tests across all phases (Kotest) |
| 6 | `umpire-compose` — `UmpireState`, `rememberUmpire`, `derivedStateOf` wiring |
| 7 | `umpire-compose` — Compose UI tests via `composeTestRule` |
| 8 | Maven Central setup — Sonatype OSSRH, GPG, `vanniktech.maven.publish` |
| 9 | Publish `umpire-core` and `umpire-compose` |

Phase 3 depends on `@umpire/json` being published with conditions + named check ops. Phases 1–2 can start independently.

---

## Open questions

1. **Kotlin Multiplatform (KMP)?** `umpire-core` has no Android or JVM-only dependencies — it could target KMP (iOS via Swift interop, WASM, JS). This is a meaningful future option but adds build complexity. Start JVM-only, revisit after the core API stabilizes.

2. **`when` backtick ergonomics** — the DSL builder avoids the reserved word issue cleanly. The function-based API needs backticks. Document the DSL as the preferred path, keep function-based for users migrating from TypeScript mental model.

3. **Testing framework — JUnit5 vs Kotest?** Kotest is more idiomatic Kotlin (data-driven tests, property testing, better coroutine support). Recommend Kotest. The extra setup is worth it.

4. **Condition type safety** — `Conditions` is `Map<String, Any?>` which loses type information. A sealed class approach (`Condition.Bool`, `Condition.StringList`, etc.) would be more Kotlin-idiomatic but adds friction. Start with `Any?`, add a typed `TypedConditions` API in a later version if the casting noise is bad in practice.

5. **`@Stable` correctness in `UmpireState`** — marking it `@Stable` tells Compose we guarantee change notification behavior. This is accurate if we use `mutableStateOf` internally, but needs careful review during implementation — incorrect `@Stable` annotation causes silent missed recompositions.
