package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ServantTarget
import io.github.fate_grand_automata.scripts.models.SkillSpamConfig
import io.github.fate_grand_automata.scripts.models.SkillSpamTarget
import io.github.fate_grand_automata.scripts.models.SpamConfigPerTeamSlot
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.skills
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class SkillSpam @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val spamConfig: SpamConfigPerTeamSlot,
    private val caster: Caster
) : IFgoAutomataApi by api {
    companion object {
        val skillSpamDelay = 0.25.seconds
        val skillReadyRecheckDelay = 0.1.seconds
        val cooldownRegex = Regex("""\d+""")

        /**
         * Similarity threshold for the `剩` cooldown-glyph template match.
         * Validated empirically (Experiment D v3, see
         * .auto-dev/investigation/ocr-report.md): 27/27 cells classified
         * correctly across mixed-state captures with a safe margin of 0.092.
         * 0.25 sits in the middle of the safe band 0.20..0.30.
         */
        const val COOLDOWN_GLYPH_SIMILARITY = 0.25
    }

    fun spamSkills() {
        for (servantSlot in FieldSlot.list) {
            val skills = servantSlot.skills()
            val teamSlot = servantTracker.deployed[servantSlot] ?: continue
            val servantSpamConfig = spamConfig[teamSlot]

            servantSpamConfig.skills.forEachIndexed { skillIndex, skillSpamConfig ->
                if (caster.canSpam(skillSpamConfig.spam) && (state.stage + 1) in skillSpamConfig.waves) {
                    val skill = skills[skillIndex]
                    val skillImage = servantTracker
                        .checkImages[teamSlot]
                        ?.skills
                        ?.getOrNull(skillIndex)

                    if (skillImage != null) {
                        // Some delay for skill icon to be loaded
                        skillSpamDelay.wait()

                        if (isReadyForSpam(skill, skillImage)) {
                            val target = skillSpamConfig.determineTarget(servantSlot)

                            caster.castServantSkill(skill, target)
                        }
                    }
                }
            }
        }
    }

    private fun SkillSpamConfig.determineTarget(fieldSlot: FieldSlot) =
        when (target) {
            SkillSpamTarget.None -> null
            SkillSpamTarget.Self -> when (fieldSlot) {
                FieldSlot.A -> ServantTarget.A
                FieldSlot.B -> ServantTarget.B
                FieldSlot.C -> ServantTarget.C
            }

            SkillSpamTarget.Slot1 -> ServantTarget.A
            SkillSpamTarget.Slot2 -> ServantTarget.B
            SkillSpamTarget.Slot3 -> ServantTarget.C
            SkillSpamTarget.Left -> ServantTarget.Left
            SkillSpamTarget.Right -> ServantTarget.Right
        }

    private fun isReadyForSpam(skill: Skill.Servant, skillImage: Pattern): Boolean {
        fun checkReady() = skillImage in locations.battle.imageRegion(skill) && !hasCooldownText(skill)

        if (!useSameSnapIn { checkReady() }) {
            return false
        }

        skillReadyRecheckDelay.wait()

        return useSameSnapIn { checkReady() }
    }

    private fun hasCooldownText(skill: Skill.Servant): Boolean {
        val region = locations.battle.cooldownTextRegion(skill)

        // Primary (TW only): template-match the `剩` cooldown glyph.
        // The glyph is rendered as part of `剩餘 N` below the icon on TW;
        // the template-match sidesteps OCR's failure mode on thin "1"
        // digits (see .auto-dev/investigation/ocr-report.md). Asset is only
        // bundled under assets/Tw, so guard the lookup to avoid hitting the
        // En fallback (which has no asset).
        if (prefs.gameServer is GameServer.Tw) {
            if (region.detectOutlinedTextHasMatch(
                    images[Images.CooldownText],
                    similarity = COOLDOWN_GLYPH_SIMILARITY
                )
            ) {
                return true
            }
        }

        // Fallback: OCR-based check. Preserves prior behaviour on EN/JP/KR/CN
        // and acts as a safety net on TW when the template match misses.
        val text = region
            .detectText(outlinedText = true)
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')

        val cooldown = cooldownRegex
            .find(text)
            ?.value
            ?.toIntOrNull()

        return cooldown != null && cooldown > 0
    }
}
