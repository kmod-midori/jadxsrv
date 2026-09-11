package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class StatResponse(
    val type: Int,
    val ctime: Long = 0,
    val mtime: Long = 0,
    val size: Long = 0,
) {
    companion object {
        const val TYPE_FILE = 1
        const val TYPE_DIR = 2
    }
}