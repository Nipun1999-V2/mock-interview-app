package com.nipun.mockinterview.routes

import com.nipun.mockinterview.model.CreateSessionRequest
import com.nipun.mockinterview.model.SubmitAnswerRequest
import com.nipun.mockinterview.service.InterviewService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.interviewRoutes(service: InterviewService) {
    route("/api/interviews") {

        post {
            val request = call.receive<CreateSessionRequest>()
            val response = service.createSession(request)
            call.respond(HttpStatusCode.Created, response)
        }

        route("/{id}") {
            get {
                val id = call.parameters["id"]!!
                val session = service.getSession(id)
                call.respond(HttpStatusCode.OK, session)
            }

            post("/answers") {
                val id = call.parameters["id"]!!
                val request = call.receive<SubmitAnswerRequest>()
                val response = service.submitAnswer(id, request.answer)
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
