package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class RenameResponse(
    val location: Location,
    val name: String,
)
