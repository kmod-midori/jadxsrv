package moe.reimu.jadxsrv

import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.request.receive
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaPackage
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.attributes.AType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import jadx.api.data.impl.JadxCodeRename
import jadx.api.data.impl.JadxNodeRef
import jadx.core.deobf.NameMapper
import moe.reimu.jadxsrv.model.AnnotationResponse
import moe.reimu.jadxsrv.model.DefinitionResponse
import moe.reimu.jadxsrv.model.Location
import moe.reimu.jadxsrv.model.LsResponse
import moe.reimu.jadxsrv.model.OutlineResponse
import moe.reimu.jadxsrv.model.RefsResponse
import moe.reimu.jadxsrv.model.RenameRequest
import moe.reimu.jadxsrv.model.RenameInfoResponse
import moe.reimu.jadxsrv.model.RenameResponse
import moe.reimu.jadxsrv.model.StatResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("jadxsrv.classes")

object RootPath

@Suppress("UnstableApiUsage")
fun Route.classesRoutes(decompiler: Decompiler) {

    route("/ls") {
        get("/classes") {
            val dirs = decompiler.jadx.packages.filter { it.pkgNode.parentPkg == null }.map { it.name }
            call.respond(LsResponse(dirs = dirs))
        }

        get("/classes/{path...}") {
            val pathParts = getCleanPath()
            val resolved = resolvePath(decompiler.jadx, pathParts)
            when (resolved) {
                is JavaPackage -> {
                    val dirs = resolved.subPackages.map { it.name }
                    val files = resolved.classes.mapNotNull {
                        if (it.classNode.contains(AType.INLINED)) {
                            null
                        } else {
                            "${it.name}.java"
                        }
                    }
                    call.respond(LsResponse(dirs = dirs, files = files))
                }
            }
        }
    }
    route("/stat") {
        get("/classes") {
            call.respond(StatResponse(type = StatResponse.TYPE_DIR))
        }

        get("/classes/{path...}") {
            val pathParts = getCleanPath()
            val resolved = resolvePath(decompiler.jadx, pathParts)
            when (resolved) {
                is RootPath -> {
                    call.respond(StatResponse(type = StatResponse.TYPE_DIR))
                }

                is JavaPackage -> {
                    call.respond(StatResponse(type = StatResponse.TYPE_DIR))
                }

                is JavaClass -> {
                    call.respond(StatResponse(type = StatResponse.TYPE_FILE))
                }
            }
        }
    }

    get("/read/classes/{path...}") {
        val pathParts = getCleanPath()
        val resolved = resolvePath(decompiler.jadx, pathParts)
        when (resolved) {
            is JavaClass -> {
                call.respondText(
                    contentType = ContentType.Application.OctetStream, text = resolved.code
                )
            }
        }
    }
    get("/annotation/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()

        val resolved = resolvePath(decompiler.jadx, pathParts)
        when (resolved) {
            is JavaClass -> {
                resolved.decompile()
                val codeInfo = resolved.codeInfo
                val annotation = codeInfo.codeMetadata.getAt(offset)
                if (annotation == null) {
                    call.respond(AnnotationResponse())
                    return@get
                }

                call.respond(AnnotationResponse(formatHover(annotation)))
            }
        }
    }
    get("/definition/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()

        val resolved = resolvePath(decompiler.jadx, pathParts)
        if (resolved !is JavaClass) {
            call.respond(DefinitionResponse())
            return@get
        }

        resolved.decompile()
        val codeInfo = resolved.codeInfo
        val annotation = codeInfo.codeMetadata.getAt(offset)
        if (annotation == null) {
            call.respond(DefinitionResponse())
            return@get
        }

        call.respond(resolveDefinition(annotation))
    }
    get("/outline/classes/{path...}") {
        val pathParts = getCleanPath()

        val resolved = resolvePath(decompiler.jadx, pathParts)
        if (resolved !is JavaClass) {
            call.respond(OutlineResponse())
            return@get
        }

        resolved.decompile()
        call.respond(OutlineResponse(classToSymbol(resolved.classNode)))
    }
    get("/rename/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val resolved = resolvePath(decompiler.jadx, pathParts)
        if (resolved !is JavaClass) {
            call.respond(RenameInfoResponse(canRename = false))
            return@get
        }

        val renameNode = resolveRenameNode(decompiler, resolved, offset)
        if (renameNode == null) {
            call.respond(RenameInfoResponse(canRename = false))
            return@get
        }
        call.respond(RenameInfoResponse(canRename = true, name = renameNode.name))
    }
    post("/rename/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()
        val request = call.receive<RenameRequest>()
        val resolved = resolvePath(decompiler.jadx, pathParts)
        if (resolved !is JavaClass) {
            call.respondText("Class not found", status = io.ktor.http.HttpStatusCode.NotFound)
            return@post
        }

        val renameNode = resolveRenameNode(decompiler, resolved, offset)
        if (renameNode == null) {
            call.respondText("No renameable symbol at offset", status = io.ktor.http.HttpStatusCode.BadRequest)
            return@post
        }
        val newName = request.name.trim()
        if (newName.isNotEmpty() && !NameMapper.isValidIdentifier(newName)) {
            call.respondText("Invalid Java identifier", status = io.ktor.http.HttpStatusCode.BadRequest)
            return@post
        }

        synchronized(decompiler) {
            val renames = decompiler.codeData.renames.toMutableSet()
            val rename = JadxCodeRename(JadxNodeRef.forJavaNode(renameNode), newName)
            renames.remove(rename)
            if (newName.isEmpty()) {
                renameNode.removeAlias()
            } else {
                renames.add(rename)
            }
            decompiler.codeData.renames = renames.sorted()
            decompiler.jadx.args.codeData = decompiler.codeData
            decompiler.jadx.reloadCodeData()
        }

        val renamedNode = decompiler.jadx.getJavaNodeByRef(renameNode.codeNodeRef)
            ?: throw IllegalStateException("Failed to resolve renamed symbol")
        renamedNode.topParentClass.reload()
        call.respond(RenameResponse(Location(renamedNode.topParentClass.classNode), renamedNode.name))
    }
    get("refs/classes/{path...}") {
        val pathParts = getCleanPath()
        val offset = getOffsetInt()

        val resolved = resolvePath(decompiler.jadx, pathParts)
        if (resolved !is JavaClass) {
            call.respond(RefsResponse())
            return@get
        }

        val codeInfo = resolved.codeInfo
        val annotation = codeInfo.codeMetadata.getAt(offset)
        if (annotation == null) {
            call.respond(RefsResponse())
            return@get
        }

        var node = annotation
        if (node is NodeDeclareRef) {
            node = node.node
        }

        val methodUsages = when (node) {
            is ClassNode -> {
                node.useInMth
            }

            is MethodNode -> {
                node.useIn
            }

            is FieldNode -> {
                node.useIn
            }

            else -> {
                emptyList()
            }
        }.map { it.topParentClass }
        val classUsages = if (node is ClassNode) {
            node.useIn.map { it.topParentClass }
        } else emptyList()

        val refs = mutableListOf<Location>()
        for (parentClass in methodUsages + classUsages) {
            val elements = findUsage(parentClass, node).map {
                Location(parentClass, parentClass.code.codeStr.charOffsetToPosition(it))
            }
            refs.addAll(elements)
        }

        call.respond(RefsResponse(refs))
    }
}

