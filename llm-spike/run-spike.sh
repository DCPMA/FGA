#!/bin/bash
# LLM Screen Understanding Spike — Screenshot Capture & Analysis
#
# This script captures screenshots from the ADB emulator and sends them
# to OpenRouter for screen identification using multiple models.
#
# Prerequisites:
#   - ADB connected to emulator (emulator-5564 / localhost:5565)
#   - OPENROUTER_API_KEY environment variable set
#
# Usage:
#   export OPENROUTER_API_KEY=sk-or-...
#   ./llm-spike/run-spike.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCREENSHOTS_DIR="$SCRIPT_DIR/screenshots"
RESULTS_DIR="$SCRIPT_DIR/results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Check prerequisites
if [ -z "${OPENROUTER_API_KEY:-}" ]; then
    echo "ERROR: OPENROUTER_API_KEY environment variable is not set."
    echo "Get a key at https://openrouter.ai/keys"
    exit 1
fi

if ! adb devices | grep -q "device$"; then
    echo "ERROR: No ADB device connected."
    exit 1
fi

mkdir -p "$SCREENSHOTS_DIR" "$RESULTS_DIR"

# Models to test
MODELS=(
    "anthropic/claude-sonnet-4"
    "openai/gpt-4o-mini"
    "deepseek/deepseek-chat-v3-0324"
)

# Capture a screenshot
capture_screenshot() {
    local name="$1"
    local output="$SCREENSHOTS_DIR/${name}.png"
    echo "  Capturing screenshot: $name"
    adb -s emulator-5564 exec-out screencap -p > "$output"
    echo "  Saved: $output ($(wc -c < "$output") bytes)"
    echo "$output"
}

