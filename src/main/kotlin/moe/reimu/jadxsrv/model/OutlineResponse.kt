package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class OutlineResponse(val root: Symbol? = null) {
    @Serializable
    data class Symbol(
        val name: String,
        val detail: String,
        val kind: Int,
        val byteOffset: Int? = null,
        val children: List<Symbol> = emptyList(),
    ) {
        companion object {
            const val TYPE_CLASS = 4
            const val TYPE_METHOD = 5
            const val TYPE_FIELD = 7
            const val TYPE_CONSTRUCTOR = 8
            const val TYPE_INTERFACE = 10
        }
    }
}