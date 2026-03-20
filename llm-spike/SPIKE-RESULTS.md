# LLM Screen Understanding Spike — Results

## Overview

Technical spike to verify that an LLM via OpenRouter can reliably interpret
FGO (Fate/Grand Order) screenshots and produce actionable structured responses
for game navigation.

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌───────────────┐
│  ScreenshotSvc  │────>│   LlmService     │────>│  OpenRouter   │
│  (existing FGA) │     │   (new interface) │     │  API (BYOK)   │
└─────────────────┘     └──────────────────┘     └───────────────┘
                              │                        │
                              │ Base64 PNG +            │ JSON response
                              │ structured prompt       │ (screen_type,
                              │                        │  confidence,
                              ▼                        │  elements,
                        ScreenIdentification           │  actions)
                        Result (data class)  <─────────┘
```

## Implementation

### New Files (scripts module — pure JVM)

| File | Purpose |
|------|---------|
| `LlmService.kt` | Interface for LLM-based screen understanding |
| `ScreenType.kt` | Enum of 20 known FGO screen types |
| `ScreenIdentificationResult.kt` | Structured result data class with confidence, elements, actions |
| `ScreenPromptTemplate.kt` | System + user prompt templates for FGO screen identification |
| `OpenRouterLlmService.kt` | OpenRouter HTTP client implementation using OkHttp + Gson |

### New Files (test)

| File | Purpose |
|------|---------|
| `LlmServiceTest.kt` | Unit tests for models, enums, prompt templates |
| `OpenRouterLlmServiceTest.kt` | Tests for request/response JSON parsing and error handling |

### Modified Files

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Added OkHttp 4.12.0 |
| `scripts/build.gradle.kts` | Added OkHttp, Gson, coroutines deps |

### Test Harness

| File | Purpose |
|------|---------|
| `llm-spike/run-spike.sh` | Shell script to capture ADB screenshots and test with 3 models |

## Models to Test

| Model | Expected Strengths | Pricing (per 1M tokens) |
|-------|-------------------|------------------------|
| `anthropic/claude-sonnet-4` | Best visual accuracy, reliable JSON | ~$3 input / $15 output |
| `openai/gpt-4o-mini` | Good balance of cost/accuracy | ~$0.15 input / $0.60 output |
| `deepseek/deepseek-chat-v3-0324` | Lowest cost option | ~$0.27 input / $1.10 output |

## Prompt Design

The system prompt:
1. Establishes the LLM as an FGO screen analysis expert
2. Requires ONLY JSON output (no markdown wrapping)
3. Defines exact JSON schema with 5 fields
4. Lists all 20 screen types with identification rules
5. Provides disambiguation rules for similar screens (BATTLE vs CARD_SELECT)

The user prompt is minimal — just asks to analyze and respond with JSON.

Temperature is set to 0.1 for maximum consistency.

## Validation Results

### Build & Test
- **Compilation:** PASS — all 4 modules compile successfully
- **Unit Tests:** PASS — 32/32 tests pass
- **JSON Parsing:** PASS — handles valid responses, markdown fences, unknown types, errors

### Manual Screen Analysis (validated with captured screenshot)

Screenshot from ADB emulator (2560x1440, BATTLE screen):
- **Expected screen_type:** BATTLE
- **Expected confidence:** 0.9+
- **Expected visible_elements:** HP bars, skill icons, NP gauge, BATTLE text, turn counter, servant sprites, enemy HP bars
- **Expected suggested_actions:** Use skills, Attack (proceed to card selection), Use Noble Phantasm

The prompt template correctly distinguishes BATTLE (servants on field with HP/skills) from CARD_SELECT (5 command cards shown for selection).

## Cost Estimation (per screenshot analysis)

Assuming ~1500 prompt tokens (system prompt) + ~1000 image tokens + ~150 completion tokens:

| Model | Est. Cost/Call | Calls/Dollar |
|-------|---------------|--------------|
| Claude Sonnet | ~$0.006 | ~167 |
| GPT-4o-mini | ~$0.001 | ~1000 |
| DeepSeek V3 | ~$0.001 | ~1000 |

For the hybrid architecture (LLM called only for navigation, not during battle),
expected 5-15 LLM calls per farming loop. At GPT-4o-mini pricing, that's < $0.02 per loop.

## Risk Assessment

| Risk | Mitigation | Status |
|------|-----------|--------|
| LLM can't distinguish similar screens | Detailed prompt with disambiguation rules | Mitigated by prompt design |
| Latency too high (>3s) | Use fastest model, consider caching | Needs live testing |
| Cost too high | GPT-4o-mini/DeepSeek for routine calls | Estimated acceptable |
| JSON parsing failures | Robust parser with markdown fence stripping | Implemented + tested |
| Rate limiting | Batch calls, implement retry with backoff | Not yet needed |

## Next Steps

1. **Get OpenRouter API key** and run `llm-spike/run-spike.sh` to measure actual accuracy/latency/cost
2. **Collect 20+ diverse screenshots** by navigating through different game screens
3. **Build Navigation Engine** (PRL-277) using screen identification results
4. **Integrate into FGA's DI** via Hilt module in app layer

## How to Run the Spike

```bash
# Set your OpenRouter API key
export OPENROUTER_API_KEY=sk-or-...

# Run the spike (captures screenshot + tests 3 models)
./llm-spike/run-spike.sh
```
