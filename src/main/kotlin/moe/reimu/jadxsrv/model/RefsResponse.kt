package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class RefsResponse(val refs: List<Location> = emptyList())
