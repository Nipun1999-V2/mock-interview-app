package com.nipun.mockinterview.db

import com.nipun.mockinterview.model.Difficulty
import com.nipun.mockinterview.model.InterviewSession
import com.nipun.mockinterview.model.InterviewType
import com.nipun.mockinterview.model.SessionStatus
import com.nipun.mockinterview.model.Turn
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

private const val LIST_SEPARATOR = "\u0000"

/**
 * All persistence for interview sessions and their turns. Uses a plain
 * function-per-operation style rather than the Exposed DAO API - keeps
 * the mapping between DB rows and the [InterviewSession]/[Turn] domain
 * models explicit and easy to follow.
 *
 * Takes the Exposed [Database] handle explicitly (rather than relying on
 * Exposed's implicit "last connected database" global) so multiple
 * `SessionRepository`s backed by different databases - e.g. one per test
 * case - can safely coexist in the same JVM.
 */
class SessionRepository(private val db: Database) {

    suspend fun createSession(
        role: String,
        interviewType: InterviewType,
        difficulty: Difficulty,
        totalQuestions: Int,
        firstQuestion: String
    ): InterviewSession = newSuspendedTransaction(db = db) {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()

        Sessions.insert {
            it[Sessions.id] = id
            it[Sessions.role] = role
            it[Sessions.interviewType] = interviewType.name
            it[Sessions.difficulty] = difficulty.name
            it[Sessions.totalQuestions] = totalQuestions
            it[Sessions.status] = SessionStatus.IN_PROGRESS.name
            it[Sessions.createdAt] = now
            it[Sessions.updatedAt] = now
        }
        SessionTurns.insert {
            it[SessionTurns.sessionId] = id
            it[SessionTurns.turnIndex] = 0
            it[SessionTurns.question] = firstQuestion
        }

        InterviewSession(
            id = id,
            role = role,
            interviewType = interviewType,
            difficulty = difficulty,
            totalQuestions = totalQuestions,
            status = SessionStatus.IN_PROGRESS,
            turns = listOf(Turn(index = 0, question = firstQuestion)),
            createdAt = now,
            updatedAt = now
        )
    }

    suspend fun findSession(sessionId: String): InterviewSession? = newSuspendedTransaction(db = db) {
        val sessionRow = Sessions.selectAll().where { Sessions.id eq sessionId }.singleOrNull() ?: return@newSuspendedTransaction null

        val turns = SessionTurns.selectAll()
            .where { SessionTurns.sessionId eq sessionId }
            .orderBy(SessionTurns.turnIndex to SortOrder.ASC)
            .map { row ->
                Turn(
                    index = row[SessionTurns.turnIndex],
                    question = row[SessionTurns.question],
                    answer = row[SessionTurns.answer],
                    score = row[SessionTurns.score],
                    feedback = row[SessionTurns.feedback],
                    strengths = row[SessionTurns.strengths]?.split(LIST_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList(),
                    improvements = row[SessionTurns.improvements]?.split(LIST_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
                )
            }

        InterviewSession(
            id = sessionRow[Sessions.id],
            role = sessionRow[Sessions.role],
            interviewType = InterviewType.valueOf(sessionRow[Sessions.interviewType]),
            difficulty = Difficulty.valueOf(sessionRow[Sessions.difficulty]),
            totalQuestions = sessionRow[Sessions.totalQuestions],
            status = SessionStatus.valueOf(sessionRow[Sessions.status]),
            turns = turns,
            summary = sessionRow[Sessions.summary],
            overallScore = sessionRow[Sessions.overallScore],
            createdAt = sessionRow[Sessions.createdAt],
            updatedAt = sessionRow[Sessions.updatedAt]
        )
    }

    suspend fun recordAnswer(
        sessionId: String,
        turnIndex: Int,
        answer: String,
        score: Int,
        feedback: String,
        strengths: List<String>,
        improvements: List<String>
    ) = newSuspendedTransaction(db = db) {
        SessionTurns.update({ (SessionTurns.sessionId eq sessionId) and (SessionTurns.turnIndex eq turnIndex) }) {
            it[SessionTurns.answer] = answer
            it[SessionTurns.score] = score
            it[SessionTurns.feedback] = feedback
            it[SessionTurns.strengths] = strengths.joinToString(LIST_SEPARATOR)
            it[SessionTurns.improvements] = improvements.joinToString(LIST_SEPARATOR)
        }
        Sessions.update({ Sessions.id eq sessionId }) {
            it[updatedAt] = Instant.now()
        }
    }

    suspend fun appendQuestion(sessionId: String, turnIndex: Int, question: String) =
        newSuspendedTransaction(db = db) {
            SessionTurns.insert {
                it[SessionTurns.sessionId] = sessionId
                it[SessionTurns.turnIndex] = turnIndex
                it[SessionTurns.question] = question
            }
        }

    suspend fun completeSession(sessionId: String, summary: String, overallScore: Double) =
        newSuspendedTransaction(db = db) {
            Sessions.update({ Sessions.id eq sessionId }) {
                it[Sessions.status] = SessionStatus.COMPLETED.name
                it[Sessions.summary] = summary
                it[Sessions.overallScore] = overallScore
                it[Sessions.updatedAt] = Instant.now()
            }
        }

    /** Used only by tests to keep the H2 in-memory DB isolated between cases. */
    suspend fun deleteAll() = newSuspendedTransaction(db = db) {
        SessionTurns.deleteWhere { Op.TRUE }
        Sessions.deleteWhere { Op.TRUE }
    }
}
