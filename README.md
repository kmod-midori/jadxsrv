# jadxsrv

`jadxsrv` is a small Kotlin/Ktor HTTP server that wraps the [jadx](https://github.com/skylot/jadx) Android
decompiler and exposes it as a filesystem-like REST API. It's meant to be used as a backend for editor
tooling (e.g. an extension that browses a decompiled APK/DEX/JAR as if it were a read-only virtual file
system), rather than as an end-user application on its own.

## Requirements

- JDK 11+
- The Gradle wrapper (`./gradlew`) handles the rest (Gradle, Kotlin, and all dependencies)

## Building & running

```bash
# Build (compiles, tests, and assembles a jar)
./gradlew build

# Run the server directly with one or more APK, JAR, or DEX inputs
./gradlew run --args="/absolute/path/to/app.apk"
```

The server listens on `http://0.0.0.0:28080`.

## How it works

Input files are positional CLI arguments. Supply one or more absolute or relative APK, JAR, or DEX paths when
starting the server:

```bash
./gradlew run --args="/path/to/app.apk /path/to/classes.dex"
```

The server initializes one `JadxDecompiler` for those files, then exposes two virtual top-level directories:

- **`classes`** — the decompiled package/class tree
- **`resources`** — app resources, including entries unpacked from the APK's resource table (`.arsc`)

Each supports a common set of operations:

| Route | Description |
|---|---|
| `GET /ls/classes/{path...}` | List contents of a directory/package |
| `GET /stat/classes/{path...}` | Get metadata (file vs. directory, size) for a path |
| `GET /read/classes/{path...}` | Read decompiled source / resource content |
| `GET /annotation/classes/{path...}?offset=` | Get hover/type info at a character offset |
| `GET /definition/classes/{path...}?offset=` | Resolve go-to-definition at a character offset |
| `GET /outline/classes/{path...}` | Get a symbol outline (classes/methods/fields) for a class |
| `GET /refs/classes/{path...}?offset=` | Find references/usages of the symbol at an offset |

(`resources` supports the same `ls`/`stat`/`read`/`annotation`/`definition` routes, but not `outline`/`refs`.)

Additionally, a streaming search endpoint is available:

- `POST /search/{taskId}?query=&types=&limit=&ignoreCase=` — streams matching
  classes/methods/fields/text as newline-delimited JSON. `types` is a comma-separated subset of
  `class,method,field,text`.
- `DELETE /search/{taskId}` — cancels an in-flight search by task ID.

## Project layout

- `Main.kt` — CLI entrypoint and Ktor server/route setup
- `Decompilers.kt` — shared decompiler lifecycle
- `ClassesRoutes.kt` — package/class browsing, outline, and references
- `ResourcesRoutes.kt` — resource browsing and the `.arsc` resource table
- `SearchRoutes.kt` — cancellable streaming search
- `Utils.kt` — shared path/annotation/formatting helpers
- `model/` — `kotlinx.serialization` response DTOs
