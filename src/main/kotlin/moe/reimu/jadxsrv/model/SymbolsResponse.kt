package moe.reimu.jadxsrv.model

import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode
import kotlinx.serialization.Serializable
import moe.reimu.jadxsrv.charOffsetToPosition
import moe.reimu.jadxsrv.formatClass
import moe.reimu.jadxsrv.formatField
import moe.reimu.jadxsrv.formatMethod

@Serializable
data class SymbolsResponse(val symbols: List<Symbol> = emptyList()) {
    @Serializable
    data class Symbol(
        val name: String,
        val containerName: String,
        val kind: Int,
        val detail: String,
        val location: Location,
    ) {
        constructor(classNode: ClassNode) : this(
            name = classNode.alias,
            containerName = if (classNode.packageNode.hasAlias()) {
                classNode.packageNode.aliasPkgInfo.fullName
            } else {
                classNode.packageNode.pkgInfo.fullName
            },
            kind = if (classNode.accessFlags.isInterface) TYPE_INTERFACE else TYPE_CLASS,
            location = Location(classNode.topParentClass),
            detail = formatClass(classNode),
        )

        constructor(methodNode: MethodNode) : this(
            name = methodNode.alias,
            containerName = methodNode.parentClass.fullName,
            kind = TYPE_METHOD,
            location = Location(
                methodNode.topParentClass,
                methodNode.topParentClass.code.codeStr.charOffsetToPosition(methodNode.defPosition)
            ),
            detail = formatMethod(methodNode),
        )

        constructor(fieldNode: FieldNode) : this(
            name = fieldNode.alias,
            containerName = fieldNode.parentClass.fullName,
            kind = TYPE_FIELD,
            location = Location(
                fieldNode.topParentClass,
                fieldNode.topParentClass.code.codeStr.charOffsetToPosition(fieldNode.defPosition)
            ),
            detail = formatField(fieldNode, false),
        )

        companion object {
            const val TYPE_FILE = 0
            const val TYPE_CLASS = 4
            const val TYPE_METHOD = 5
            const val TYPE_FIELD = 7
            const val TYPE_INTERFACE = 10

            fun fromOffset(classNode: ClassNode, offsetInTop: Int): Symbol {
                val source = classNode.topParentClass.code.codeStr
                val position = source.charOffsetToPosition(offsetInTop)
                return Symbol(
                    name = classNode.alias,
                    containerName = classNode.classInfo.aliasFullName,
                    kind = TYPE_FILE,
                    location = Location(
                        classNode.topParentClass,
                        position
                    ),
                    detail = if (position != null) {
                        source.lines()[position.line].trim()
                    } else {
                        ""
                    }
                )
            }
        }
    }
}