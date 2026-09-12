package moe.reimu.jadxsrv

import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import moe.reimu.jadxsrv.model.SymbolsResponse
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("jadxsrv.search")

@Suppress("UnstableApiUsage")
fun Route.searchRoutes(decompiler: Decompiler) {
    val tasks = ConcurrentHashMap<String, Unit>()

    fun shouldRun(taskId: String): Boolean {
        return tasks.containsKey(taskId)
    }

    post("/search/{taskId}") {
        val query = call.request.queryParameters["query"] ?: run {
            call.respondText("No query", status = io.ktor.http.HttpStatusCode.BadRequest)
            return@post
        }
        val taskId = call.parameters["taskId"] ?: run {
            call.respondText("No taskId", status = io.ktor.http.HttpStatusCode.BadRequest)
            return@post
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
        val types = call.request.queryParameters["types"]?.split(",")?.map { it.trim() }
        if (types.isNullOrEmpty()) {
            call.respondText("Invalid types", status = io.ktor.http.HttpStatusCode.BadRequest)
            return@post
        }
        val ignoreCase = call.request.queryParameters["ignoreCase"] != null

        val searchClasses = types.contains("class")
        val searchFields = types.contains("field")
        val searchMethods = types.contains("method")
        val searchText = types.contains("text")

        if (query.isEmpty() || (!searchClasses && !searchFields && !searchMethods)) {
            call.respond(SymbolsResponse())
            return@post
        }

        logger.info("Search task started: $taskId, query: $query, limit: $limit, types: $types")

        fun isMatch(haystack: String): Boolean {
            return haystack.contains(query, ignoreCase = ignoreCase)
        }

        tasks[taskId] = Unit

        try {
            call.respondTextWriter(status = io.ktor.http.HttpStatusCode.OK) {
                var count = 0
                val json = Json {
                    encodeDefaults = true
                }

                for (cls in decompiler.jadx.classesWithInners) {
                    if (count >= limit || !shouldRun(taskId)) {
                        break
                    }

                    if (searchClasses) {
                        val info = cls.classNode.classInfo
                        if (
                            isMatch(info.shortName) ||
                            isMatch(info.fullName) ||
                            isMatch(info.aliasFullName) ||
                            isMatch(info.rawName)
                        ) {
                            count += 1
                            write(json.encodeToString(SymbolsResponse.Symbol(cls.classNode)))
                            write("\n")
                        }
                    }

                    if (searchText) {
                        val code = cls.classNode.code.codeStr
                        val index = code.indexOf(query, ignoreCase = ignoreCase)
                        if (index != -1) {
                            count += 1
                            write(json.encodeToString(SymbolsResponse.Symbol.fromOffset(cls.classNode, index)))
                            write("\n")
                        }
                    }

                    if (searchMethods) {
                        for (mth in cls.methods) {
                            if (count >= limit || !shouldRun(taskId)) {
                                break
                            }

                            val info = mth.methodNode.methodInfo
                            if (
                                isMatch(info.shortId) ||
                                isMatch(info.alias) ||
                                isMatch(info.fullId) ||
                                isMatch(info.aliasFullName)
                            ) {
                                count += 1
                                write(json.encodeToString(SymbolsResponse.Symbol(mth.methodNode)))
                                write("\n")
                            }
                        }
                    }

                    if (searchFields) {
                        for (field in cls.fields) {
                            if (count >= limit || !shouldRun(taskId)) {
                                break
                            }

                            val info = field.fieldNode.fieldInfo
                            if (
                                isMatch(info.shortId) ||
                                isMatch(info.alias) ||
                                isMatch(info.fullId)
                            ) {
                                count += 1
                                write(json.encodeToString(SymbolsResponse.Symbol(field.fieldNode)))
                                write("\n")
                            }
                        }
                    }

                    flush()
                }
            }
        } finally {
            tasks.remove(taskId)
        }
    }

    delete("/search/{taskId}") {
        val taskId = call.parameters["taskId"] ?: run {
            call.respondText("No taskId", status = io.ktor.http.HttpStatusCode.BadRequest)
            return@delete
        }

        if (tasks.remove(taskId) != null) {
            logger.info("Search task cancelled: $taskId")
        } else {
            logger.warn("Search task not found for cancellation: $taskId")
        }

        call.respondText("Task $taskId cancelled", status = io.ktor.http.HttpStatusCode.OK)
    }
}
