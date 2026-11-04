package com.nipun.mockinterview.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One row per interview session. `turns` are stored in a separate table
 * (SessionTurns) so we can append to a session's transcript without
 * rewriting the whole row.
 */
object Sessions : Table("sessions") {
    val id = varchar("id", 36)
    val role = varchar("role", 200)
    val interviewType = varchar("interview_type", 30)
    val difficulty = varchar("difficulty", 20)
    val totalQuestions = integer("total_questions")
    val status = varchar("status", 20)
    val summary = text("summary").nullable()
    val overallScore = double("overall_score").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * One row per question/answer exchange. `index` is the turn's position
 * within its session (0-based), and doubles as the natural ordering key.
 */
object SessionTurns : Table("session_turns") {
    val sessionId = varchar("session_id", 36).references(Sessions.id)

    // Named turnIndex rather than "index" - Exposed's Table class already
    // has a member function called index() for declaring DB indexes, and
    // shadowing it with a same-named column is a well-known footgun.
    val turnIndex = integer("turn_index")
    val question = text("question")
    val answer = text("answer").nullable()
    val score = integer("score").nullable()
    val feedback = text("feedback").nullable()
    // Stored as newline-joined text rather than a separate table -
    // simple, and plenty for a handful of bullet points per turn.
    val strengths = text("strengths").nullable()
    val improvements = text("improvements").nullable()

    override val primaryKey = PrimaryKey(sessionId, turnIndex)
}
