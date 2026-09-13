package moe.reimu.jadxsrv

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import jadx.api.JavaClass
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.core.dex.nodes.ClassNode
import jadx.core.xmlgen.ResContainer
import moe.reimu.jadxsrv.model.Location
import moe.reimu.jadxsrv.model.OutlineResponse
import moe.reimu.jadxsrv.model.TypeHierarchyItem
import moe.reimu.jadxsrv.model.TypeHierarchyPrepareResponse
import moe.reimu.jadxsrv.model.TypeHierarchyResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("jadxsrv.typehierarchy")

/**
 * Class type hierarchy. Super- and subtypes are reported one level at a time;
 * the editor walks the tree by re-querying with each item's location + offset.
 *
 * Classes outside the decompiled inputs (java.lang.Object, library types) are
 * not loaded into jadx's class map, so resolveClass returns null for them and
 * they silently drop out of the supertype list.
 */
@Suppress("UnstableApiUsage")
fun Route.typeHierarchyRoutes(decompiler: Decompiler) {
    get("/typehierarchy/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val cls = resolveClassAt(decompiler, pathParts, offset)
        call.respond(TypeHierarchyPrepareResponse(cls?.let { classToItem(decompiler, it) }))
    }
    // Prepare works from XML resources too (e.g. a class name in
    // AndroidManifest.xml). Supertypes/subtypes always navigate inside the
    // classes tree, so they only need the /classes variant.
    get("/typehierarchy/resources/{path...}") {
        val offset = getOffsetInt()
        val loaded = resolveResource(decompiler)
        if (loaded == null || (loaded.dataType != ResContainer.DataType.TEXT && loaded.dataType != ResContainer.DataType.RES_TABLE)) {
            call.respond(TypeHierarchyPrepareResponse())
            return@get
        }
        var annotation = loaded.text.codeMetadata.getAt(offset)
        if (annotation is NodeDeclareRef) {
            annotation = annotation.node
        }
        val cls = annotation as? ClassNode
        call.respond(TypeHierarchyPrepareResponse(cls?.let { classToItem(decompiler, it) }))
    }
    get("/typehierarchy/supertypes/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val cls = resolveClassAt(decompiler, pathParts, offset)
        if (cls == null) {
            call.respond(TypeHierarchyResponse())
            return@get
        }
        call.respond(TypeHierarchyResponse(directSupertypes(decompiler, cls).mapNotNull { classToItem(decompiler, it) }))
    }
    get("/typehierarchy/subtypes/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val cls = resolveClassAt(decompiler, pathParts, offset)
        if (cls == null) {
            call.respond(TypeHierarchyResponse())
            return@get
        }
        call.respond(TypeHierarchyResponse(directSubtypes(decompiler, cls).mapNotNull { classToItem(decompiler, it) }))
    }
}

private fun resolveClassAt(decompiler: Decompiler, pathParts: List<String>, offset: Int): ClassNode? {
    val resolved = resolvePath(decompiler.jadx, pathParts)
    if (resolved !is JavaClass) {
        return null
    }
    resolved.decompile()
    var annotation = resolved.codeInfo.codeMetadata.getAt(offset) ?: return null
    if (annotation is NodeDeclareRef) {
        annotation = annotation.node
    }
    return annotation as? ClassNode
}

private fun directSupertypes(decompiler: Decompiler, cls: ClassNode): List<ClassNode> {
    val root = decompiler.jadx.root
    val result = LinkedHashMap<String, ClassNode>()
    cls.superClass?.let { root.resolveClass(it) }?.let { result[it.classInfo.rawName] = it }
    for (iface in cls.interfaces) {
        root.resolveClass(iface)?.let { result.putIfAbsent(it.classInfo.rawName, it) }
    }
    return result.values.toList()
}

private fun directSubtypes(decompiler: Decompiler, cls: ClassNode): List<ClassNode> {
    val rawName = cls.classInfo.rawName
    return decompiler.jadx.root.classes.filter { candidate ->
        candidate.classInfo.rawName != rawName &&
            (candidate.superClass?.`object` == rawName || candidate.interfaces.any { it.`object` == rawName })
    }
}

private fun classToItem(decompiler: Decompiler, cls: ClassNode): TypeHierarchyItem? {
    val topCls = decompiler.jadx.getJavaNodeByRef(cls.topParentClass) as? JavaClass ?: return null
    // defPosition is 0 until the class' code is generated; access codeInfo to
    // force codegen — JavaClass.decompile() alone skips classes that were
    // already processed as dependencies of another class' codegen.
    if (!topCls.tryDecompile() || cls.defPosition <= 0) {
        return null
    }
    return TypeHierarchyItem(
        name = cls.alias,
        detail = cls.`package`,
        kind = if (cls.accessFlags.isInterface) {
            OutlineResponse.Symbol.TYPE_INTERFACE
        } else {
            OutlineResponse.Symbol.TYPE_CLASS
        },
        location = Location(cls.topParentClass),
        offset = cls.defPosition,
    )
}

private fun JavaClass.tryDecompile(): Boolean {
    return try {
        codeInfo
        true
    } catch (e: Exception) {
        logger.warn("Failed to decompile {} for type hierarchy", fullName, e)
        false
    }
}
