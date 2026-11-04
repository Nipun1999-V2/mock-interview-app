package com.nipun.mockinterview.ai

import com.nipun.mockinterview.model.Difficulty
import com.nipun.mockinterview.model.InterviewType
import com.nipun.mockinterview.model.Turn
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Talks to the Anthropic Messages API. Every call forces the model to
 * respond through a tool call (rather than free-form text) so the reply
 * is always well-formed JSON we can trust - no regex-scraping a markdown
 * code fence out of a chat response.
 */
class AnthropicInterviewAiClient(
    private val apiKey: String,
    private val model: String,
    private val httpClient: HttpClient
) : InterviewAiClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generateFirstQuestion(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty
    ): String {
        val system = interviewerSystemPrompt(role, interviewType, difficulty)
        val userPrompt = """
            Ask the opening question for this mock interview. It should be the kind of
            question a real interviewer would lead with for this role and interview type.
        """.trimIndent()

        val tool = AnthropicTool(
            name = "ask_question",
            description = "Ask the candidate the next interview question.",
            input_schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("question", buildJsonObject {
                        put("type", "string")
                        put("description", "The interview question, written as the interviewer would say it aloud.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("question"))))
            }
        )

        val result = callTool(system, userPrompt, tool)
        return result["question"]?.jsonPrimitive?.contentOrNull
            ?: error("Anthropic response did not include a question")
    }

    override suspend fun evaluateAnswerAndGenerateNextQuestion(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty,
        history: List<Turn>,
        currentQuestion: String,
        candidateAnswer: String,
        isLastQuestion: Boolean
    ): Pair<AnswerEvaluation, String?> {
        val system = interviewerSystemPrompt(role, interviewType, difficulty)
        val transcript = renderTranscript(history)
        val nextQuestionInstruction = if (isLastQuestion) {
            "This was the final question, so set next_question to null."
        } else {
            "Then ask ONE follow-up or new question that naturally continues the interview " +
                "- don't repeat ground already covered."
        }

        val userPrompt = """
            Interview so far:
            $transcript

            Most recent question: "$currentQuestion"
            Candidate's answer: "$candidateAnswer"

            Score the answer from 1-10, give concise actionable feedback (2-4 sentences),
            list specific strengths and improvements as short bullet points. $nextQuestionInstruction
        """.trimIndent()

        val tool = AnthropicTool(
            name = "submit_evaluation",
            description = "Submit the graded evaluation of the candidate's answer, and optionally the next question.",
            input_schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("score", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 (poor) to 10 (excellent)")
                    })
                    put("feedback", buildJsonObject { put("type", "string") })
                    put("strengths", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("improvements", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("next_question", buildJsonObject {
                        put("type", listOf("string", "null").let { JsonArray(it.map(::JsonPrimitive)) })
                    })
                })
                put(
                    "required",
                    JsonArray(listOf("score", "feedback", "strengths", "improvements").map(::JsonPrimitive))
                )
            }
        )

        val result = callTool(system, userPrompt, tool)
        val evaluation = AnswerEvaluation(
            score = result["score"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5,
            feedback = result["feedback"]?.jsonPrimitive?.contentOrNull ?: "",
            strengths = result["strengths"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            improvements = result["improvements"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        )
        val nextQuestion = if (isLastQuestion) null else result["next_question"]?.jsonPrimitive?.contentOrNull
        return evaluation to nextQuestion
    }

    override suspend fun generateSummary(
        role: String,
        interviewType: InterviewType,
        transcript: List<Turn>
    ): String {
        val system = interviewerSystemPrompt(role, interviewType, Difficulty.MID)
        val userPrompt = """
            Full interview transcript:
            ${renderTranscript(transcript)}

            Write a short (3-5 sentence) closing summary of the candidate's overall
            performance, as if giving feedback to a hiring panel: what came across well,
            what to work on before the real interview.
        """.trimIndent()

        val tool = AnthropicTool(
            name = "submit_summary",
            description = "Submit the closing summary for the whole interview.",
            input_schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("summary", buildJsonObject { put("type", "string") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("summary"))))
            }
        )

        val result = callTool(system, userPrompt, tool)
        return result["summary"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    private fun interviewerSystemPrompt(role: String, interviewType: InterviewType, difficulty: Difficulty): String {
        val typeDescription = when (interviewType) {
            InterviewType.BEHAVIORAL -> "behavioral questions (STAR-format, past experience, teamwork, conflict)"
            InterviewType.TECHNICAL -> "hands-on technical questions for the role's core stack"
            InterviewType.SYSTEM_DESIGN -> "system design and architecture trade-off questions"
            InterviewType.MIXED -> "a mix of behavioral and technical questions"
        }
        return """
            You are an experienced technical interviewer conducting a mock interview for a
            "$role" position, at ${difficulty.name.lowercase().replace('_', '-')} level, focused on
            $typeDescription. Be realistic, specific, and professional - the same way a
            thoughtful hiring manager would run a real interview. Always respond by calling
            the provided tool; never reply with plain prose.
        """.trimIndent()
    }

    private fun renderTranscript(turns: List<Turn>): String {
        if (turns.isEmpty()) return "(no turns yet)"
        return turns.joinToString("\n\n") { turn ->
            buildString {
                append("Q${turn.index + 1}: ${turn.question}")
                if (turn.answer != null) {
                    append("\nA${turn.index + 1}: ${turn.answer}")
                }
                if (turn.score != null) {
                    append("\n(scored ${turn.score}/10)")
                }
            }
        }
    }

    private suspend fun callTool(system: String, userPrompt: String, tool: AnthropicTool): JsonObject {
        val request = AnthropicRequest(
            model = model,
            max_tokens = 1024,
            system = system,
            messages = listOf(AnthropicMessage(role = "user", content = userPrompt)),
            tools = listOf(tool),
            tool_choice = AnthropicToolChoice(name = tool.name)
        )

        val response = httpClient.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val rawBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.error("Anthropic API call failed: {} {}", response.status, rawBody)
            error("Anthropic API call failed with status ${response.status}")
        }

        val parsed = json.decodeFromString(AnthropicResponse.serializer(), rawBody)
        val toolUse = parsed.content.firstOrNull { it.type == "tool_use" && it.name == tool.name }
            ?: error("Anthropic response did not include the expected tool_use block: $rawBody")

        return toolUse.input?.jsonObject ?: error("Tool call had no input payload")
    }
}
