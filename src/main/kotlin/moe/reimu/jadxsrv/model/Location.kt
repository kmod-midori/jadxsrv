package moe.reimu.jadxsrv.model

import jadx.core.dex.nodes.ClassNode
import kotlinx.serialization.Serializable

@Serializable
data class Location(val topPackageName: String, val topClassName: String, val position: Position?) {
    constructor(containerClass: ClassNode, position: Position? = null) : this(
        topPackageName = containerClass.`package`,
        topClassName = containerClass.alias,
        position = position,
    )
}
