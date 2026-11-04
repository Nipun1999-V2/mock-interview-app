package com.nipun.mockinterview.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal subset of the Anthropic Messages API request/response shape -
 * just enough to send a system+user prompt and force a structured reply
 * via tool-use (the same "make the model call a tool instead of free text"
 * trick used for reliable JSON in the QueryForge project).
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    val max_tokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicTool>,
    val tool_choice: AnthropicToolChoice
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    val input_schema: JsonObject
)

@Serializable
data class AnthropicToolChoice(
    val type: String = "tool",
    val name: String
)

@Serializable
data class AnthropicResponse(
    val id: String? = null,
    val content: List<AnthropicContentBlock> = emptyList(),
    val stop_reason: String? = null
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val input: JsonElement? = null
)

@Serializable
data class AnthropicErrorEnvelope(
    val type: String? = null,
    val error: AnthropicErrorBody? = null
)

@Serializable
data class AnthropicErrorBody(
    val type: String? = null,
    val message: String? = null
)
