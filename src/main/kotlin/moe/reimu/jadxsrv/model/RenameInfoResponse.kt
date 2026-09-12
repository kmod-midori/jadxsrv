package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class RenameInfoResponse(
    val canRename: Boolean,
    val name: String? = null,
)
