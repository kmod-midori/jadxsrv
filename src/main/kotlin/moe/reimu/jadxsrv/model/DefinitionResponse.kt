package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class DefinitionResponse(val def: Location? = null)
