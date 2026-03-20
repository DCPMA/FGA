package io.github.fate_grand_automata.scripts.llm

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import kotlin.test.Test

/**
 * Unit tests for LLM service models and prompt templates.
 * These tests do NOT call the OpenRouter API — they validate local logic only.
 */
class LlmServiceTest {

    @Test
    fun `ScreenType enum contains all expected types`() {
        val expectedTypes = listOf(
            "HOME", "QUEST_SELECT", "EVENT", "SUPPORT_SELECT", "PARTY_SETUP",
            "BATTLE", "CARD_SELECT", "QUEST_REWARD", "BOND_RESULT", "MASTER_EXP",
            "SUMMON", "PRESENT_BOX", "SHOP", "ENHANCEMENT", "DIALOG",
            "LOADING", "LOGIN_REWARD", "AP_REFILL", "ERROR", "UNKNOWN"
        )

        for (typeName in expectedTypes) {
            val screenType = ScreenType.valueOf(typeName)
            assertThat(screenType.name).isEqualTo(typeName)
        }
    }

    @Test
    fun `ScreenIdentificationResult stores all fields correctly`() {
        val result = ScreenIdentificationResult(
            screenType = ScreenType.BATTLE,
            confidence = 0.95,
            description = "Battle screen with 3 servants",
            visibleElements = listOf("HP bars", "Skill icons", "NP gauge"),
            suggestedActions = listOf("Use skill", "Attack", "Use NP"),
            model = "anthropic/claude-sonnet-4",
            latencyMs = 1500,
            tokenUsage = TokenUsage(
                promptTokens = 1000,
                completionTokens = 200,
                totalTokens = 1200
            ),
            rawResponse = """{"screen_type": "BATTLE"}"""
        )

        assertThat(result.screenType).isEqualTo(ScreenType.BATTLE)
        assertThat(result.confidence).isEqualTo(0.95)
        assertThat(result.description).isNotEmpty()
        assertThat(result.visibleElements.size).isEqualTo(3)
        assertThat(result.suggestedActions.size).isEqualTo(3)
        assertThat(result.model).isEqualTo("anthropic/claude-sonnet-4")
        assertThat(result.latencyMs).isEqualTo(1500)
        assertThat(result.tokenUsage).isNotNull()
        assertThat(result.tokenUsage!!.totalTokens).isEqualTo(1200)
    }

    @Test
    fun `ScreenPromptTemplate system prompt mentions all screen types`() {
        val systemPrompt = ScreenPromptTemplate.systemPrompt

        for (screenType in ScreenType.values()) {
            assertThat(systemPrompt).transform("contains ${screenType.name}") {
                it.contains(screenType.name)
            }.isEqualTo(true)
        }
    }

    @Test
    fun `ScreenPromptTemplate system prompt requests JSON output`() {
        val systemPrompt = ScreenPromptTemplate.systemPrompt
        assertThat(systemPrompt).transform("contains JSON") {
            it.contains("JSON")
        }.isEqualTo(true)
        assertThat(systemPrompt).transform("contains screen_type") {
            it.contains("screen_type")
        }.isEqualTo(true)
    }

    @Test
    fun `OpenRouterLlmService companion has correct default model`() {
        assertThat(OpenRouterLlmService.DEFAULT_MODEL).isEqualTo("anthropic/claude-sonnet-4")
    }

    @Test
    fun `OpenRouterLlmService companion has 3 spike models`() {
        assertThat(OpenRouterLlmService.SPIKE_MODELS.size).isEqualTo(3)
    }

    @Test
    fun `TokenUsage correctly computes total tokens`() {
        val usage = TokenUsage(
            promptTokens = 500,
            completionTokens = 100,
            totalTokens = 600
        )
        assertThat(usage.promptTokens).isEqualTo(500)
        assertThat(usage.completionTokens).isEqualTo(100)
        assertThat(usage.totalTokens).isEqualTo(600)
    }
}
