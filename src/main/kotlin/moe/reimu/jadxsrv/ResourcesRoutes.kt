package moe.reimu.jadxsrv

import io.ktor.http.ContentType
import io.ktor.server.response.*
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import jadx.core.xmlgen.ResContainer
import moe.reimu.jadxsrv.model.AnnotationResponse
import moe.reimu.jadxsrv.model.DefinitionResponse
import moe.reimu.jadxsrv.model.LsResponse
import moe.reimu.jadxsrv.model.StatResponse
import org.slf4j.LoggerFactory
import java.util.stream.Stream

private val logger = LoggerFactory.getLogger("jadxsrv.resources")

fun Route.resourcesRoutes(decompiler: Decompiler) {
    get("/ls/resources/{path...}") {
        val path = getCleanPath()
        val dirs = mutableSetOf<String>()
        val files = mutableSetOf<String>()

        val rawResources = decompiler.jadx.resources.stream().map { it.deobfName ?: it.originalName }
        val encodedResources = decompiler.arsc?.subFiles?.stream()?.map { it.name } ?: Stream.empty()

        for (resName in Stream.concat(rawResources, encodedResources)) {
            val nameParts = resName.split('/')

            val strippedParts = stripPrefix(path, nameParts)
            if (strippedParts == null) {
                continue
            }

            when (strippedParts.size) {
                0 -> {}
                1 -> {
                    files.add(strippedParts[0])
                }

                else -> {
                    dirs.add(strippedParts[0])
                }
            }
        }

        call.respond(
            LsResponse(
                dirs = dirs.toList(),
                files = files.toList(),
            )
        )
    }
    get("/stat/resources/{path...}") {
        val res = resolveResource(decompiler)
        if (res == null) {
            call.respond(StatResponse(type = StatResponse.TYPE_DIR))
            return@get
        }

        when (res.dataType) {
            ResContainer.DataType.TEXT -> {
                call.respond(StatResponse(type = StatResponse.TYPE_FILE))
            }

            ResContainer.DataType.DECODED_DATA -> {
                call.respond(StatResponse(type = StatResponse.TYPE_FILE))
            }

            ResContainer.DataType.RES_LINK -> {
                val entry = res.resLink.zipEntry
                if (entry == null) {
                    call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
                    return@get
                }
                call.respond(StatResponse(type = StatResponse.TYPE_FILE, size = entry.uncompressedSize))
            }

            ResContainer.DataType.RES_TABLE -> {
                call.respond(StatResponse(type = StatResponse.TYPE_FILE))
            }
        }
    }
    get("/read/resources/{path...}") {
        val loaded = resolveResource(decompiler)
        if (loaded == null) {
            call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }

        when (loaded.dataType) {
            ResContainer.DataType.TEXT, ResContainer.DataType.RES_TABLE -> {
                call.respondText(
                    contentType = ContentType.Application.OctetStream,
                    text = loaded.text.codeStr
                )
            }

            ResContainer.DataType.RES_LINK -> {
                val entry = loaded.resLink.zipEntry
                if (entry == null) {
                    call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
                    return@get
                }

                entry.inputStream.use { input ->
                    call.respondBytesWriter(
                        contentType = ContentType.Application.OctetStream,
                        contentLength = entry.uncompressedSize
                    ) {
                        input.toByteReadChannel().copyTo(this)
                    }
                }
            }

            else -> {
                println(loaded)
                call.respondText("Not supported yet")
            }
        }
    }
    get("/annotation/resources/{path...}") {
        val offset = getOffsetInt()
        val loaded = resolveResource(decompiler)
        if (loaded == null) {
            call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }

        if (loaded.dataType != ResContainer.DataType.TEXT && loaded.dataType != ResContainer.DataType.RES_TABLE) {
            call.respond(AnnotationResponse())
            return@get
        }

        val ci = loaded.text
        val annotation = ci.codeMetadata.getAt(offset)
        if (annotation == null) {
            call.respond(AnnotationResponse())
            return@get
        }

        call.respond(AnnotationResponse(formatHover(annotation)))
    }
    get("/definition/resources/{path...}") {
        val offset = getOffsetInt()
        val loaded = resolveResource(decompiler)
        if (loaded == null) {
            call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }

        if (loaded.dataType != ResContainer.DataType.TEXT && loaded.dataType != ResContainer.DataType.RES_TABLE) {
            call.respond(DefinitionResponse())
            return@get
        }

        val ci = loaded.text
        val annotation = ci.codeMetadata.getAt(offset)
        if (annotation == null) {
            call.respond(DefinitionResponse())
            return@get
        }

        call.respond(resolveDefinition(annotation))
    }
}

fun stripPrefix(prefix: List<String>, path: List<String>): List<String>? {
    val commonPrefix = prefix.zip(path).takeWhile { it.first == it.second }
    if (commonPrefix.size != prefix.size) {
        return null
    }
    return path.drop(commonPrefix.size)
}

fun RoutingContext.resolveResource(decompiler: Decompiler): ResContainer? {
    val path = getCleanPath()

    val pathStr = path.joinToString("/")
    for (res in decompiler.jadx.resources) {
        val name = res.deobfName ?: res.originalName
        if (pathStr == name) {
            return res.loadContent()
        }
    }
    for (resContainer in decompiler.arsc?.subFiles.orEmpty()) {
        if (pathStr == resContainer.name) {
            return resContainer
        }
    }
    return null
}
