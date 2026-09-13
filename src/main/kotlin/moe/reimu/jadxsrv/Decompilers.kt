package moe.reimu.jadxsrv

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.data.impl.JadxCodeData
import jadx.core.xmlgen.ResContainer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("jadxsrv.decompilers")

fun loadDecompiler(files: List<File>, codeDataPath: Path? = null): Decompiler {
    logger.info("Creating new decompiler for {}", files)

    val jadxArgs = JadxArgs()
    jadxArgs.isDeobfuscationOn = true
    jadxArgs.deobfuscationMinLength = 3
    jadxArgs.inputFiles = files
    jadxArgs.isShowInconsistentCode = true

    val jadx = JadxDecompiler(jadxArgs)
    jadx.load()

    var codeData = JadxCodeData()
    if (codeDataPath != null && Files.exists(codeDataPath)) {
        codeData = try {
            loadCodeData(codeDataPath)
        } catch (e: Exception) {
            // Refuse to start: renaming on top of empty data would overwrite
            // the (possibly corrupt) file with the next save.
            throw IllegalStateException("Failed to load code data from $codeDataPath", e)
        }
        jadx.args.codeData = codeData
        jadx.reloadCodeData()
    }

    val arsc = jadx.resources.find { it.type == ResourceType.ARSC }?.loadContent()

    return Decompiler(jadx, arsc, codeData, codeDataPath)
}

data class Decompiler(
    val jadx: JadxDecompiler,
    val arsc: ResContainer?,
    val codeData: JadxCodeData = JadxCodeData(),
    /** Set only when --code-data was passed; renames are saved there. */
    val codeDataPath: Path? = null,
) : Closeable {
    override fun close() {
        jadx.close()
    }
}
