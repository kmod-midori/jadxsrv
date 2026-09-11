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

# Run the server directly
./gradlew run
```

The server listens on `http://0.0.0.0:28080`.

## How it works

Every route is nested under `/{encodedFilePaths}`, where `encodedFilePaths` is a `;`-separated list of
Base64url-encoded absolute file paths to the input file(s) to decompile (an APK, JAR, DEX, or a combination
thereof). The first request for a given `encodedFilePaths` value lazily creates and caches a `JadxDecompiler`
instance; subsequent requests reuse it until it's explicitly released with:

```
POST /{encodedFilePaths}/close
```

Within that scope, the API exposes two virtual top-level directories:

- **`classes`** — the decompiled package/class tree
- **`resources`** — app resources, including entries unpacked from the APK's resource table (`.arsc`)

Each supports a common set of operations:

| Route | Description |
|---|---|
| `GET /{encodedFilePaths}/ls/classes/{path...}` | List contents of a directory/package |
| `GET /{encodedFilePaths}/stat/classes/{path...}` | Get metadata (file vs. directory, size) for a path |
| `GET /{encodedFilePaths}/read/classes/{path...}` | Read decompiled source / resource content |
| `GET /{encodedFilePaths}/annotation/classes/{path...}?offset=` | Get hover/type info at a character offset |
| `GET /{encodedFilePaths}/definition/classes/{path...}?offset=` | Resolve go-to-definition at a character offset |
| `GET /{encodedFilePaths}/outline/classes/{path...}` | Get a symbol outline (classes/methods/fields) for a class |
| `GET /{encodedFilePaths}/refs/classes/{path...}?offset=` | Find references/usages of the symbol at an offset |

(`resources` supports the same `ls`/`stat`/`read`/`annotation`/`definition` routes, but not `outline`/`refs`.)

Additionally, a streaming search endpoint is available:

- `POST /{encodedFilePaths}/search/{taskId}?query=&types=&limit=&ignoreCase=` — streams matching
  classes/methods/fields/text as newline-delimited JSON. `types` is a comma-separated subset of
  `class,method,field,text`.
- `DELETE /{encodedFilePaths}/search/{taskId}` — cancels an in-flight search by task ID.

## Project layout

- `Main.kt` — CLI entrypoint and Ktor server/route setup
- `Decompilers.kt` — decompiler instance cache, keyed by `encodedFilePaths`
- `ClassesRoutes.kt` — package/class browsing, outline, and references
- `ResourcesRoutes.kt` — resource browsing and the `.arsc` resource table
- `SearchRoutes.kt` — cancellable streaming search
- `Utils.kt` — shared path/annotation/formatting helpers
- `model/` — `kotlinx.serialization` response DTOs
