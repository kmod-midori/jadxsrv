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
import jadx.api.JavaVariable
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.api.metadata.annotations.VarNode
import jadx.core.deobf.NameMapper
import jadx.core.dex.attributes.AType
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
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

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

    /**
     * Locates a method, accepting a display/raw name, or a full shortId/fullId
     * signature in either `method_name` or `method_id`; throws with guidance
     * when the name is unknown or overloads remain ambiguous.
     */
    fun findMethod(cls: JavaClass, methodName: String, methodId: String?): JavaMethod {
        fun JavaMethod.signatureMatches(signature: String): Boolean =
            methodNode.methodInfo.shortId == signature || methodNode.methodInfo.fullId == signature

        var candidates = cls.methods.filter {
            it.name == methodName || it.methodNode.methodInfo.name == methodName
        }
        if (candidates.isEmpty()) {
            // LLM clients often pass the full "name(args)ret" suggested by the
            // ambiguity error as method_name — match it as a signature. (A name
            // can never accidentally equal a signature: names are Java
            // identifiers, signatures always contain '('.)
            candidates = cls.methods.filter { it.signatureMatches(methodName) }
        }
        if (methodId != null && candidates.size != 1) {
            candidates = candidates.filter { it.signatureMatches(methodId) }
        }
        return when (candidates.size) {
            1 -> candidates.first()
            0 -> throw IllegalArgumentException("Method not found: $methodName in ${cls.fullName}")
            else -> throw IllegalArgumentException(
                "Ambiguous method name '$methodName'; pass method_id, one of: " +
                    candidates.joinToString(", ") { it.methodNode.methodInfo.shortId },
            )
        }
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

    server.addTool(
        name = "get_method_by_name",
        description = "Get the decompiled source of a single method, extracted from the enclosing class. On overloaded names pass method_id to pick one.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "method_name" to strProp("Method name (display or raw), or a full name(args)ret signature"),
                "method_id" to strProp("Optional method signature shortId to disambiguate overloads"),
            ),
            listOf("class_name", "method_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val mth = findMethod(
                cls,
                args.str("method_name") ?: throw IllegalArgumentException("Missing required argument: method_name"),
                args.str("method_id"),
            )
            textResult(
                extractMethodCode(mth)
                    ?: throw IllegalStateException(
                        "Method is not present in the decompiled output (empty constructors and inlined/replaced methods are not generated)",
                    ),
            )
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
        description = "Full-text search in decompiled class code. EXPENSIVE LAST RESORT: it decompiles every class on demand and can take very long. Avoid unless absolutely necessary — prefer search_classes/search_methods to locate a symbol by name, then get_xrefs_to_class/method/field to see where it is used. Use search_code only when you have neither a class/method name to search for nor a known symbol in the code path you want to find.",
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
                "method_name" to strProp("Method name (display or raw), or a full name(args)ret signature"),
                "method_id" to strProp("Optional method signature shortId, e.g. onCreate(Landroid/os/Bundle;)V, to disambiguate overloads"),
                "limit" to intProp("Max usages (default $DEFAULT_LIMIT)"),
            ),
            listOf("class_name", "method_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val mth = findMethod(
                cls,
                args.str("method_name") ?: throw IllegalArgumentException("Missing required argument: method_name"),
                args.str("method_id"),
            )
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

    // --- Manifest (parsed views of AndroidManifest.xml) ----------------------

    val componentTypes = listOf("activity", "activity-alias", "service", "receiver", "provider")

    /** Returns the package attribute and root element of the decoded manifest. */
    fun parseManifest(): Pair<String, Element> {
        val xml = loadTextResource("AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val root = factory.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        return root.getAttribute("package") to root
    }

    fun Element.androidAttr(name: String): String? =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
            .ifEmpty { getAttribute("android:$name") }
            .ifEmpty { null }

    fun Element.directChildren(tagName: String): List<Element> =
        childNodes.asList().filterIsInstance<Element>().filter { it.tagName == tagName }

    fun componentElements(root: Element, componentType: String): List<Element> =
        root.getElementsByTagName(componentType).asList().filterIsInstance<Element>()

    /** Resolves a manifest class attribute against the manifest package. */
    fun resolveComponentName(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        name.contains(".") -> name
        else -> "$pkg.$name"
    }

    fun appClasses(pkg: String): List<JavaClass> {
        val prefix = "$pkg."
        return decompiler.jadx.classesWithInners.filter {
            it.classNode.classInfo.rawName.startsWith(prefix) ||
                it.classNode.classInfo.aliasFullName.startsWith(prefix)
        }
    }

    server.addTool(
        name = "get_manifest_component",
        description = "List components of one type from the AndroidManifest with their exported state. exported is null when the attribute is absent; such components count as exported when they declare an intent-filter.",
        inputSchema = schema(
            mapOf(
                "component_type" to strProp("One of: ${componentTypes.joinToString(", ")}"),
                "only_exported" to boolProp("Only include exported components (default false)"),
            ),
            listOf("component_type"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val componentType = args.str("component_type")
                ?: throw IllegalArgumentException("Missing required argument: component_type")
            if (componentType !in componentTypes) {
                throw IllegalArgumentException(
                    "Unknown component_type '$componentType', expected one of: ${componentTypes.joinToString(", ")}",
                )
            }
            val onlyExported = args.bool("only_exported") ?: false
            val (pkg, root) = parseManifest()

            data class Component(
                val name: String,
                val className: String,
                val exported: Boolean?,
                val effectivelyExported: Boolean,
            )

            val components = componentElements(root, componentType).map { el ->
                val rawName = el.androidAttr("name") ?: "<missing-name>"
                val targetName = if (componentType == "activity-alias") {
                    el.androidAttr("targetActivity") ?: rawName
                } else {
                    rawName
                }
                val exportedAttr = el.androidAttr("exported")?.let { it == "true" || it == "1" }
                val hasIntentFilter = el.directChildren("intent-filter").isNotEmpty()
                Component(
                    name = rawName,
                    className = resolveComponentName(pkg, targetName),
                    exported = exportedAttr,
                    effectivelyExported = exportedAttr ?: hasIntentFilter,
                )
            }.filter { !onlyExported || it.effectivelyExported }

            jsonResult(buildJsonObject {
                put("package", pkg)
                put("componentType", componentType)
                putJsonArrayOf("components", components) { c ->
                    put("name", c.name)
                    put("className", c.className)
                    c.exported?.let { put("exported", it) }
                    put("effectivelyExported", c.effectivelyExported)
                }
            })
        }
    }

    server.addTool(
        name = "get_main_activity_class",
        description = "Get the app's launcher activity: the activity (or activity-alias target) with an intent-filter for android.intent.action.MAIN + android.intent.category.LAUNCHER.",
        inputSchema = schema(emptyMap()),
    ) {
        runTool {
            val (pkg, root) = parseManifest()

            fun isLauncher(el: Element): Boolean = el.directChildren("intent-filter").any { filter ->
                filter.directChildren("action").any { it.androidAttr("name") == "android.intent.action.MAIN" } &&
                    filter.directChildren("category").any { it.androidAttr("name") == "android.intent.category.LAUNCHER" }
            }

            for (type in listOf("activity", "activity-alias")) {
                for (el in componentElements(root, type)) {
                    if (!isLauncher(el)) continue
                    val rawName = (if (type == "activity-alias") el.androidAttr("targetActivity") else null)
                        ?: el.androidAttr("name")
                    val className = rawName?.let { resolveComponentName(pkg, it) }
                    return@runTool jsonResult(buildJsonObject {
                        put("className", className)
                        put("declaredAs", type)
                        put("existsInDex", className?.let { findClass(it) != null } ?: false)
                    })
                }
            }
            jsonResult(buildJsonObject {
                put("className", null as String?)
                put("message", "No launcher activity found in the manifest")
            })
        }
    }

    server.addTool(
        name = "get_main_application_classes_names",
        description = "List classes that belong to the app's own package (from the AndroidManifest package attribute), excluding library classes. Paginated.",
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
            val (pkg, _) = parseManifest()
            val classes = appClasses(pkg)
            val page = classes.asSequence().drop(offset).let { if (count > 0) it.take(count) else it }.toList()
            jsonResult(buildJsonObject {
                put("package", pkg)
                put("total", classes.size)
                putJsonArrayOf("classes", page) { cls ->
                    put("className", cls.classNode.classInfo.aliasFullName)
                    put("rawClassName", cls.classNode.classInfo.rawName)
                }
            })
        }
    }

    server.addTool(
        name = "get_main_application_classes_code",
        description = "Get the decompiled source of classes in the app's own package. Paginated (defaults to 5 classes per call).",
        inputSchema = schema(
            mapOf(
                "offset" to intProp("Number of classes to skip (default 0)"),
                "count" to intProp("Max classes to return (default 5)"),
            ),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
            val count = (args.int("count") ?: 5).coerceAtLeast(1)
            val (pkg, _) = parseManifest()
            val classes = appClasses(pkg)
            val page = classes.asSequence().drop(offset).take(count).toList()
            jsonResult(buildJsonObject {
                put("package", pkg)
                put("total", classes.size)
                putJsonArrayOf("classes", page) { cls ->
                    put("className", cls.classNode.classInfo.aliasFullName)
                    put("code", cls.code)
                }
            })
        }
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
                "method_name" to strProp("Current method name (display or raw), or a full name(args)ret signature"),
                "method_id" to strProp("Optional signature shortId to disambiguate overloads"),
                "new_name" to strProp("New method name (valid Java identifier), or empty to reset"),
            ),
            listOf("class_name", "method_name", "new_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val mth = findMethod(
                cls,
                args.str("method_name") ?: throw IllegalArgumentException("Missing required argument: method_name"),
                args.str("method_id"),
            )
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

    server.addTool(
        name = "rename_variable",
        description = "Rename a local variable or method argument, scoped to a single SSA variable within its method (matches jadx-gui's variable rename). Pass reg/ssa to disambiguate variables sharing a name, and an empty new_name to reset.",
        inputSchema = schema(
            mapOf(
                classNameProp,
                "method_name" to strProp("Method name (display or raw), or a full name(args)ret signature"),
                "method_id" to strProp("Optional method signature shortId to disambiguate overloads"),
                "variable_name" to strProp("Current variable/argument name"),
                "new_name" to strProp("New variable name (valid Java identifier), or empty to reset"),
                "reg" to intProp("Optional register number to disambiguate same-named variables"),
                "ssa" to intProp("Optional SSA version to disambiguate same-named variables"),
            ),
            listOf("class_name", "method_name", "variable_name", "new_name"),
        ),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val cls = requireClass(args)
            val mth = findMethod(
                cls,
                args.str("method_name") ?: throw IllegalArgumentException("Missing required argument: method_name"),
                args.str("method_id"),
            )
            val variableName = args.str("variable_name")
                ?: throw IllegalArgumentException("Missing required argument: variable_name")
            val reg = args.int("reg")
            val ssa = args.int("ssa")

            // Variables surface as VarNode entries in the top-level class'
            // code metadata (same lookup as the REST route's fresh-name scan).
            val top = mth.topParentClass
            top.codeInfo
            val vars = mutableMapOf<Pair<Int, Int>, JavaVariable>()
            for ((_, annotation) in top.codeInfo.codeMetadata.asMap) {
                var node = annotation
                if (node is NodeDeclareRef) {
                    node = node.node
                }
                val varNode = node as? VarNode ?: continue
                if (varNode.mth !== mth.methodNode || varNode.name != variableName) continue
                if (reg != null && varNode.reg != reg) continue
                if (ssa != null && varNode.ssa != ssa) continue
                vars[varNode.reg to varNode.ssa] = JavaVariable(mth, varNode)
            }

            val javaVar = when (vars.size) {
                1 -> vars.values.single()
                0 -> throw IllegalArgumentException("Variable not found: $variableName in ${mth.name}")
                else -> throw IllegalArgumentException(
                    "Ambiguous variable '$variableName'; pass reg/ssa, one of: " +
                        vars.values.joinToString(", ") { "(reg=${it.reg}, ssa=${it.ssa})" },
                )
            }

            val newName = validateNewName(args)
            val applied = renameVariable(decompiler, javaVar, newName)
            jsonResult(buildJsonObject {
                put("className", cls.classNode.classInfo.rawName)
                put("method", mth.methodNode.methodInfo.shortId)
                put("reg", javaVar.reg)
                put("ssa", javaVar.ssa)
                put("name", applied)
            })
        }
    }

    logger.info("MCP server configured with 23 tools")
    return server
}

