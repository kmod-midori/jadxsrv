@file:Suppress("UnstableApiUsage")

package moe.reimu.jadxsrv

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import moe.reimu.jadxsrv.model.StatResponse
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("jadxsrv")

class App : CliktCommand() {
    override fun run() {
        configureLogging()
        logger.info("Starting...")

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
                route("/{encodedFilePaths}") {
                    get("/ls") {
                        getDecompiler()
                        call.respond(LsResponse(dirs = listOf("classes", "resources")))
                    }
                    get("/stat") {
                        getDecompiler()
                        call.respond(StatResponse(type = StatResponse.TYPE_DIR))
                    }
                    post("/close") {
                        Decompilers.closeDecompiler(
                            call.parameters["encodedFilePaths"]!!
                        )
                    }

                    classesRoutes()
                    resourcesRoutes()
                    searchRoutes()
                }
            }
        }.start(wait = true)
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



