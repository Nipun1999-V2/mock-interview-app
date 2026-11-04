package com.nipun.mockinterview.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The kind of interview being simulated. Drives how the AI is prompted
 * to generate questions and score answers.
 */
@Serializable
enum class InterviewType {
    BEHAVIORAL,
    TECHNICAL,
    SYSTEM_DESIGN,
    MIXED
}

@Serializable
enum class Difficulty {
    JUNIOR,
    MID,
    SENIOR,
    STAFF_PLUS
}

@Serializable
enum class SessionStatus {
    IN_PROGRESS,
    COMPLETED
}

/**
 * One question/answer/feedback exchange within a session.
 * `answer`, `score` and `feedback` are null until the candidate responds.
 */
@Serializable
data class Turn(
    val index: Int,
    val question: String,
    val answer: String? = null,
    val score: Int? = null,
    val feedback: String? = null,
    val strengths: List<String> = emptyList(),
    val improvements: List<String> = emptyList()
)

@Serializable
data class InterviewSession(
    val id: String,
    val role: String,
    val interviewType: InterviewType,
    val difficulty: Difficulty,
    val totalQuestions: Int,
    val status: SessionStatus,
    val turns: List<Turn>,
    val summary: String? = null,
    val overallScore: Double? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

// ---- API request/response DTOs ----

@Serializable
data class CreateSessionRequest(
    val role: String,
    val interviewType: InterviewType = InterviewType.MIXED,
    val difficulty: Difficulty = Difficulty.MID,
    val totalQuestions: Int = 5
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val firstQuestion: String
)

@Serializable
data class SubmitAnswerRequest(
    val answer: String
)

@Serializable
data class SubmitAnswerResponse(
    val score: Int,
    val feedback: String,
    val strengths: List<String>,
    val improvements: List<String>,
    val nextQuestion: String?,
    val completed: Boolean,
    val summary: String? = null,
    val overallScore: Double? = null
)

@Serializable
data class ErrorResponse(val error: String)
