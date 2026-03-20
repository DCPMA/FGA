package io.github.fate_grand_automata.scripts.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

/**
 * LLM service implementation that uses OpenRouter API for screen understanding.
 *
 * OpenRouter provides a unified API to access multiple LLM providers
 * (Claude, GPT-4, DeepSeek, etc.) with a single API key.
 *
 * @param apiKey OpenRouter API key (user-provided, BYOK model)
 * @param defaultModel default model to use for requests
 */
class OpenRouterLlmService(
    private val apiKey: String,
    private val defaultModel: String = DEFAULT_MODEL
) : LlmService {

    companion object {
        const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_MODEL = "anthropic/claude-sonnet-4"

        /** Models to test in the spike */
        val SPIKE_MODELS = listOf(
            "anthropic/claude-sonnet-4",
            "openai/gpt-4o-mini",
            "deepseek/deepseek-chat-v3-0324"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun identifyScreen(screenshotBase64: String): ScreenIdentificationResult {
        return identifyScreen(screenshotBase64, defaultModel)
    }

    override suspend fun identifyScreen(screenshotBase64: String, model: String): ScreenIdentificationResult {
        val requestBody = buildRequestBody(screenshotBase64, model)
        val request = Request.Builder()
            .url(OPENROUTER_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/Fate-Grand-Automata/FGA")
            .addHeader("X-Title", "FGA LLM Spike")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        val timeMark = TimeSource.Monotonic.markNow()

        val response = client.newCall(request).execute()
        val latencyMs = timeMark.elapsedNow().inWholeMilliseconds

        val responseBody = response.body?.string()
            ?: throw LlmServiceException("Empty response body from OpenRouter")

        if (!response.isSuccessful) {
            throw LlmServiceException(
                "OpenRouter API error ${response.code}: $responseBody"
            )
        }

        return parseResponse(responseBody, model, latencyMs)
    }

    private fun buildRequestBody(screenshotBase64: String, model: String): String {
        val imageUrl = "data:image/png;base64,$screenshotBase64"

        // Build the request following OpenRouter's Chat Completions API format
        val requestObj = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to ScreenPromptTemplate.systemPrompt
                ),
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to ScreenPromptTemplate.userPrompt
                        ),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to imageUrl
                            )
                        )
                    )
                )
            ),
            "max_tokens" to 1024,
            "temperature" to 0.1
        )

        return gson.toJson(requestObj)
    }

    private fun parseResponse(
        responseBody: String,
        model: String,
        latencyMs: Long
    ): ScreenIdentificationResult {
        val responseJson = JsonParser.parseString(responseBody).asJsonObject

        // Extract token usage
        val usageObj = responseJson.getAsJsonObject("usage")
        val tokenUsage = if (usageObj != null) {
            TokenUsage(
                promptTokens = usageObj.get("prompt_tokens")?.asInt ?: 0,
                completionTokens = usageObj.get("completion_tokens")?.asInt ?: 0,
                totalTokens = usageObj.get("total_tokens")?.asInt ?: 0
            )
        } else null

        // Extract the LLM's text response
        val choices = responseJson.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw LlmServiceException("No choices in OpenRouter response: $responseBody")
        }

        val messageContent = choices[0].asJsonObject
            .getAsJsonObject("message")
            ?.get("content")?.asString
            ?: throw LlmServiceException("No message content in response: $responseBody")

        // Parse the JSON from the LLM's response
        // Strip markdown code fences if present
        val cleanedContent = messageContent
            .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        val resultJson = try {
            JsonParser.parseString(cleanedContent).asJsonObject
        } catch (e: Exception) {
            throw LlmServiceException(
                "Failed to parse LLM JSON response: ${e.message}\nRaw: $messageContent"
            )
        }

        val screenTypeStr = resultJson.get("screen_type")?.asString ?: "UNKNOWN"
        val screenType = try {
            ScreenType.valueOf(screenTypeStr)
        } catch (e: IllegalArgumentException) {
            ScreenType.UNKNOWN
        }

        val confidence = resultJson.get("confidence")?.asDouble ?: 0.0
        val description = resultJson.get("description")?.asString ?: ""

        val visibleElements = resultJson.getAsJsonArray("visible_elements")
            ?.map { it.asString }
            ?: emptyList()

        val suggestedActions = resultJson.getAsJsonArray("suggested_actions")
            ?.map { it.asString }
            ?: emptyList()

        return ScreenIdentificationResult(
            screenType = screenType,
            confidence = confidence,
            description = description,
            visibleElements = visibleElements,
            suggestedActions = suggestedActions,
            model = model,
            latencyMs = latencyMs,
            tokenUsage = tokenUsage,
            rawResponse = messageContent
        )
    }
}

/**
 * Exception thrown when the LLM service encounters an error.
 */
class LlmServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
