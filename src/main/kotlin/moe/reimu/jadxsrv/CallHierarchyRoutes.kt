package moe.reimu.jadxsrv

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import jadx.api.JavaClass
import jadx.api.JavaMethod
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.core.dex.info.MethodInfo
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import moe.reimu.jadxsrv.model.CallHierarchyCall
import moe.reimu.jadxsrv.model.CallHierarchyCallsResponse
import moe.reimu.jadxsrv.model.CallHierarchyItem
import moe.reimu.jadxsrv.model.CallHierarchyPrepareResponse
import moe.reimu.jadxsrv.model.Location
import moe.reimu.jadxsrv.model.OutlineResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("jadxsrv.callhierarchy")

/**
 * Call hierarchy, mirroring jadx-gui's UsageDialogPlus: a call to any
 * override-related method counts as a call to the queried one.
 *
 * Method identity is compared by source key (class + name + parameter types,
 * no return type), never by instance: jadx may attach a bridge method's
 * MethodNode to a merged source method's declaration, so load-time references
 * (useIn) and code metadata can hold different instances of the same source
 * method.
 */
@Suppress("UnstableApiUsage")
fun Route.callHierarchyRoutes(decompiler: Decompiler) {
    get("/callhierarchy/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val mth = resolveMethodAt(decompiler, pathParts, offset)
        call.respond(CallHierarchyPrepareResponse(mth?.let { methodToItem(it.methodNode, it.topParentClass) }))
    }
    get("/callhierarchy/incoming/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val mth = resolveMethodAt(decompiler, pathParts, offset)
        if (mth == null) {
            call.respond(CallHierarchyCallsResponse())
            return@get
        }

        val relatedMethods = (mth.overrideRelatedMethods + mth).distinctBy { it.methodNode.methodInfo }
        val callerMap = LinkedHashMap<String, CallerData>()
        for (relMth in relatedMethods) {
            val relKey = srcKey(relMth.methodNode.methodInfo)
            val callersByClass = relMth.useIn.filterIsInstance<JavaMethod>().groupBy { it.topParentClass }
            for ((topCls, callers) in callersByClass) {
                if (!topCls.tryDecompile()) {
                    continue
                }
                val wanted = callers.map { srcKey(it.methodNode.methodInfo) }.toSet()
                val metadata = topCls.codeInfo.codeMetadata
                for ((pos, ann) in metadata.asMap) {
                    if (ann.annType != ICodeAnnotation.AnnType.METHOD) {
                        continue
                    }
                    val callee = ann as MethodNode
                    if (srcKey(callee.methodInfo) != relKey) {
                        continue
                    }
                    // Call sites are reported per caller method, so attribute
                    // the position to its enclosing method.
                    val enclosing = metadata.getNodeAt(pos)
                    if (enclosing is MethodNode && srcKey(enclosing.methodInfo) in wanted) {
                        callerMap.getOrPut(srcKey(enclosing.methodInfo)) {
                            CallerData(enclosing, topCls, mutableListOf())
                        }.positions.add(pos)
                    }
                }
            }
        }
        val calls = callerMap.values.map {
            CallHierarchyCall(methodToItem(it.node, it.topCls), it.positions.distinct().sorted())
        }
        call.respond(CallHierarchyCallsResponse(calls))
    }
    get("/callhierarchy/outgoing/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val mth = resolveMethodAt(decompiler, pathParts, offset)
        if (mth == null) {
            call.respond(CallHierarchyCallsResponse())
            return@get
        }

        // There is no callee index in jadx, so scan the enclosing class' code
        // metadata for method annotations placed inside this method's body.
        val mthKey = srcKey(mth.methodNode.methodInfo)
        val topCls = mth.topParentClass
        val metadata = topCls.codeInfo.codeMetadata
        val calleeMap = LinkedHashMap<String, Pair<MethodInfo, MutableList<Int>>>()
        for ((pos, ann) in metadata.asMap) {
            if (ann.annType != ICodeAnnotation.AnnType.METHOD) {
                continue
            }
            val callee = ann as MethodNode
            val enclosing = metadata.getNodeAt(pos)
            if (enclosing is MethodNode && srcKey(enclosing.methodInfo) == mthKey) {
                val key = srcKey(callee.methodInfo)
                calleeMap.getOrPut(key) { callee.methodInfo to mutableListOf() }.second.add(pos)
            }
        }
        val calls = calleeMap.mapNotNull { (_, entry) ->
            val (calleeInfo, positions) = entry
            findDeclItem(decompiler, calleeInfo)?.let { CallHierarchyCall(it, positions.distinct().sorted()) }
        }
        call.respond(CallHierarchyCallsResponse(calls))
    }
}

private class CallerData(val node: MethodNode, val topCls: JavaClass, val positions: MutableList<Int>)

/**
 * Source-level method identity: declaring class + name + parameter types.
 * Bridge methods share these with the source method they forward to.
 */
private fun srcKey(mthInfo: MethodInfo): String {
    val shortId = mthInfo.shortId
    return mthInfo.declClass.rawName + '.' + shortId.substring(0, shortId.indexOf(')') + 1)
}

private fun resolveMethodAt(decompiler: Decompiler, pathParts: List<String>, offset: Int): JavaMethod? {
    val resolved = resolvePath(decompiler.jadx, pathParts)
    if (resolved !is JavaClass) {
        return null
    }
    resolved.decompile()
    return decompiler.jadx.getJavaNodeAtPosition(resolved.codeInfo, offset) as? JavaMethod
}

private fun JavaClass.tryDecompile(): Boolean {
    return try {
        decompile()
        true
    } catch (e: Exception) {
        logger.warn("Failed to decompile {} for call hierarchy", fullName, e)
        false
    }
}

private fun methodToItem(mth: MethodNode, topCls: JavaClass): CallHierarchyItem {
    return CallHierarchyItem(
        name = mth.alias,
        detail = mth.parentClass.fullName,
        kind = if (mth.methodInfo.isConstructor) {
            OutlineResponse.Symbol.TYPE_CONSTRUCTOR
        } else {
            OutlineResponse.Symbol.TYPE_METHOD
        },
        location = Location(topCls.classNode),
        offset = mth.defPosition,
    )
}

/**
 * Finds the declaration of a method in its top class' code. Needed because
 * call-site annotations can hold MethodNode instances whose defPosition was
 * never set (their class may not have been decompiled yet).
 */
private fun findDeclItem(decompiler: Decompiler, mthInfo: MethodInfo): CallHierarchyItem? {
    val clsNode: ClassNode = decompiler.jadx.root.resolveClass(mthInfo.declClass) ?: return null
    val topCls = decompiler.jadx.getJavaNodeByRef(clsNode.topParentClass) as? JavaClass ?: return null
    if (!topCls.tryDecompile()) {
        return null
    }
    val key = srcKey(mthInfo)
    for ((_, ann) in topCls.codeInfo.codeMetadata.asMap) {
        if (ann is NodeDeclareRef) {
            val node = ann.node
            if (node is MethodNode && srcKey(node.methodInfo) == key) {
                return methodToItem(node, topCls)
            }
        }
    }
    return null
}
