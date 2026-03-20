package io.github.fate_grand_automata.scripts.llm

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import com.google.gson.JsonParser
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for OpenRouterLlmService request/response parsing logic.
 *
 * These tests validate JSON construction and parsing WITHOUT making actual API calls.
 * For live API testing, use the shell script: llm-spike/run-spike.sh
 */
class OpenRouterLlmServiceTest {

    @Test
    fun `parseResponse correctly handles a valid battle screen response`() {
        val responseBody = """
        {
            "id": "gen-123",
            "model": "anthropic/claude-sonnet-4",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "{\"screen_type\": \"BATTLE\", \"confidence\": 0.95, \"description\": \"Active battle screen showing 3 servants fighting enemies\", \"visible_elements\": [\"HP bars\", \"Skill icons\", \"NP gauge\", \"BATTLE text\", \"Turn counter\"], \"suggested_actions\": [\"Use skills\", \"Attack\", \"Use Noble Phantasm\"]}"
                    },
                    "finish_reason": "stop"
                }
            ],
            "usage": {
                "prompt_tokens": 1500,
                "completion_tokens": 150,
                "total_tokens": 1650
            }
        }
        """.trimIndent()

        val result = parseResponseViaReflection(responseBody, "anthropic/claude-sonnet-4", 2000)

        assertThat(result.screenType).isEqualTo(ScreenType.BATTLE)
        assertThat(result.confidence).isEqualTo(0.95)
        assertThat(result.description).contains("battle")
        assertThat(result.visibleElements).isNotEmpty()
        assertThat(result.visibleElements.size).isEqualTo(5)
        assertThat(result.suggestedActions.size).isEqualTo(3)
        assertThat(result.model).isEqualTo("anthropic/claude-sonnet-4")
        assertThat(result.latencyMs).isEqualTo(2000)
        assertThat(result.tokenUsage).isNotNull()
        assertThat(result.tokenUsage!!.promptTokens).isEqualTo(1500)
        assertThat(result.tokenUsage!!.completionTokens).isEqualTo(150)
        assertThat(result.tokenUsage!!.totalTokens).isEqualTo(1650)
    }

    @Test
    fun `parseResponse handles response with markdown code fences`() {
        val responseBody = """
        {
            "id": "gen-456",
            "model": "openai/gpt-4o-mini",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "```json\n{\"screen_type\": \"HOME\", \"confidence\": 0.9, \"description\": \"Main menu screen\", \"visible_elements\": [\"Master info\", \"Menu buttons\"], \"suggested_actions\": [\"Navigate to quests\"]}\n```"
                    },
                    "finish_reason": "stop"
                }
            ],
            "usage": {
                "prompt_tokens": 1000,
                "completion_tokens": 100,
                "total_tokens": 1100
            }
        }
        """.trimIndent()

        val result = parseResponseViaReflection(responseBody, "openai/gpt-4o-mini", 1500)

        assertThat(result.screenType).isEqualTo(ScreenType.HOME)
        assertThat(result.confidence).isEqualTo(0.9)
    }

    @Test
    fun `parseResponse handles unknown screen type gracefully`() {
        val responseBody = """
        {
            "id": "gen-789",
            "model": "test-model",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "{\"screen_type\": \"SOME_NEW_TYPE\", \"confidence\": 0.5, \"description\": \"Unknown screen\", \"visible_elements\": [], \"suggested_actions\": []}"
                    },
                    "finish_reason": "stop"
                }
            ]
        }
        """.trimIndent()

        val result = parseResponseViaReflection(responseBody, "test-model", 1000)

        assertThat(result.screenType).isEqualTo(ScreenType.UNKNOWN)
        assertThat(result.tokenUsage).isEqualTo(null)
    }

    @Test
    fun `parseResponse throws on empty choices`() {
        val responseBody = """
        {
            "id": "gen-err",
            "model": "test-model",
            "choices": []
        }
        """.trimIndent()

        val exception = assertFailsWith<InvocationTargetException> {
            parseResponseViaReflection(responseBody, "test-model", 1000)
        }
        assertThat(exception.cause).isNotNull().isInstanceOf(LlmServiceException::class)
    }

    @Test
    fun `parseResponse throws on invalid JSON content`() {
        val responseBody = """
        {
            "id": "gen-err2",
            "model": "test-model",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "This is not JSON at all, just plain text"
                    },
                    "finish_reason": "stop"
                }
            ]
        }
        """.trimIndent()

        val exception = assertFailsWith<InvocationTargetException> {
            parseResponseViaReflection(responseBody, "test-model", 1000)
        }
        assertThat(exception.cause).isNotNull().isInstanceOf(LlmServiceException::class)
    }

    @Test
    fun `parseResponse handles all screen types`() {
        for (screenType in ScreenType.values()) {
            val responseBody = """
            {
                "id": "gen-${screenType.name}",
                "model": "test-model",
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "{\"screen_type\": \"${screenType.name}\", \"confidence\": 0.8, \"description\": \"Test\", \"visible_elements\": [], \"suggested_actions\": []}"
                        },
                        "finish_reason": "stop"
                    }
                ]
            }
            """.trimIndent()

            val result = parseResponseViaReflection(responseBody, "test-model", 100)
            assertThat(result.screenType).isEqualTo(screenType)
        }
    }

    @Test
    fun `request body is well-formed JSON with image content`() {
        val fakeBase64 = "iVBORw0KGgoAAAANS"
        val service = OpenRouterLlmService(apiKey = "test-key", defaultModel = "test-model")

        // Use reflection to call the private buildRequestBody method
        val method = OpenRouterLlmService::class.java.getDeclaredMethod(
            "buildRequestBody", String::class.java, String::class.java
        )
        method.isAccessible = true
        val requestJson = method.invoke(service, fakeBase64, "test-model") as String

        // Parse and validate structure
        val parsed = JsonParser.parseString(requestJson).asJsonObject
        assertThat(parsed.get("model").asString).isEqualTo("test-model")
        assertThat(parsed.get("max_tokens").asInt).isEqualTo(1024)
        assertThat(parsed.get("temperature").asDouble).isEqualTo(0.1)

        val messages = parsed.getAsJsonArray("messages")
        assertThat(messages.size()).isEqualTo(2)

        // System message
        val systemMsg = messages[0].asJsonObject
        assertThat(systemMsg.get("role").asString).isEqualTo("system")
        assertThat(systemMsg.get("content").asString).contains("screen_type")

        // User message with image
        val userMsg = messages[1].asJsonObject
        assertThat(userMsg.get("role").asString).isEqualTo("user")
        val content = userMsg.getAsJsonArray("content")
        assertThat(content.size()).isEqualTo(2)

        val textPart = content[0].asJsonObject
        assertThat(textPart.get("type").asString).isEqualTo("text")

        val imagePart = content[1].asJsonObject
        assertThat(imagePart.get("type").asString).isEqualTo("image_url")
        val imageUrl = imagePart.getAsJsonObject("image_url").get("url").asString
        assertThat(imageUrl).contains("data:image/png;base64,")
        assertThat(imageUrl).contains(fakeBase64)
    }

    /**
     * Helper to call the private parseResponse method via reflection.
     */
    private fun parseResponseViaReflection(
        responseBody: String,
        model: String,
        latencyMs: Long
    ): ScreenIdentificationResult {
        val service = OpenRouterLlmService(apiKey = "test-key")
        val method = OpenRouterLlmService::class.java.getDeclaredMethod(
            "parseResponse", String::class.java, String::class.java, Long::class.java
        )
        method.isAccessible = true
        return method.invoke(service, responseBody, model, latencyMs) as ScreenIdentificationResult
    }
}
