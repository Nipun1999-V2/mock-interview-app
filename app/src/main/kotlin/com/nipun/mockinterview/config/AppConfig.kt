package com.nipun.mockinterview.config

/**
 * All runtime configuration comes from environment variables so the same
 * jar/image runs unchanged across local dev, Docker Compose, and a cloud
 * deployment (Azure Container Apps / ECS / etc). Nothing here is optional
 * except the Anthropic API key, which can be omitted to run in demo mode
 * (see [com.nipun.mockinterview.ai.FakeInterviewAiClient]).
 */
data class AppConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val anthropicApiKey: String?,
    val anthropicModel: String,
    val useFakeAiClient: Boolean
) {
    companion object {
        fun fromEnv(): AppConfig {
            val apiKey = System.getenv("ANTHROPIC_API_KEY")
            return AppConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                databaseUrl = System.getenv("DATABASE_URL")
                    ?: "jdbc:postgresql://localhost:5432/mock_interview",
                databaseUser = System.getenv("DATABASE_USER") ?: "mock_interview",
                databasePassword = System.getenv("DATABASE_PASSWORD") ?: "mock_interview",
                anthropicApiKey = apiKey,
                anthropicModel = System.getenv("ANTHROPIC_MODEL") ?: "claude-sonnet-4-5",
                // Explicit opt-in OR automatic fallback when no key is configured,
                // so `./gradlew run` works out of the box without any secrets.
                useFakeAiClient = System.getenv("USE_FAKE_AI")?.toBoolean() ?: apiKey.isNullOrBlank()
            )
        }
    }
}
