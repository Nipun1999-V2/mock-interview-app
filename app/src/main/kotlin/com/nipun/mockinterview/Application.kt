package com.nipun.mockinterview

import com.nipun.mockinterview.ai.AnthropicInterviewAiClient
import com.nipun.mockinterview.ai.FakeInterviewAiClient
import com.nipun.mockinterview.ai.InterviewAiClient
import com.nipun.mockinterview.config.AppConfig
import com.nipun.mockinterview.db.DatabaseFactory
import com.nipun.mockinterview.db.SessionRepository
import com.nipun.mockinterview.model.ErrorResponse
import com.nipun.mockinterview.routes.interviewRoutes
import com.nipun.mockinterview.service.InvalidSessionRequestException
import com.nipun.mockinterview.service.InterviewService
import com.nipun.mockinterview.service.SessionAlreadyCompletedException
import com.nipun.mockinterview.service.SessionNotFoundException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    val config = AppConfig.fromEnv()
    val database = DatabaseFactory.init(config)
    val repository = SessionRepository(database)
    val aiClient = buildAiClient(config)
    val service = InterviewService(aiClient, repository)

    embeddedServer(Netty, port = config.port) {
        configureApp(service)
    }.start(wait = true)
}

fun buildAiClient(config: AppConfig): InterviewAiClient {
    if (config.useFakeAiClient) {
        return FakeInterviewAiClient()
    }
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    return AnthropicInterviewAiClient(
        apiKey = requireNotNull(config.anthropicApiKey) { "ANTHROPIC_API_KEY must be set unless USE_FAKE_AI=true" },
        model = config.anthropicModel,
        httpClient = httpClient
    )
}

fun Application.configureApp(service: InterviewService) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }
    install(StatusPages) {
        exception<SessionNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
        exception<SessionAlreadyCompletedException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict"))
        }
        exception<InvalidSessionRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        interviewRoutes(service)
    }
}
