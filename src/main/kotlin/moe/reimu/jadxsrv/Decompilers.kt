package moe.reimu.jadxsrv

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.data.impl.JadxCodeData
import jadx.core.xmlgen.ResContainer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File

private val logger = LoggerFactory.getLogger("jadxsrv.decompilers")

fun loadDecompiler(files: List<File>): Decompiler {
    logger.info("Creating new decompiler for {}", files)

    val jadxArgs = JadxArgs()
    jadxArgs.isDeobfuscationOn = true
    jadxArgs.deobfuscationMinLength = 3
    jadxArgs.inputFiles = files
    jadxArgs.isShowInconsistentCode = true

    val jadx = JadxDecompiler(jadxArgs)
    jadx.load()

    val arsc = jadx.resources.find { it.type == ResourceType.ARSC }?.loadContent()

    return Decompiler(jadx, arsc)
}

data class Decompiler(
    val jadx: JadxDecompiler,
    val arsc: ResContainer?,
    val codeData: JadxCodeData = JadxCodeData(),
) : Closeable {
    override fun close() {
        jadx.close()
    }
}