private fun JavaNode.replaceConstructor(): JavaNode {
    return if (this is JavaMethod && this.isConstructor) this.declaringClass else this
}

private fun resolveRenameNode(decompiler: Decompiler, cls: JavaClass, offset: Int): JavaNode? {
    cls.decompile()
    val node = decompiler.jadx.getJavaNodeAtPosition(cls.codeInfo, offset) ?: return null
    if (!node.canRename()) {
        return null
    }
    return node.replaceConstructor()
}

private fun JavaNode.canRename(): Boolean {
    return when (this) {
        is JavaClass -> !classNode.contains(AFlag.DONT_RENAME)
        is JavaField -> !fieldNode.contains(AFlag.DONT_RENAME)
        is JavaMethod -> !isClassInit && !methodNode.contains(AFlag.DONT_RENAME)
        else -> false
    }
}

fun resolvePath(decompiler: JadxDecompiler, pathParts: List<String>): Any? {
    if (pathParts.isEmpty()) {
        return RootPath
    }

    val packages = decompiler.packages

    if (pathParts.size == 1) {
        if (pathParts[0] == "") {
            return RootPath
        }
        return packages.find { it.fullName == pathParts[0] }
    }

    val pathDotMinus1 = pathParts.dropLast(1).joinToString(".")
    val pathLast = pathParts.last()

    val currentPackage = packages.find { it.fullName == pathDotMinus1 }
    if (currentPackage == null) {
        return null
    }

    val subPackage = currentPackage.subPackages.find { it.name == pathLast }
    if (subPackage != null) {
        return subPackage
    }

    if (pathLast.endsWith(".java")) {
        val className = pathLast.removeSuffix(".java")
        val cls = currentPackage.classes.find { it.name == className }
        if (cls != null) {
            return cls
        }
    }

    if (pathLast.endsWith(".smali")) {
        val className = pathLast.removeSuffix(".smali")
        val cls = currentPackage.classes.find { it.name == className }
        if (cls != null) {
            return cls
        }
    }

    return null
}

fun classToSymbol(cls: ClassNode): OutlineResponse.Symbol {
    val directChildren = mutableListOf<OutlineResponse.Symbol>()

    for (mth in cls.methods) {
        val inlineAttr = mth.get(AType.METHOD_INLINE)
        if (inlineAttr != null && !inlineAttr.notNeeded()) {
            // Inlined method, skip
            continue
        }
        if (mth.contains(AFlag.DONT_GENERATE)) {
            continue // Does not exist in decompiled code
        }
        directChildren.add(
            OutlineResponse.Symbol(
                name = mth.alias,
                detail = mth.returnType.toString(),
                kind = OutlineResponse.Symbol.TYPE_METHOD,
                byteOffset = mth.defPosition
            )
        )
    }

    for (field in cls.fields) {
        directChildren.add(
            OutlineResponse.Symbol(
                name = field.alias,
                detail = field.type.toString(),
                kind = OutlineResponse.Symbol.TYPE_FIELD,
                byteOffset = field.defPosition
            )
        )
    }

    for (innerClass in cls.innerClasses) {
        if (innerClass.contains(AType.ANONYMOUS_CLASS)) {
            continue
        }
        directChildren.add(
            classToSymbol(innerClass)
        )
    }

    return OutlineResponse.Symbol(
        name = cls.alias,
        detail = "",
        kind = if (cls.accessFlags.isInterface) {
            OutlineResponse.Symbol.TYPE_INTERFACE
        } else {
            OutlineResponse.Symbol.TYPE_CLASS
        },
        byteOffset = cls.defPosition,
        children = directChildren
    )
}

fun findUsage(classNode: ClassNode, target: ICodeAnnotation): List<Int> {
    val metadata = classNode.code.codeMetadata
    val ret = mutableListOf<Int>()
    for ((pos, anno) in metadata.asMap) {
        if (anno == target) {
            ret.add(pos)
        }
    }
    return ret
}
