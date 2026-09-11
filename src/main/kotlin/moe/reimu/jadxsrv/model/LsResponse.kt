package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class LsResponse(
    val dirs: List<String> = emptyList(),
    val files: List<String> = emptyList(),
)
