package com.nipun.mockinterview

import com.nipun.mockinterview.ai.FakeInterviewAiClient
import com.nipun.mockinterview.db.SessionRepository
import com.nipun.mockinterview.db.SessionTurns
import com.nipun.mockinterview.db.Sessions
import com.nipun.mockinterview.model.CreateSessionRequest
import com.nipun.mockinterview.model.InterviewType
import com.nipun.mockinterview.model.SessionStatus
import com.nipun.mockinterview.service.InterviewService
import com.nipun.mockinterview.service.InvalidSessionRequestException
import com.nipun.mockinterview.service.SessionAlreadyCompletedException
import com.nipun.mockinterview.service.SessionNotFoundException
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the full session lifecycle against a real (in-memory H2)
 * database and the deterministic [FakeInterviewAiClient], so these tests
 * run offline with no API key or external Postgres instance required.
 */
class InterviewServiceTest {

    private lateinit var repository: SessionRepository
    private lateinit var service: InterviewService

    @BeforeTest
    fun setUp() {
        // A fresh in-memory schema per test keeps cases independent without
        // needing a running Postgres container just to run `./gradlew test`.
        val database = Database.connect(
            url = "jdbc:h2:mem:mock_interview_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        transaction(database) {
            SchemaUtils.create(Sessions, SessionTurns)
        }
        repository = SessionRepository(database)
        service = InterviewService(FakeInterviewAiClient(), repository)
    }

    @Test
    fun `creating a session returns the first question and persists it`() = runTest {
        val response = service.createSession(
            CreateSessionRequest(role = "Backend Engineer", interviewType = InterviewType.TECHNICAL, totalQuestions = 3)
        )

        assertNotNull(response.sessionId)
        assertTrue(response.firstQuestion.isNotBlank())

        val session = service.getSession(response.sessionId)
        assertEquals(SessionStatus.IN_PROGRESS, session.status)
        assertEquals(1, session.turns.size)
        assertEquals(response.firstQuestion, session.turns.first().question)
    }

    @Test
    fun `rejects an out-of-range question count`() = runTest {
        assertFailsWith<InvalidSessionRequestException> {
            service.createSession(CreateSessionRequest(role = "Backend Engineer", totalQuestions = 0))
        }
    }

    @Test
    fun `a full session progresses through every turn and completes with a summary`() = runTest {
        val created = service.createSession(
            CreateSessionRequest(role = "Backend Engineer", interviewType = InterviewType.MIXED, totalQuestions = 2)
        )

        val first = service.submitAnswer(created.sessionId, "I once redesigned a permit workflow under a tight deadline.")
        assertEquals(false, first.completed)
        assertNotNull(first.nextQuestion)

        val second = service.submitAnswer(created.sessionId, "I'd put a token bucket in front of the API gateway.")
        assertTrue(second.completed)
        assertNull(second.nextQuestion)
        assertNotNull(second.summary)
        assertNotNull(second.overallScore)

        val finalSession = service.getSession(created.sessionId)
        assertEquals(SessionStatus.COMPLETED, finalSession.status)
        assertEquals(2, finalSession.turns.size)
        assertTrue(finalSession.turns.all { it.answer != null && it.score != null })
    }

    @Test
    fun `submitting an answer to a completed session fails`() = runTest {
        val created = service.createSession(CreateSessionRequest(role = "Backend Engineer", totalQuestions = 1))
        service.submitAnswer(created.sessionId, "A complete answer covering the key points.")

        assertFailsWith<SessionAlreadyCompletedException> {
            service.submitAnswer(created.sessionId, "One more, please?")
        }
    }

    @Test
    fun `answering an unknown session fails`() = runTest {
        assertFailsWith<SessionNotFoundException> {
            service.submitAnswer("does-not-exist", "answer")
        }
    }

    @Test
    fun `blank answers are rejected`() = runTest {
        val created = service.createSession(CreateSessionRequest(role = "Backend Engineer", totalQuestions = 2))
        assertFailsWith<InvalidSessionRequestException> {
            service.submitAnswer(created.sessionId, "   ")
        }
    }
}
