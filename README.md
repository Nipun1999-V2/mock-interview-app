# Mock Interview API (Kotlin + Ktor)

A small backend service that runs AI-graded mock interviews: it asks a question,
grades your spoken/typed answer, asks a follow-up, and closes the session out
with an overall score and a short written summary - the same shape as a real
interview, minus the human on the other end.

This is a from-scratch Kotlin project, built to close a specific, named gap
(Kotlin) rather than as a toy "hello world" - it leans on patterns from
existing Java/Spring work (layered service/repository architecture, structured
LLM tool-calling the same way the QueryForge project used the Claude API) so
it doubles as a portfolio piece and an interview-prep tool at the same time.

## How it's put together

```
HTTP request
   -> routes/InterviewRoutes.kt      (Ktor routing - thin, no logic)
   -> service/InterviewService.kt    (session lifecycle, turn-count bookkeeping)
   -> ai/InterviewAiClient.kt        (interface: generate question / grade answer / summarize)
        -> AnthropicInterviewAiClient   (real: calls Claude via tool-use for structured JSON)
        -> FakeInterviewAiClient        (demo: deterministic, offline, no API key needed)
   -> db/SessionRepository.kt        (Exposed DSL over Postgres - sessions + turns)
```

The service layer is the only place that decides "is this interview over yet"
(by counting turns against `totalQuestions`), which keeps the AI client dumb
and easy to fake in tests - `InterviewServiceTest` exercises the full
create -> answer -> answer -> complete -> summary lifecycle against an
in-memory H2 database and `FakeInterviewAiClient`, with no network access or
API key required to run `./gradlew test`.

Every AI call is forced through Anthropic's tool-use mechanism (the model
must call a tool like `submit_evaluation` rather than reply with prose), so
responses are parsed as real JSON rather than scraped out of a markdown code
fence - a resilience trick worth mentioning; it's the same one used in the
QueryForge natural-language-to-SQL project.

## Stack

Kotlin, Ktor 3 (server + client), Exposed (SQL DSL) over PostgreSQL, HikariCP,
kotlinx.serialization, JUnit5/kotlin.test + H2 for tests, Docker Compose for
local orchestration.

## Running it

**Demo mode (no API key, no cost):**

```bash
docker compose up db -d      # just the database
export DATABASE_URL=jdbc:postgresql://localhost:5432/mock_interview
export DATABASE_USER=mock_interview
export DATABASE_PASSWORD=mock_interview
./gradlew run
```

With no `ANTHROPIC_API_KEY` set, the app automatically falls back to
`FakeInterviewAiClient`, which grades answers heuristically (length/structure,
not real content) so you can exercise the whole API without spending anything.

**Real AI-graded interviews:**

```bash
cp .env.example .env
# edit .env and set ANTHROPIC_API_KEY=sk-ant-...
docker compose --env-file .env up --build
```

**Tests** (no Postgres needed - uses an in-memory H2 database):

```bash
./gradlew test
```

## API

```
POST /api/interviews
  { "role": "Backend Engineer", "interviewType": "MIXED",
    "difficulty": "SENIOR", "totalQuestions": 5 }
  -> 201 { "sessionId": "...", "firstQuestion": "..." }

POST /api/interviews/{id}/answers
  { "answer": "..." }
  -> 200 { "score": 7, "feedback": "...", "strengths": [...],
           "improvements": [...], "nextQuestion": "..." | null,
           "completed": false | true, "summary": "..."?, "overallScore": 7.5? }

GET /api/interviews/{id}
  -> 200 full session transcript (all turns, status, summary once completed)

GET /health
  -> 200 { "status": "ok" }
```

`interviewType` is one of `BEHAVIORAL`, `TECHNICAL`, `SYSTEM_DESIGN`, `MIXED`;
`difficulty` is one of `JUNIOR`, `MID`, `SENIOR`, `STAFF_PLUS`.

## A note on this build

This project was scaffolded and written in a sandboxed environment whose
network policy blocks Maven Central / the Gradle Plugin Portal, so
`./gradlew build` could not be run end-to-end here to confirm a clean
compile. Every file was hand-reviewed for type/import correctness (including
a manual Kotlin-syntax pass using the compiler bundled with the local Gradle
install), but treat the very first `./gradlew build` on your own machine as
the real compile check - in particular, double-check the exact import
packages for the Ktor `CORS` and `CallLogging` plugins against whatever Ktor
3.0.3 resolves to, since plugin package paths have moved around between Ktor
2.x and 3.x and this couldn't be verified against real dependency jars here.

## Possible next steps

- A minimal React/TypeScript frontend (matches your existing stack) instead
  of curl/Postman for exercising the API.
- Deploy the Docker image to Azure Container Apps - you've already got
  hands-on ACA experience from MPA, so this would be a short hop.
- Swap the length-based demo heuristic for a tiny local embedding-similarity
  score, so demo mode is more than a word-count check.
- Add auth (even a simple API key header) before this goes anywhere public.
