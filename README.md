# jadxsrv

`jadxsrv` is a small Kotlin/Ktor HTTP server that wraps the [jadx](https://github.com/skylot/jadx) Android
decompiler and exposes it as two interfaces:

- a filesystem-like REST API, meant to be used as a backend for editor tooling (e.g. an extension that
  browses a decompiled APK/DEX/JAR as if it were a read-only virtual file system);
- an [MCP](https://modelcontextprotocol.io) server (Streamable HTTP at `/mcp`, built on
  `io.modelcontextprotocol:kotlin-sdk`) exposing jadx as analysis tools for LLM clients, modeled after
  [jadx-mcp-server](https://github.com/zinja-coder/jadx-ai-mcp) (minus its debugger tools).

Both run on the same port against the same decompiler instance, so renames/aliases made through one
interface are visible in the other.

## Requirements

- JDK 11+
- The Gradle wrapper (`./gradlew`) handles the rest (Gradle, Kotlin, and all dependencies)

## Building & running

```bash
# Build (compiles, tests, and assembles a jar)
./gradlew build

# Run the server with one or more APK, JAR, or DEX inputs
./run.sh /absolute/path/to/app.apk

# Persist symbol renames to a JSON file across restarts (jadx-gui's
# .jadx project codeData format; loaded back on startup if it exists)
./run.sh --code-data /path/to/codedata.json /absolute/path/to/app.apk
```

The server listens on `http://0.0.0.0:28080` by default; pass `--port <n>` to
change it:

```bash
./run.sh --port 9000 /absolute/path/to/app.apk
```

## MCP endpoint

The MCP endpoint is served alongside the REST API at `http://<host>:<port>/mcp`
(Streamable HTTP transport, one shared jadx-backed `Server` for all sessions).
Point any MCP client that supports Streamable HTTP at it, e.g.:

```json
{
  "mcpServers": {
    "jadxsrv": {
      "type": "http",
      "url": "http://localhost:28080/mcp"
    }
  }
}
```

Tools exposed (see `McpServer.kt`):

| Tool | Description |
|---|---|
| `list_classes` | Paginated list of all classes |
| `get_class_source` | Decompiled Java source of a class |
| `get_methods_of_class` / `get_fields_of_class` | Members of a class with signatures |
| `get_method_by_name` | Decompiled source of a single method, extracted from the class |
| `search_classes` / `search_methods` | Substring search over names (display and raw/obfuscated) |
| `search_code` | Full-text search in decompiled code (decompiles on demand; slow at first) |
| `get_xrefs_to_class` / `get_xrefs_to_method` / `get_xrefs_to_field` | Usage locations with class, line, and snippet |
| `get_android_manifest` | Decoded AndroidManifest.xml |
| `get_manifest_component` | Components of one type (activity/service/…) with exported state |
| `get_main_activity_class` | Launcher activity resolved from the manifest |
| `get_main_application_classes_names` / `get_main_application_classes_code` | Names or source of classes in the app's own package |
| `list_resource_files` / `get_resource_file` / `get_strings` | Resource browsing (text resources only) |
| `rename_class` / `rename_method` / `rename_field` | Set/reset deobfuscation aliases (same data and persistence as the REST `/rename` route) |
| `rename_variable` | Rename a local variable/method argument, scoped to one SSA variable |

Class lookup accepts the alias (deobfuscated) or raw full name; ambiguous method
names can be disambiguated with the `method_id` signature from
`get_methods_of_class`.

## How it works

Input files are positional CLI arguments. Supply one or more absolute or relative APK, JAR, or DEX paths when
starting the server:

```bash
./run.sh /path/to/app.apk /path/to/classes.dex
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
| `POST /rename/classes/{path...}?offset=` | Rename or reset a class, method, or field alias at an offset |
| `GET /typehierarchy/classes/{path...}?offset=` | Resolve the class at an offset into a type hierarchy item |
| `GET /typehierarchy/resources/{path...}?offset=` | Same, resolving a class name in an XML resource |
| `GET /typehierarchy/supertypes/classes/{path...}?offset=` | Direct superclasses and implemented interfaces of the class at an offset |
| `GET /typehierarchy/subtypes/classes/{path...}?offset=` | Direct subclasses and implementors of the class at an offset |

(`resources` supports the same `ls`/`stat`/`read`/`annotation`/`definition` routes, but not `outline`/`refs`.)

Additionally, a streaming search endpoint is available:

- `POST /search/{taskId}?query=&types=&limit=&ignoreCase=` — streams matching
  classes/methods/fields/text as newline-delimited JSON. `types` is a comma-separated subset of
  `class,method,field,text`.
- `DELETE /search/{taskId}` — cancels an in-flight search by task ID.

Symbol renames use the same JADX alias data and reload flow as the GUI. Send a
JSON body such as `{"name":"betterName"}`; an empty name resets the alias.
Renames last for the running server session unless `--code-data <file>` was
passed, in which case they are saved after every rename (atomically, via a
temp file) and loaded back on startup. The JSON matches the `codeData` block
of a jadx-gui `.jadx` project file.

## Project layout

- `Main.kt` — CLI entrypoint and Ktor server/route setup (REST + MCP)
- `Decompilers.kt` — shared decompiler lifecycle
- `ClassesRoutes.kt` — package/class browsing, outline, and references
- `ResourcesRoutes.kt` — resource browsing and the `.arsc` resource table
- `SearchRoutes.kt` — cancellable streaming search
- `McpServer.kt` — MCP tool registration (Streamable HTTP at `/mcp`)
- `RenameSupport.kt` — shared apply/reload/persist logic for renames
- `Utils.kt` — shared path/annotation/formatting helpers
- `model/` — `kotlinx.serialization` response DTOs
