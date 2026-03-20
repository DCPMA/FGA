package io.github.fate_grand_automata.scripts.llm

/**
 * Known FGO screen types that the LLM can identify.
 */
enum class ScreenType {
    /** Main home/menu screen with master info and menu buttons */
    HOME,

    /** Quest/story selection screen */
    QUEST_SELECT,

    /** Event-specific quest or banner screen */
    EVENT,

    /** Support servant selection screen */
    SUPPORT_SELECT,

    /** Party/team setup screen before a quest */
    PARTY_SETUP,

    /** Active battle screen with servants and command cards */
    BATTLE,

    /** Command card selection phase of battle */
    CARD_SELECT,

    /** Battle result / quest reward screen */
    QUEST_REWARD,

    /** Bond result screen after quest completion */
    BOND_RESULT,

    /** Master EXP / level up screen */
    MASTER_EXP,

    /** Friend point / saint quartz summon screen */
    SUMMON,

    /** Present box / gift box screen */
    PRESENT_BOX,

    /** Shop screen */
    SHOP,

    /** Enhancement / leveling screen */
    ENHANCEMENT,

    /** Dialog / popup / confirmation overlay */
    DIALOG,

    /** Loading screen or transition */
    LOADING,

    /** Login bonus or campaign notification */
    LOGIN_REWARD,

    /** AP refill / stamina recovery dialog */
    AP_REFILL,

    /** Connection error or retry screen */
    ERROR,

    /** Screen type could not be determined */
    UNKNOWN
}
