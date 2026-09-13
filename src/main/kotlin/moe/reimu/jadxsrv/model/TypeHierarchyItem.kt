package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class TypeHierarchyItem(
    val name: String,
    val detail: String,
    val kind: Int,
    val location: Location,
    /** Byte offset of the class declaration in the location's file */
    val offset: Int,
)

@Serializable
data class TypeHierarchyPrepareResponse(val item: TypeHierarchyItem? = null)

@Serializable
data class TypeHierarchyResponse(val items: List<TypeHierarchyItem> = emptyList())
