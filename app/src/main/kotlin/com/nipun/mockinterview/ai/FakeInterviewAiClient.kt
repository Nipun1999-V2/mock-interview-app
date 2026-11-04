package com.nipun.mockinterview.ai

import com.nipun.mockinterview.model.Difficulty
import com.nipun.mockinterview.model.InterviewType
import com.nipun.mockinterview.model.Turn

/**
 * Deterministic, offline stand-in for [AnthropicInterviewAiClient]. Used
 * automatically when no ANTHROPIC_API_KEY is configured, and in unit
 * tests, so the whole session lifecycle (create -> answer -> answer ->
 * complete -> summary) can be exercised without spending API credits or
 * needing network access.
 */
class FakeInterviewAiClient : InterviewAiClient {

    private val behavioralBank = listOf(
        "Tell me about a time you had to push back on a technical decision. What happened?",
        "Describe a project where the requirements changed significantly midway through.",
        "Walk me through how you handled a production incident you were responsible for."
    )

    private val technicalBank = listOf(
        "How would you design a rate limiter for a public API?",
        "Explain how you'd debug a service that's intermittently timing out under load.",
        "What trade-offs would you weigh between a message queue and direct synchronous calls here?"
    )

    override suspend fun generateFirstQuestion(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty
    ): String = pickQuestion(interviewType, index = 0, role = role)

    override suspend fun evaluateAnswerAndGenerateNextQuestion(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty,
        history: List<Turn>,
        currentQuestion: String,
        candidateAnswer: String,
        isLastQuestion: Boolean
    ): Pair<AnswerEvaluation, String?> {
        val wordCount = candidateAnswer.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val score = when {
            wordCount == 0 -> 1
            wordCount < 15 -> 4
            wordCount < 60 -> 7
            else -> 8
        }
        val evaluation = AnswerEvaluation(
            score = score,
            feedback = if (wordCount == 0) {
                "No answer was given - in a real interview, even a partial attempt beats silence."
            } else {
                "Reasonable answer (demo mode: scored heuristically on length/structure, not content). " +
                    "In live mode, Claude would grade the actual substance of what you said."
            },
            strengths = if (wordCount >= 15) listOf("Gave a concrete, structured answer") else emptyList(),
            improvements = if (wordCount < 60) listOf("Add more specific detail or a concrete example") else emptyList()
        )
        val next = if (isLastQuestion) null else pickQuestion(interviewType, index = history.size + 1, role = role)
        return evaluation to next
    }

    override suspend fun generateSummary(
        role: String,
        interviewType: InterviewType,
        transcript: List<Turn>
    ): String {
        val avg = transcript.mapNotNull { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgFormatted = String.format("%.1f", avg)
        return "Demo-mode summary for the $role interview: averaged $avgFormatted/10 across " +
            "${transcript.size} question(s). Configure ANTHROPIC_API_KEY for real content-aware " +
            "grading and feedback."
    }

    private fun pickQuestion(interviewType: InterviewType, index: Int, role: String): String {
        val bank = when (interviewType) {
            InterviewType.BEHAVIORAL -> behavioralBank
            InterviewType.TECHNICAL, InterviewType.SYSTEM_DESIGN -> technicalBank
            InterviewType.MIXED -> behavioralBank + technicalBank
        }
        return bank[index % bank.size] + " (role context: $role)"
    }
}
