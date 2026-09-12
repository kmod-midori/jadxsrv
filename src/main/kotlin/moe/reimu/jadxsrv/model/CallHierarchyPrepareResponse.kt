package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyPrepareResponse(
    val item: CallHierarchyItem? = null,
)
