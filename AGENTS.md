# jadxsrv

A Kotlin/Ktor HTTP server that wraps the [jadx](https://github.com/skylot/jadx) decompiler library, exposing
APK/DEX/JAR decompilation over a filesystem-like REST API (list/stat/read/annotation/definition/references/search).
It is designed to be consumed by an editor extension that treats a decompiled app as a virtual, read-only file
system (paths encode which package/class/resource is being browsed).

## Build, run, test

- Build: `./gradlew build`
- Run locally: `./run.sh /path/to/app.apk` (starts the Ktor server on port
  `28080` by default; override with `--port <n>`, see `Main.kt`). The
  launcher passes one or more input paths to Gradle's `run` task.
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
- `POST /rename/classes/{path...}?offset=` resolves a class, method, field,
  or local variable (including method args) from JADX code metadata and applies
  an in-memory user alias. It follows JADX's `JadxCodeData`/`reloadCodeData()`
  flow; empty names reset aliases. Variable renames use a `JadxCodeRef.forVar`
  code ref attached to the enclosing method ref (same shape as jadx-gui's
  `JVariable.buildCodeRename`), so they are scoped to one SSA variable.
- Rename persistence is opt-in via `--code-data <path>` (`CodeDataStore.kt`):
  loaded at startup (fails fast on a corrupt file so it can't be overwritten
  empty), saved inside the rename lock after every rename, atomically via
  write-then-move. The format is byte-compatible with the `codeData` block of
  a jadx-gui `.jadx` project (same Gson beans/interfaceReplace adapters), so
  files can be shared with the GUI. Without the flag nothing touches disk.
- After a rename, `refreshAffectedClasses()` must invalidate decompiled code
  for every class that can show the renamed symbol (the node's own class, its
  `useIn` classes, and for methods the override-related methods and their
  callers), mirroring jadx-gui's `RenameService` — otherwise `/read` keeps
  serving stale cached code for other classes. Affected classes are unloaded,
  so the next read re-decompiles them on demand.
- `GET /callhierarchy[/incoming|/outgoing]/classes/{path...}?offset=`
  (`CallHierarchyRoutes.kt`) backs the editor's call hierarchy, mirroring
  jadx-gui's `UsageDialogPlus` (incoming includes override-related methods).
  Call-site attribution to enclosing methods uses `ICodeMetadata.getNodeAt()`.
  Compare method identity by source key (declaring class + name + parameter
  types, no return type), never by `MethodNode`/`MethodInfo` equality: jadx
  merges bridge methods into one source method and codegen can leave stale
  instances in `useIn`/annotations, so stricter comparisons silently miss
  results.
- `GET /typehierarchy[/supertypes|/subtypes]/classes/{path...}?offset=`
  (`TypeHierarchyRoutes.kt`, plus a `/typehierarchy/resources/{path...}`
  prepare variant for class names in XML resources) backs the editor's
  type hierarchy: direct
  superclass/interfaces and direct subclasses/implementors, walked one level
  per request. Supertypes resolve via `RootNode.resolveClass(ArgType)` (types
  not in the inputs, e.g. java.lang.Object, drop out); subtypes scan
  `root.classes` for raw-name matches.
- defPosition gotcha: `JavaClass.decompile()` (its `load()`) skips codegen for
  classes already processed as dependencies of another class' codegen, so
  their `defPosition`/`codeMetadata` annotations are never set and stay 0
  forever. Any code reading `defPosition` must first force codegen through
  `codeInfo` (as `TypeHierarchyRoutes.classToItem` and the `outline` route
  do), never rely on `decompile()` alone.

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
