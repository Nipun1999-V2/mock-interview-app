package com.nipun.mockinterview.service

import com.nipun.mockinterview.ai.InterviewAiClient
import com.nipun.mockinterview.db.SessionRepository
import com.nipun.mockinterview.model.CreateSessionRequest
import com.nipun.mockinterview.model.CreateSessionResponse
import com.nipun.mockinterview.model.InterviewSession
import com.nipun.mockinterview.model.SessionStatus
import com.nipun.mockinterview.model.SubmitAnswerResponse

class SessionNotFoundException(sessionId: String) : RuntimeException("No interview session with id $sessionId")

class SessionAlreadyCompletedException(sessionId: String) :
    RuntimeException("Interview session $sessionId is already completed")

class InvalidSessionRequestException(message: String) : RuntimeException(message)

/**
 * Orchestrates one interview session's lifecycle: ask a question, grade
 * the answer, ask the next one, and - once the configured question count
 * is reached - close the session out with an overall score and summary.
 *
 * This is the one place that decides "are we done yet?" (by turn count,
 * not by what the AI client returns), which keeps the AI client dumb and
 * easy to swap or fake in tests.
 */
class InterviewService(
    private val aiClient: InterviewAiClient,
    private val repository: SessionRepository
) {

    suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        if (request.role.isBlank()) {
            throw InvalidSessionRequestException("role must not be blank")
        }
        if (request.totalQuestions !in 1..20) {
            throw InvalidSessionRequestException("totalQuestions must be between 1 and 20")
        }

        val firstQuestion = aiClient.generateFirstQuestion(
            role = request.role,
            interviewType = request.interviewType,
            difficulty = request.difficulty
        )

        val session = repository.createSession(
            role = request.role,
            interviewType = request.interviewType,
            difficulty = request.difficulty,
            totalQuestions = request.totalQuestions,
            firstQuestion = firstQuestion
        )

        return CreateSessionResponse(sessionId = session.id, firstQuestion = firstQuestion)
    }

    suspend fun submitAnswer(sessionId: String, answer: String): SubmitAnswerResponse {
        val session = repository.findSession(sessionId) ?: throw SessionNotFoundException(sessionId)
        if (session.status == SessionStatus.COMPLETED) {
            throw SessionAlreadyCompletedException(sessionId)
        }
        if (answer.isBlank()) {
            throw InvalidSessionRequestException("answer must not be blank")
        }

        val currentTurn = session.turns.lastOrNull { it.answer == null }
            ?: throw SessionAlreadyCompletedException(sessionId)
        val isLastQuestion = currentTurn.index == session.totalQuestions - 1

        val (evaluation, nextQuestion) = aiClient.evaluateAnswerAndGenerateNextQuestion(
            role = session.role,
            interviewType = session.interviewType,
            difficulty = session.difficulty,
            history = session.turns,
            currentQuestion = currentTurn.question,
            candidateAnswer = answer,
            isLastQuestion = isLastQuestion
        )

        repository.recordAnswer(
            sessionId = sessionId,
            turnIndex = currentTurn.index,
            answer = answer,
            score = evaluation.score,
            feedback = evaluation.feedback,
            strengths = evaluation.strengths,
            improvements = evaluation.improvements
        )

        if (isLastQuestion) {
            val finished = repository.findSession(sessionId) ?: throw SessionNotFoundException(sessionId)
            val overallScore = finished.turns.mapNotNull { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
            val summary = aiClient.generateSummary(
                role = finished.role,
                interviewType = finished.interviewType,
                transcript = finished.turns
            )
            repository.completeSession(sessionId, summary, overallScore)

            return SubmitAnswerResponse(
                score = evaluation.score,
                feedback = evaluation.feedback,
                strengths = evaluation.strengths,
                improvements = evaluation.improvements,
                nextQuestion = null,
                completed = true,
                summary = summary,
                overallScore = overallScore
            )
        }

        checkNotNull(nextQuestion) { "AI client must return a next question when the session isn't complete" }
        repository.appendQuestion(sessionId, currentTurn.index + 1, nextQuestion)

        return SubmitAnswerResponse(
            score = evaluation.score,
            feedback = evaluation.feedback,
            strengths = evaluation.strengths,
            improvements = evaluation.improvements,
            nextQuestion = nextQuestion,
            completed = false
        )
    }

    suspend fun getSession(sessionId: String): InterviewSession =
        repository.findSession(sessionId) ?: throw SessionNotFoundException(sessionId)
}
