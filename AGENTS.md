# jadxsrv

A Kotlin/Ktor HTTP server that wraps the [jadx](https://github.com/skylot/jadx) decompiler library, exposing
APK/DEX/JAR decompilation over a filesystem-like REST API (list/stat/read/annotation/definition/references/search).
It is designed to be consumed by an editor extension that treats a decompiled app as a virtual, read-only file
system (paths encode which package/class/resource is being browsed).

## Build, run, test

- Build: `./gradlew build`
- Run locally: `./gradlew run` (starts the Ktor server on port `28080`, see `Main.kt`)
- Run all tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "moe.reimu.jadxsrv.SomeTestClass"`
- Run a single test method: `./gradlew test --tests "moe.reimu.jadxsrv.SomeTestClass.someMethod"`
- There are currently no test sources under `src/test/kotlin` — `src/test` only has empty `kotlin`/`resources`
  directories wired up via `testImplementation(kotlin("test"))` in `build.gradle.kts`.
- JVM toolchain is Java 11 (`kotlin { jvmToolchain(11) }`).

## Architecture

Input files are supplied as positional CLI arguments at server startup (see `App` in `Main.kt`). One
`Decompiler` serves all requests for the supplied APK/JAR/DEX inputs.

- `loadDecompiler()` creates the `Decompiler` (wraps `JadxDecompiler` + the loaded `.arsc` resource table) once at
  startup; `Main.kt` passes that instance to all route groups and closes it on shutdown.
- There are two virtual top-level directories, each with parallel
  `ls` / `stat` / `read` / `annotation` / `definition` sub-routes:
  - `classes` (`ClassesRoutes.kt`) — package/class tree backed by `jadx.api.JavaPackage` / `JavaClass`.
    `resolvePath()` walks a dot-path built from the URL path segments to resolve a `RootPath` / `JavaPackage` /
    `JavaClass`. Also exposes `outline` (symbol tree via `classToSymbol`) and `refs` (find usages via
    `findUsage`, using jadx's code metadata + `ICodeAnnotation`).
  - `resources` (`ResourcesRoutes.kt`) — raw app resources plus entries unpacked from the `.arsc` resource table
    (`ResContainer`). `resolveResource()` matches the request path against resource names; binary resources are
    streamed directly out of the source `ZipFile`.
- `SearchRoutes.kt` implements a cancellable, streaming (NDJSON via `respondTextWriter`) search across classes/
  methods/fields/text for a given decompiler, keyed by a client-supplied `taskId` so an in-flight search can be
  cancelled with `DELETE /search/{taskId}`.
- `annotation`/`definition`/`refs` endpoints all pivot on `offset` (a character offset into the decompiled text)
  looked up in jadx's `codeMetadata` to get an `ICodeAnnotation`, which is then resolved to a class/method/field
  node (see `resolveDefinition()` and `formatHover()` in `Utils.kt`).
- Response DTOs are `kotlinx.serialization` `@Serializable` data classes under `model/`; kind/type constants
  (e.g. `OutlineResponse.Symbol.TYPE_METHOD`, `StatResponse.TYPE_FILE`) mirror LSP-style symbol kinds — reuse
  existing constants rather than inventing new numbering when adding symbol kinds.

## Conventions

- Route groups receive the active `Decompiler` from `Main.kt` — never construct a `JadxDecompiler` directly in a
  route.
- Path segments arrive as a raw list from Ktor (`call.parameters.getAll("path")`); use `getCleanPath()`
  (`Utils.kt`) to strip the leading/trailing empty segments before resolving.
- Route files use `when (resolved) { is JavaClass -> ...; is JavaPackage -> ... }` over the result of
  `resolvePath()`/`resolveResource()`; add new branches there rather than special-casing types elsewhere.
- Each route file declares its own `private val logger = LoggerFactory.getLogger("jadxsrv.<area>")` — follow this
  per-file logger naming pattern for new route files.
- `alias` (not `name`/`shortName`) is the deobfuscated/display name used in formatted output (`formatClass`,
  `formatMethod`, `formatField`, `SymbolsResponse.Symbol`); use it consistently for anything user-facing.
