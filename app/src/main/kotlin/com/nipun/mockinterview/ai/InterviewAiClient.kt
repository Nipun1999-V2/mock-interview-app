package com.nipun.mockinterview.ai

import com.nipun.mockinterview.model.Difficulty
import com.nipun.mockinterview.model.InterviewType
import com.nipun.mockinterview.model.Turn

data class AnswerEvaluation(
    val score: Int,
    val feedback: String,
    val strengths: List<String>,
    val improvements: List<String>
)

/**
 * Abstraction over "whatever generates questions and grades answers".
 * Keeping this as an interface (rather than calling Anthropic directly
 * from the service layer) means the interview flow can be tested without
 * an API key, and the model/provider behind it can be swapped later.
 */
interface InterviewAiClient {

    suspend fun generateFirstQuestion(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty
    ): String

    suspend fun evaluateAnswerAndGenerateNextQuestion(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty,
        history: List<Turn>,
        currentQuestion: String,
        candidateAnswer: String,
        isLastQuestion: Boolean
    ): Pair<AnswerEvaluation, String?>

    suspend fun generateSummary(
        role: String,
        interviewType: InterviewType,
        transcript: List<Turn>
    ): String
}
