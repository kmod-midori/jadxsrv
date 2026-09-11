package moe.reimu.jadxsrv.model

import kotlinx.serialization.Serializable

/**
 * Represents a line and character position, such as
 * the position of the cursor.
 */
@Serializable
data class Position(
    /**
     * The zero-based line value.
     */
    val line: Int,
    /**
     * The zero-based character value.
     *
     * Character offsets are expressed using UTF-16 [code units](https://developer.mozilla.org/en-US/docs/Glossary/Code_unit).
     */
    val character: Int,
)
