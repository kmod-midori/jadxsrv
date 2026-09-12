@file:Suppress("UnstableApiUsage")

package moe.reimu.jadxsrv

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.file
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import moe.reimu.jadxsrv.model.LsResponse
import org.slf4j.LoggerFactory
import io.ktor.server.response.*
import io.ktor.server.routing.IgnoreTrailingSlash
import moe.reimu.jadxsrv.model.StatResponse
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("jadxsrv")

class App : CliktCommand() {
    private val inputFiles by argument("input").file(mustExist = true, canBeDir = false).multiple(required = true)

    override fun run() {
        configureLogging()
        logger.info("Starting...")

        val decompiler = loadDecompiler(inputFiles)

        try {
            embeddedServer(Netty, 28080) {
                install(CallLogging) {
                    level = Level.INFO
                }

                install(ContentNegotiation) {
                    json(Json {
                        isLenient = true
                        encodeDefaults = true
                    })
                }

                install(IgnoreTrailingSlash)

                routing {
                    get("/ls") {
                        call.respond(LsResponse(dirs = listOf("classes", "resources")))
                    }
                    get("/stat") {
                        call.respond(StatResponse(type = StatResponse.TYPE_DIR))
                    }

                    classesRoutes(decompiler)
                    resourcesRoutes(decompiler)
                    searchRoutes(decompiler)
                    callHierarchyRoutes(decompiler)
                }
            }.start(wait = true)
        } finally {
            decompiler.close()
        }
    }
}

fun main(args: Array<String>) = App().main(args)

fun configureLogging() {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext

    val consoleAppender = ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
        this.context = context
        this.encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
            this.start()
        }
        this.start()
    }

    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    rootLogger.detachAndStopAllAppenders()
    rootLogger.addAppender(consoleAppender)
    rootLogger.level = ch.qos.logback.classic.Level.INFO
}
