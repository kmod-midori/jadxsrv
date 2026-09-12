package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

@Serializable
data class StatResponse(
    val type: Int,
    val ctime: Long = System.currentTimeMillis(),
    val mtime: Long = System.currentTimeMillis(),
    val size: Long = 0,
) {
    companion object {
        const val TYPE_FILE = 1
        const val TYPE_DIR = 2
    }
}