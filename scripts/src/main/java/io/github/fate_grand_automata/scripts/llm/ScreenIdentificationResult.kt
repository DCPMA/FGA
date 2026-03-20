package io.github.fate_grand_automata.scripts.llm

/**
 * Structured result from LLM screen identification.
 */
data class ScreenIdentificationResult(
    /** The identified screen type */
    val screenType: ScreenType,

    /** Confidence level from 0.0 to 1.0 */
    val confidence: Double,

    /** Human-readable description of what's on screen */
    val description: String,

    /** List of UI elements detected on screen */
    val visibleElements: List<String>,

    /** Suggested actions that could be taken on this screen */
    val suggestedActions: List<String>,

    /** The model used for identification */
    val model: String,

    /** Latency in milliseconds for the API call */
    val latencyMs: Long,

    /** Token usage details */
    val tokenUsage: TokenUsage? = null,

    /** Raw response from the LLM (for debugging) */
    val rawResponse: String? = null
)

/**
 * Token usage from the API response, used for cost estimation.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)