private fun NodeList.asList(): List<Node> = (0 until length).map(::item)

/**
 * Extracts one method's source from the decompiled code of its top-level
 * class using jadx's code metadata annotations: scan down from the method's
 * defPos, tracking DECLARATION/END annotation nesting, until the method's own
 * END closes it. Unlike text-based
 * annotation nesting, until the method's own END closes it. Unlike text-based
 * brace matching, this is immune to braces inside strings and comments, and it
 * also covers bodiless (abstract/native) methods which end after the
 * declaration.
 */
internal fun extractMethodCode(method: JavaMethod): String? {
    var mth = method
    if (mth.methodNode.contains(AType.METHOD_REPLACE)) {
        val replaced = mth.methodNode.get(AType.METHOD_REPLACE)
        if (replaced != null) {
            mth = replaced.replaceMth.javaNode
        }
    }

    // Access codeInfo (not just code): forces codegen for classes processed as
    // dependencies — otherwise defPos stays 0 (see outline route).
    val codeInfo = mth.topParentClass.codeInfo
    val codeStr = codeInfo.codeStr
    val codeMeta = codeInfo.codeMetadata

    val startPos = mth.defPos
    if (startPos <= 0) {
        return null
    }

    var nesting = 0
    val endPos = codeMeta.searchDown(startPos) { pos, annotation ->
        when (annotation.annType) {
            ICodeAnnotation.AnnType.END -> {
                nesting--
                if (nesting == 0) {
                    return@searchDown pos
                }
            }

            ICodeAnnotation.AnnType.DECLARATION -> {
                val node = (annotation as NodeDeclareRef).node
                if (node.annType == ICodeAnnotation.AnnType.CLASS || node.annType == ICodeAnnotation.AnnType.METHOD) {
                    nesting++
                }
            }

            else -> {}
        }
        null
    } ?: return null

    val lines = codeStr.split("\n")
    val startLine = codeStr.substring(0, startPos).count { it == '\n' }
    val endLine = codeStr.substring(0, endPos).count { it == '\n' }
    return dedentText(lines.subList(startLine, endLine + 1).joinToString("\n"))
}

private fun dedentText(text: String): String {
    val lines = text.lines()
    if (lines.isEmpty()) {
        return ""
    }
    val indent = lines.first().takeWhile { it.isWhitespace() }
    return lines.joinToString("\n") { it.removePrefix(indent) }
}

private inline fun <T> JsonObjectBuilder.putJsonArrayOf(
    key: String,
    items: List<T>,
    crossinline build: JsonObjectBuilder.(T) -> Unit,
) {
    put(key, JsonArray(items.map { item -> buildJsonObject { build(item) } }))
}
