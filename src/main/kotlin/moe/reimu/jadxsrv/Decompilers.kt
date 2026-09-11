package moe.reimu.jadxsrv

import io.ktor.server.routing.RoutingContext
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.core.xmlgen.ResContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.Base64

private val logger = LoggerFactory.getLogger("jadxsrv.decompilers")

object Decompilers {
    private val decompilers = mutableMapOf<String, Decompiler>()
    private val decompilerLock = Mutex(false)

    suspend fun getOrNewDecompiler(encodedPaths: String): Decompiler {
        decompilerLock.withLock {
            val existing = decompilers[encodedPaths]
            if (existing != null) {
                return existing
            }

            val files = encodedPaths.split(';').map {
                File(Base64.getUrlDecoder().decode(it).decodeToString())
            }

            logger.info("Creating new decompiler for {}", files)

            val jadxArgs = JadxArgs()
            jadxArgs.isDeobfuscationOn = true
            jadxArgs.deobfuscationMinLength = 3
            jadxArgs.inputFiles = files
            jadxArgs.isShowInconsistentCode = true

            val newDecompiler = withContext(Dispatchers.IO) {
                val decompiler = JadxDecompiler(jadxArgs)
                decompiler.load()

                val resTableContainer =
                    decompiler.resources.find { it.type == jadx.api.ResourceType.ARSC }?.loadContent()

                Decompiler(decompiler, resTableContainer)
            }

            decompilers[encodedPaths] = newDecompiler
            return newDecompiler
        }
    }

    suspend fun closeDecompiler(encodedPaths: String) {
        decompilerLock.withLock {
            val decompiler = decompilers.remove(encodedPaths)
            if (decompiler != null) {
                logger.info("Closing decompiler for {}", encodedPaths)
                decompiler.close()
            }
        }
    }
}

data class Decompiler(
    val jadx: JadxDecompiler,
    val arsc: ResContainer?,
) : Closeable {
    override fun close() {
        jadx.close()
    }
}

suspend fun RoutingContext.getDecompiler(): Decompiler {
    val encodedPaths =
        call.parameters["encodedFilePaths"] ?: throw IllegalArgumentException("Missing encodedFilePaths parameter")
    return Decompilers.getOrNewDecompiler(encodedPaths)
}