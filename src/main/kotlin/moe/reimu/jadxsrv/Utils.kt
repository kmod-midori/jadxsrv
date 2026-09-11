package moe.reimu.jadxsrv

import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.RoutingRequest
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.InsnCodeOffset
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.api.plugins.input.data.attributes.JadxAttrType
import jadx.core.dex.attributes.nodes.LineAttrNode
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.PackageNode
import moe.reimu.jadxsrv.model.DefinitionResponse
import moe.reimu.jadxsrv.model.Location
import moe.reimu.jadxsrv.model.Position
import kotlin.text.toIntOrNull

fun RoutingContext.getCleanPath(): List<String> {
    val path = call.parameters.getAll("path").orEmpty().toMutableList()
    if (path.firstOrNull() == "") {
        path.removeFirst()
    }
    if (path.lastOrNull() == "") {
        path.removeLast()
    }
    return path
}


fun RoutingContext.getOffsetInt() = call.queryParameters["offset"]!!.toInt()

fun resolveDefinition(annotation: ICodeAnnotation): DefinitionResponse {
    val tpc = when (annotation) {
        is ClassNode -> annotation.topParentClass
        is MethodNode -> annotation.topParentClass
        is FieldNode -> annotation.topParentClass
        is InsnCodeOffset -> null
        is NodeDeclareRef -> null
        else -> {
            println(annotation)
            null
        }
    }
    if (tpc == null) {
        return DefinitionResponse()
    }

    tpc.decompile()

    return DefinitionResponse(
        def = Location(
            tpc,
            if (annotation is LineAttrNode) {
                tpc.code.codeStr.charOffsetToPosition(annotation.defPosition)
            } else {
                null
            }
        )
    )
}

fun formatPackage(pkg: PackageNode): String {
    return if (pkg.hasAlias()) {
        "~~`${pkg.pkgInfo.fullName}`~~ `${pkg.aliasPkgInfo.fullName}`"
    } else {
        "`${pkg.pkgInfo.fullName}`"
    }
}

fun formatClass(node: ClassNode): String = "${node.accessFlags.makeString(false)}class ${node.alias}"
fun formatMethod(method: MethodNode): String {
    val argNodes = method.collectArgNodes()
    val args = argNodes.joinToString(", ") { "${it.type} ${it.name}" }
    return "${method.accessFlags.makeString(false)}${method.returnType} ${method.parentClass.alias}.${method.alias}(${args})"
}
fun formatField(field: FieldNode, withValue: Boolean): String {
    val constantValue = field.get(JadxAttrType.CONSTANT_VALUE)
    val suffix = if (constantValue != null && withValue) {
        " = $constantValue"
    } else {
        ""
    }
    return "${field.accessFlags.makeString(false)}${field.type} ${field.parentClass.alias}.${field.alias}$suffix"
}

fun formatHover(annotation: ICodeAnnotation): String? {
    var node = annotation
    if (node is NodeDeclareRef) {
        node = node.node
    }
    when (node) {
        is ClassNode -> {
            return """
                ${formatPackage(node.packageNode)}
                
                ```java
                ${formatClass(node)}
                ```
            """.trimIndent()
        }

        is MethodNode -> {
            return """
                ${formatPackage(node.parentClass.packageNode)}
                
                ```java
                ${formatMethod(node)}
                ```
            """.trimIndent()
        }

        is FieldNode -> {
            return """
                ${formatPackage(node.parentClass.packageNode)}
                
                ```java
                ${formatField(node, true)}
                ```
            """.trimIndent()
        }

        else -> {
            println(node)
            return null
        }
    }
}

fun String.charOffsetToPosition(offset: Int): Position? {
    if (offset < 0 || offset > this.length) {
        return null
    }

    var line = 0
    var column = 0

    for (i in 0 until offset) {
        if (this[i] == '\n') {
            line++
            column = 0
        } else {
            column++
        }
    }

    return Position(line, column)
}