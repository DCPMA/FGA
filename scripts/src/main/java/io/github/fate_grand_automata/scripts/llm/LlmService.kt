package io.github.fate_grand_automata.scripts.llm

/**
 * Service interface for LLM-based screen understanding.
 *
 * Sends screenshots to an LLM via OpenRouter and receives structured
 * responses identifying the current game screen and available actions.
 */
interface LlmService {
    /**
     * Identifies the current FGO screen from a screenshot.
     *
     * @param screenshotBase64 Base64-encoded PNG screenshot data
     * @return structured response identifying the screen type and available actions
     */
    suspend fun identifyScreen(screenshotBase64: String): ScreenIdentificationResult

    /**
     * Identifies the current FGO screen using a specific model.
     *
     * @param screenshotBase64 Base64-encoded PNG screenshot data
     * @param model the OpenRouter model identifier to use
     * @return structured response identifying the screen type and available actions
     */
    suspend fun identifyScreen(screenshotBase64: String, model: String): ScreenIdentificationResult
}
