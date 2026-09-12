package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class RenameResponse(
    val location: Location,
    val name: String,
    /** Pre-rename location of the class file, set only when a top-level class
     * was renamed and its file therefore moved; null for member/variable renames. */
    val oldLocation: Location? = null,
)