# Send screenshot to OpenRouter for analysis
analyze_screenshot() {
    local screenshot_path="$1"
    local model="$2"
    local screenshot_name
    screenshot_name=$(basename "$screenshot_path" .png)

    echo "  Analyzing with model: $model"

    # Base64 encode the screenshot
    local base64_data
    base64_data=$(base64 -i "$screenshot_path" | tr -d '\n')

    # Build the system prompt (JSON-escaped)
    local system_prompt
    system_prompt=$(python3 -c "
import json
prompt = '''You are an expert game screen analyzer for Fate/Grand Order (FGO), a mobile RPG game.
Your job is to identify the current game screen from a screenshot and provide structured information.

You MUST respond with ONLY a valid JSON object (no markdown, no explanation outside JSON) matching this exact schema:

{
  \"screen_type\": \"<one of: HOME, QUEST_SELECT, EVENT, SUPPORT_SELECT, PARTY_SETUP, BATTLE, CARD_SELECT, QUEST_REWARD, BOND_RESULT, MASTER_EXP, SUMMON, PRESENT_BOX, SHOP, ENHANCEMENT, DIALOG, LOADING, LOGIN_REWARD, AP_REFILL, ERROR, UNKNOWN>\",
  \"confidence\": <number from 0.0 to 1.0>,
  \"description\": \"<brief description of what is currently shown on screen>\",
  \"visible_elements\": [\"<list of UI elements visible on screen>\"],
  \"suggested_actions\": [\"<list of actions a player could take on this screen>\"]
}

Key screen identification rules:
- BATTLE: Shows servants on a battlefield with HP bars, skill icons at bottom, and enemy HP bars at top. The BATTLE text and turn counter are visible in the upper right.
- CARD_SELECT: Shows 5 command cards (Buster/Arts/Quick) at the bottom for selection during battle.
- HOME: Main menu with master avatar, news, and navigation buttons.
- QUEST_SELECT: List of available quests/nodes, typically with AP cost shown.
- EVENT: Event-specific banners, quest lists, or event shop — distinguished by event-themed UI.
- SUPPORT_SELECT: Grid/list of friend/support servants to choose before a quest.
- PARTY_SETUP: Team formation screen showing 6 servant slots before starting a quest.
- QUEST_REWARD: Shows drops/rewards after completing a quest, with items displayed.
- BOND_RESULT: Shows bond points gained with each servant after a quest.
- MASTER_EXP: Shows master EXP gained, possibly level up.
- DIALOG: Any popup, confirmation dialog, or overlay on top of another screen.
- AP_REFILL: Stamina/AP recovery dialog asking to use apples or saint quartz.
- ERROR: Connection error, retry prompt, or maintenance notice.
- LOADING: Loading screen with progress indicator or Now Loading text.

Be precise. If you see battle UI elements (HP bars, skill icons, BATTLE text), it is BATTLE, not CARD_SELECT.
If you see command cards for selection, it is CARD_SELECT.'''
print(json.dumps(prompt))
")

    # Build the request JSON
    local request_json
    request_json=$(python3 -c "
import json
data = {
    'model': '$model',
    'messages': [
        {
            'role': 'system',
            'content': $system_prompt
        },
        {
            'role': 'user',
            'content': [
                {
                    'type': 'text',
                    'text': 'Analyze this Fate/Grand Order screenshot and identify the current game screen. Respond with ONLY the JSON object as specified.'
                },
                {
                    'type': 'image_url',
                    'image_url': {
                        'url': 'data:image/png;base64,$base64_data'
                    }
                }
            ]
        }
    ],
    'max_tokens': 1024,
    'temperature': 0.1
}
print(json.dumps(data))
")

    local result_file="$RESULTS_DIR/${screenshot_name}_${model//\//_}_${TIMESTAMP}.json"

    local start_time
    start_time=$(python3 -c "import time; print(int(time.time() * 1000))")

    # Call OpenRouter API
    local response
    response=$(curl -s -w "\n%{http_code}" \
        -X POST "https://openrouter.ai/api/v1/chat/completions" \
        -H "Authorization: Bearer $OPENROUTER_API_KEY" \
        -H "Content-Type: application/json" \
        -H "HTTP-Referer: https://github.com/Fate-Grand-Automata/FGA" \
        -H "X-Title: FGA LLM Spike" \
        -d "$request_json")

    local end_time
    end_time=$(python3 -c "import time; print(int(time.time() * 1000))")
    local latency_ms=$((end_time - start_time))

    local http_code
    http_code=$(echo "$response" | tail -1)
    local body
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" != "200" ]; then
        echo "    ERROR: HTTP $http_code"
        echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
        echo "{\"error\": \"HTTP $http_code\", \"body\": $(echo "$body" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))')}" > "$result_file"
        return 1
    fi

    # Extract and display result
    local llm_content
    llm_content=$(echo "$body" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data['choices'][0]['message']['content']
# Strip markdown fences if present
content = content.strip()
if content.startswith('\`\`\`json'):
    content = content[7:]
if content.startswith('\`\`\`'):
    content = content[3:]
if content.endswith('\`\`\`'):
    content = content[:-3]
content = content.strip()
print(content)
")

    local usage
    usage=$(echo "$body" | python3 -c "
import sys, json
data = json.load(sys.stdin)
u = data.get('usage', {})
print(json.dumps(u))
")

    # Save structured result
    python3 -c "
import json
result = {
    'screenshot': '$screenshot_name',
    'model': '$model',
    'latency_ms': $latency_ms,
    'http_code': $http_code,
    'llm_response': json.loads('''$llm_content'''),
    'usage': json.loads('''$usage'''),
    'raw_response': json.loads('''$(echo "$body" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")''')
}
with open('$result_file', 'w') as f:
    json.dump(result, f, indent=2)
" 2>/dev/null || echo "$body" > "$result_file"

    # Display summary
    local screen_type confidence
    screen_type=$(echo "$llm_content" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('screen_type','PARSE_ERROR'))" 2>/dev/null || echo "PARSE_ERROR")
    confidence=$(echo "$llm_content" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('confidence','?'))" 2>/dev/null || echo "?")

    echo "    Screen: $screen_type (confidence: $confidence)"
    echo "    Latency: ${latency_ms}ms"
    echo "    Usage: $usage"
    echo "    Result saved: $result_file"
}

echo "=== FGA LLM Screen Understanding Spike ==="
echo "Timestamp: $TIMESTAMP"
echo ""

# Step 1: Capture current screenshot
echo "--- Step 1: Capturing screenshots ---"
CURRENT_SCREENSHOT=$(capture_screenshot "spike_${TIMESTAMP}")
echo ""

# Step 2: Test with each model
echo "--- Step 2: Testing with multiple models ---"
for model in "${MODELS[@]}"; do
    echo ""
    echo "Model: $model"
    echo "---"
    analyze_screenshot "$CURRENT_SCREENSHOT" "$model" || echo "  (Failed for this model)"
    echo ""
done

# Step 3: Also test with any existing screenshots
echo "--- Step 3: Testing with any pre-existing screenshots ---"
for screenshot in "$SCREENSHOTS_DIR"/*.png; do
    [ "$screenshot" = "$CURRENT_SCREENSHOT" ] && continue
    [ -f "$screenshot" ] || continue
    screenshot_name=$(basename "$screenshot" .png)
    echo "Screenshot: $screenshot_name"
    for model in "${MODELS[@]}"; do
        echo ""
        echo "  Model: $model"
        analyze_screenshot "$screenshot" "$model" || echo "    (Failed)"
    done
    echo ""
done

echo ""
echo "=== Spike Complete ==="
echo "Screenshots: $SCREENSHOTS_DIR"
echo "Results: $RESULTS_DIR"
echo ""
echo "Next: Review results in $RESULTS_DIR and check accuracy, latency, cost."
