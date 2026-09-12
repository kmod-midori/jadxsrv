package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyItem(
    val name: String,
    val detail: String,
    val kind: Int,
    val location: Location,
    /** Byte offset of the declaration in the location's file */
    val offset: Int,
)

@Serializable
data class CallHierarchyCall(
    val item: CallHierarchyItem,
    /** For incoming calls: byte offsets of the call sites in the caller's
     * file. For outgoing calls: byte offsets of the call sites in the queried
     * method's file. */
    val callOffsets: List<Int>,
)
