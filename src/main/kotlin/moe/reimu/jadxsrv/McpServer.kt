@file:Suppress("UnstableApiUsage")

package moe.reimu.jadxsrv

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jadx.api.JavaClass
import jadx.api.JavaMethod
import jadx.api.metadata.ICodeAnnotation
import jadx.core.deobf.NameMapper
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.xmlgen.ResContainer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("jadxsrv.mcp")

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 1000

/**
 * Builds the MCP server exposing the loaded APK/DEX/JAR as analysis tools,
 * modeled after jadx-mcp-server (minus its debugger tools). The same Server
 * instance is shared by every MCP session on the /mcp endpoint; tool calls are
 * serialized through [mcpMutex] because jadx decompilation is not thread-safe.
 */
fun createMcpServer(decompiler: Decompiler, version: String): Server {
    val server = Server(
        serverInfo = Implementation(name = "jadxsrv", version = version),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    fun textResult(text: String) = CallToolResult(content = listOf(TextContent(text)))
    fun jsonResult(json: JsonObject) = textResult(json.toString())
    fun errorResult(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    val mcpMutex = Mutex()

    suspend fun runTool(block: () -> CallToolResult): CallToolResult = try {
        mcpMutex.withLock { block() }
    } catch (e: Exception) {
        logger.warn("Tool call failed", e)
        errorResult("${e.javaClass.simpleName}: ${e.message}")
    }

    fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content
    fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

    fun limitOf(args: JsonObject?, default: Int = DEFAULT_LIMIT): Int =
        (args?.int("limit") ?: default).coerceIn(1, MAX_LIMIT)

    fun JsonObject.page(offsetName: String = "offset", countName: String = "count"): Pair<Int, Int> =
        (int(offsetName) ?: 0).coerceAtLeast(0) to (int(countName) ?: 0).coerceAtLeast(0)

    fun findClass(name: String): JavaClass? = decompiler.jadx.classesWithInners.firstOrNull {
        val ci = it.classNode.classInfo
        ci.aliasFullName == name || ci.rawName == name
    }

    fun requireClass(args: JsonObject): JavaClass {
        val name = args.str("class_name")
            ?: throw IllegalArgumentException("Missing required argument: class_name")
        return findClass(name) ?: throw IllegalArgumentException("Class not found: $name")
    }

    fun matchesName(display: String, raw: String, query: String, ignoreCase: Boolean): Boolean =
        display.contains(query, ignoreCase) || raw.contains(query, ignoreCase)

    fun strProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun intProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun boolProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun schema(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ) = ToolSchema(
        properties = buildJsonObject { properties.forEach { (k, v) -> put(k, v) } },
        required = required.ifEmpty { null },
    )

    val classNameProp = "class_name" to strProp("Full class name, alias or raw, e.g. com.example.MainActivity")

    fun validateNewName(args: JsonObject): String {
        val newName = args.str("new_name") ?: throw IllegalArgumentException("Missing required argument: new_name")
        if (newName.isNotEmpty() && !NameMapper.isValidIdentifier(newName)) {
            throw IllegalArgumentException("Invalid Java identifier: $newName")
        }
        return newName
    }

    fun collectXrefs(target: ICodeAnnotation, limit: Int): JsonObject {
        val methodUsages = when (target) {
            is ClassNode -> target.useInMth
            is MethodNode -> target.useIn
            is FieldNode -> target.useIn
            else -> emptyList()
        }.map { it.topParentClass }
        val classUsages = if (target is ClassNode) {
            target.useIn.map { it.topParentClass }
        } else {
            emptyList()
        }

        var truncated = false
        val usages = buildJsonArray {
            var emitted = 0
            outer@ for (parentClass in methodUsages + classUsages) {
                val code = parentClass.code.codeStr
                for (offset in findUsage(parentClass, target)) {
                    if (emitted >= limit) {
                        truncated = true
                        break@outer
                    }
                    val position = code.charOffsetToPosition(offset) ?: continue
                    addJsonObject {
                        put("className", parentClass.classInfo.aliasFullName)
                        put("rawClassName", parentClass.classInfo.rawName)
                        put("line", position.line + 1)
                        put("snippet", code.lines().getOrNull(position.line)?.trim() ?: "")
                    }
                    emitted++
                }
            }
        }

        return buildJsonObject {
            put("usages", usages)
            put("truncated", truncated)
        }
    }

    // --- Class browsing -----------------------------------------------------

    server.addTool(
        name = "list_classes",
        description = "List all classes in the decompiled app. Returns a paginated list of class names.",
        inputSchema = schema(
            mapOf(
                "offset" to intProp("Number of classes to skip (default 0)"),
                "count" to intProp("Max classes to return (default $DEFAULT_LIMIT)"),
            ),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val (offset, count) = args.page()
            val classes = decompiler.jadx.classesWithInners.map {
                it.classNode.classInfo.let { ci -> ci.aliasFullName to ci.rawName }
            }
            val page = classes.asSequence().drop(offset).let {
                if (count > 0) it.take(count) else it
            }.toList()
            jsonResult(buildJsonObject {
                put("total", classes.size)
                putJsonArrayOf("classes", page) { (alias, raw) ->
                    put("className", alias)
                    put("rawClassName", raw)
                }
            })
        }
    }

    server.addTool(
        name = "get_class_source",
        description = "Get the decompiled Java source of a class.",
        inputSchema = schema(mapOf(classNameProp), listOf("class_name")),
    ) { request ->
        runTool {
            val cls = requireClass(request.arguments ?: JsonObject(emptyMap()))
            textResult(cls.code ?: throw IllegalStateException("Class has no decompiled code"))
        }
    }

    server.addTool(
        name = "get_methods_of_class",
        description = "List the methods declared by a class, with display name, signature, and declaration.",
        inputSchema = schema(mapOf(classNameProp), listOf("class_name")),
    ) { request ->
        runTool {
            val cls = requireClass(request.arguments ?: JsonObject(emptyMap()))
            jsonResult(buildJsonObject {
                putJsonArrayOf("methods", cls.methods) { mth ->
                    put("name", mth.name)
                    put("id", mth.methodNode.methodInfo.shortId)
                    put("declaration", formatMethod(mth.methodNode))
                }
            })
        }
    }

    server.addTool(
        name = "get_fields_of_class",
        description = "List the fields declared by a class, with display name and declaration.",
        inputSchema = schema(mapOf(classNameProp), listOf("class_name")),
    ) { request ->
        runTool {
            val cls = requireClass(request.arguments ?: JsonObject(emptyMap()))
            jsonResult(buildJsonObject {
                putJsonArrayOf("fields", cls.fields) { field ->
                    put("name", field.name)
                    put("declaration", formatField(field.fieldNode, true))
                }
            })
        }
    }

    // --- Search -------------------------------------------------------------

    server.addTool(
        name = "search_classes",
        description = "Search classes by name (matches both display and raw/obfuscated names).",
        inputSchema = schema(
            mapOf(
                "query" to strProp("Substring to search for"),
                "limit" to intProp("Max results (default $DEFAULT_LIMIT)"),
                "ignore_case" to boolProp("Case-insensitive match (default true)"),
            ),
            listOf("query"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val query = args.str("query") ?: throw IllegalArgumentException("Missing required argument: query")
            val limit = limitOf(args)
            val ignoreCase = args.bool("ignore_case") ?: true
            val matches = mutableListOf<JavaClass>()
            for (cls in decompiler.jadx.classesWithInners) {
                val ci = cls.classNode.classInfo
                if (matchesName(ci.aliasFullName + " " + ci.shortName, ci.rawName, query, ignoreCase) ||
                    ci.aliasShortName.contains(query, ignoreCase)
                ) {
                    matches.add(cls)
                    if (matches.size >= limit) break
                }
            }
            jsonResult(buildJsonObject {
                put("totalMatches", matches.size)
                putJsonArrayOf("matches", matches) { cls ->
                    put("className", cls.classNode.classInfo.aliasFullName)
                    put("rawClassName", cls.classNode.classInfo.rawName)
                    put("declaration", formatClass(cls.classNode))
                }
            })
        }
    }

    server.addTool(
        name = "search_methods",
        description = "Search methods by name across all classes (matches both display and raw/obfuscated names).",
        inputSchema = schema(
            mapOf(
                "query" to strProp("Substring to search for"),
                "limit" to intProp("Max results (default $DEFAULT_LIMIT)"),
                "ignore_case" to boolProp("Case-insensitive match (default true)"),
            ),
            listOf("query"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val query = args.str("query") ?: throw IllegalArgumentException("Missing required argument: query")
            val limit = limitOf(args)
            val ignoreCase = args.bool("ignore_case") ?: true
            val matches = mutableListOf<JavaMethod>()
            outer@ for (cls in decompiler.jadx.classesWithInners) {
                for (mth in cls.methods) {
                    val info = mth.methodNode.methodInfo
                    if (matchesName(info.alias + " " + info.shortId, info.name + " " + info.shortId, query, ignoreCase)) {
                        matches.add(mth)
                        if (matches.size >= limit) break@outer
                    }
                }
            }
            jsonResult(buildJsonObject {
                put("totalMatches", matches.size)
                putJsonArrayOf("matches", matches) { mth ->
                    put("name", mth.name)
                    put("className", mth.declaringClass.classNode.classInfo.aliasFullName)
                    put("id", mth.methodNode.methodInfo.shortId)
                    put("declaration", formatMethod(mth.methodNode))
                }
            })
        }
    }

    server.addTool(
        name = "search_code",
        description = "Full-text search in decompiled class code. Note: classes decompiled on demand, so this can be slow on first searches.",
        inputSchema = schema(
            mapOf(
                "query" to strProp("Text to search for in the decompiled code"),
                "limit" to intProp("Max results (default 50)"),
                "ignore_case" to boolProp("Case-insensitive match (default false)"),
            ),
            listOf("query"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val query = args.str("query") ?: throw IllegalArgumentException("Missing required argument: query")
            val limit = limitOf(args, 50)
            val ignoreCase = args.bool("ignore_case") ?: false
            val found = mutableListOf<Triple<String, Int, String>>()
            for (cls in decompiler.jadx.classesWithInners) {
                val code = cls.code ?: continue
                val index = code.indexOf(query, ignoreCase = ignoreCase)
                if (index != -1) {
                    val position = code.charOffsetToPosition(index)
                    val line = (position?.line ?: 0) + 1
                    found.add(Triple(cls.classNode.classInfo.aliasFullName, line, code.lines().getOrNull(line - 1)?.trim() ?: ""))
                    if (found.size >= limit) break
                }
            }
            jsonResult(buildJsonObject {
                putJsonArrayOf("matches", found) { (className, line, snippet) ->
                    put("className", className)
                    put("line", line)
                    put("snippet", snippet)
                }
            })
        }
    }

    // --- Cross references ---------------------------------------------------

    server.addTool(
        name = "get_xrefs_to_class",
        description = "Find code locations (class, line, snippet) referencing a class.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "limit" to intProp("Max usages (default $DEFAULT_LIMIT)"),
            ),
            listOf("class_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            jsonResult(collectXrefs(cls.classNode, limitOf(args)))
        }
    }

    server.addTool(
        name = "get_xrefs_to_method",
        description = "Find code locations referencing a method. On overloaded names pass method_id (e.g. \"(I)V\" style shortId from get_methods_of_class) to pick one.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "method_name" to strProp("Method name (display or raw)"),
                "method_id" to strProp("Optional method signature shortId, e.g. onCreate(Landroid/os/Bundle;)V, to disambiguate overloads"),
                "limit" to intProp("Max usages (default $DEFAULT_LIMIT)"),
            ),
            listOf("class_name", "method_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val methodName = args.str("method_name")
                ?: throw IllegalArgumentException("Missing required argument: method_name")
            val methodId = args.str("method_id")
            val candidates = cls.methods.filter {
                it.name == methodName || it.methodNode.methodInfo.name == methodName
            }.let { matched ->
                if (methodId != null) matched.filter { it.methodNode.methodInfo.shortId == methodId } else matched
            }
            val mth = when (candidates.size) {
                1 -> candidates.first()
                0 -> throw IllegalArgumentException("Method not found: $methodName in ${cls.fullName}")
                else -> throw IllegalArgumentException(
                    "Ambiguous method name '$methodName'; pass method_id, one of: " +
                        candidates.joinToString(", ") { it.methodNode.methodInfo.shortId },
                )
            }
            jsonResult(collectXrefs(mth.methodNode, limitOf(args)))
        }
    }

    server.addTool(
        name = "get_xrefs_to_field",
        description = "Find code locations referencing a field.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "field_name" to strProp("Field name (display or raw)"),
                "limit" to intProp("Max usages (default $DEFAULT_LIMIT)"),
            ),
            listOf("class_name", "field_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val fieldName = args.str("field_name")
                ?: throw IllegalArgumentException("Missing required argument: field_name")
            val field = cls.fields.firstOrNull {
                it.name == fieldName || it.fieldNode.fieldInfo.name == fieldName
            } ?: throw IllegalArgumentException("Field not found: $fieldName in ${cls.fullName}")
            jsonResult(collectXrefs(field.fieldNode, limitOf(args)))
        }
    }

    // --- Resources ----------------------------------------------------------

    fun loadTextResource(name: String): String {
        val raw = decompiler.jadx.resources.firstOrNull { (it.deobfName ?: it.originalName) == name }
            ?.loadContent()
        val fromTable = raw ?: decompiler.arsc?.subFiles?.firstOrNull { it.name == name }
        val container = fromTable ?: throw IllegalArgumentException("Resource not found: $name")
        return when (container.dataType) {
            ResContainer.DataType.TEXT, ResContainer.DataType.RES_TABLE -> container.text.codeStr
            else -> throw IllegalArgumentException("Resource '$name' is binary; only text resources are supported")
        }
    }

    server.addTool(
        name = "get_android_manifest",
        description = "Get the decoded AndroidManifest.xml of the app.",
        inputSchema = schema(emptyMap()),
    ) {
        runTool { textResult(loadTextResource("AndroidManifest.xml")) }
    }

    server.addTool(
        name = "list_resource_files",
        description = "List resource file names in the app, including entries unpacked from the resource table (.arsc). Paginated.",
        inputSchema = schema(
            mapOf(
                "offset" to intProp("Number of entries to skip (default 0)"),
                "count" to intProp("Max entries to return (default $DEFAULT_LIMIT)"),
            ),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val (offset, count) = args.page()
            val names = decompiler.jadx.resources.map { it.deobfName ?: it.originalName } +
                (decompiler.arsc?.subFiles?.map { it.name } ?: emptyList())
            val page = names.asSequence().drop(offset).let { if (count > 0) it.take(count) else it }.toList()
            jsonResult(buildJsonObject {
                put("total", names.size)
                putJsonArrayOf("files", page) { name -> put("name", name) }
            })
        }
    }

    server.addTool(
        name = "get_resource_file",
        description = "Get the content of a text resource file (XML, decoded .arsc entry, etc).",
        inputSchema = schema(
            mapOf("name" to strProp("Resource path, e.g. res/values/strings.xml or AndroidManifest.xml")),
            listOf("name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val name = args.str("name") ?: throw IllegalArgumentException("Missing required argument: name")
            textResult(loadTextResource(name))
        }
    }

    server.addTool(
        name = "get_strings",
        description = "Get the app's string resources (all locales unpacked from the resource table).",
        inputSchema = schema(emptyMap()),
    ) {
        runTool {
            val arsc = decompiler.arsc ?: throw IllegalStateException("App has no resource table")
            val files = arsc.subFiles.filter { it.name.contains("strings") }
            if (files.isEmpty()) throw IllegalStateException("No decoded string resources found")
            val out = StringBuilder()
            for (file in files) {
                out.appendLine("## ${file.name}")
                when (file.dataType) {
                    ResContainer.DataType.TEXT, ResContainer.DataType.RES_TABLE -> out.appendLine(file.text.codeStr)
                    else -> out.appendLine("(binary, skipped)")
                }
                out.appendLine()
            }
            textResult(out.toString())
        }
    }

    // --- Renaming -----------------------------------------------------------

    server.addTool(
        name = "rename_class",
        description = "Rename (set an alias for) a class. Pass an empty new_name to reset to the original name. Persists to the --code-data file if configured.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "new_name" to strProp("New simple class name (must be a valid Java identifier), or empty to reset"),
            ),
            listOf("class_name", "new_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val newName = validateNewName(args)
            if (newName.contains('.')) throw IllegalArgumentException("new_name must be a simple name, not a package path")
            val applied = renameNode(decompiler, cls, newName)
            jsonResult(buildJsonObject { put("className", cls.classNode.classInfo.rawName); put("name", applied) })
        }
    }

    server.addTool(
        name = "rename_method",
        description = "Rename (set an alias for) a method of a class. Also renames override-related methods. Pass an empty new_name to reset to the original name.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "method_name" to strProp("Current method name (display or raw)"),
                "method_id" to strProp("Optional signature shortId to disambiguate overloads"),
                "new_name" to strProp("New method name (valid Java identifier), or empty to reset"),
            ),
            listOf("class_name", "method_name", "new_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val methodName = args.str("method_name")
                ?: throw IllegalArgumentException("Missing required argument: method_name")
            val methodId = args.str("method_id")
            val candidates = cls.methods.filter {
                it.name == methodName || it.methodNode.methodInfo.name == methodName
            }.let { matched ->
                if (methodId != null) matched.filter { it.methodNode.methodInfo.shortId == methodId } else matched
            }
            val mth = when (candidates.size) {
                1 -> candidates.first()
                0 -> throw IllegalArgumentException("Method not found: $methodName in ${cls.fullName}")
                else -> throw IllegalArgumentException(
                    "Ambiguous method name '$methodName'; pass method_id, one of: " +
                        candidates.joinToString(", ") { it.methodNode.methodInfo.shortId },
                )
            }
            val newName = validateNewName(args)
            val applied = renameNode(decompiler, mth, newName)
            jsonResult(buildJsonObject {
                put("className", cls.classNode.classInfo.rawName)
                put("signature", mth.methodNode.methodInfo.shortId)
                put("name", applied)
            })
        }
    }

    server.addTool(
        name = "rename_field",
        description = "Rename (set an alias for) a field of a class. Pass an empty new_name to reset to the original name.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "field_name" to strProp("Current field name (display or raw)"),
                "new_name" to strProp("New field name (valid Java identifier), or empty to reset"),
            ),
            listOf("class_name", "field_name", "new_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val fieldName = args.str("field_name")
                ?: throw IllegalArgumentException("Missing required argument: field_name")
            val field = cls.fields.firstOrNull {
                it.name == fieldName || it.fieldNode.fieldInfo.name == fieldName
            } ?: throw IllegalArgumentException("Field not found: $fieldName in ${cls.fullName}")
            val newName = validateNewName(args)
            val applied = renameNode(decompiler, field, newName)
            jsonResult(buildJsonObject {
                put("className", cls.classNode.classInfo.rawName)
                put("field", fieldName)
                put("name", applied)
            })
        }
    }

    logger.info("MCP server configured with 17 tools")
    return server
}

private inline fun <T> JsonObjectBuilder.putJsonArrayOf(
    key: String,
    items: List<T>,
    crossinline build: JsonObjectBuilder.(T) -> Unit,
) {
    put(key, JsonArray(items.map { item -> buildJsonObject { build(item) } }))
}
