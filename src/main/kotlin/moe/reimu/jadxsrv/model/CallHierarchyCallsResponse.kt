package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyCallsResponse(
    val calls: List<CallHierarchyCall> = emptyList(),
)
