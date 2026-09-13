package moe.reimu.jadxsrv

import jadx.api.JavaNode
import jadx.api.data.impl.JadxCodeRename
import jadx.api.data.impl.JadxNodeRef
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("jadxsrv.rename")

/**
 * Applies a rename (user alias) to code data under the decompiler lock,
 * mirrors the jadx-gui reload flow, and persists when --code-data is set.
 * Shared by the REST /rename route and the MCP rename tools.
 */
fun applyRename(decompiler: Decompiler, rename: JadxCodeRename, node: JavaNode) {
    val newName = rename.newName
    synchronized(decompiler) {
        val renames = decompiler.codeData.renames.toMutableSet()
        renames.remove(rename)
        if (newName.isEmpty()) {
            node.removeAlias()
        } else {
            renames.add(rename)
        }
        decompiler.codeData.renames = renames.sorted()
        decompiler.jadx.args.codeData = decompiler.codeData
        decompiler.jadx.reloadCodeData()

        decompiler.codeDataPath?.let { path ->
            try {
                saveCodeData(path, decompiler.codeData)
            } catch (e: Exception) {
                // Don't fail the rename over a failed save; log loudly instead.
                logger.error("Failed to save code data to {}", path, e)
            }
        }
    }
}

/**
 * Renames a class, method, or field node by a direct node reference (as
 * opposed to the REST route's offset-based resolution), then invalidates
 * decompiled code for every affected class. Returns the node's fresh display
 * name after the reload.
 */
fun renameNode(decompiler: Decompiler, node: JavaNode, newName: String): String {
    val nodeRef = JadxNodeRef.forJavaNode(node)
        ?: throw IllegalArgumentException("This symbol does not support renaming")
    applyRename(decompiler, JadxCodeRename(nodeRef, newName), node)

    val freshNode = decompiler.jadx.getJavaNodeByRef(node.codeNodeRef)
        ?: throw IllegalStateException("Failed to resolve renamed symbol")
    refreshAffectedClasses(freshNode)
    return freshNode.name
}
