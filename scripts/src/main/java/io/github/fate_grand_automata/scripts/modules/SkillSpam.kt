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

        // Match the fixed TW 剩餘 prefix instead of recognizing a thin digit.
        // The 34x18 template is captured at FGA's 1280x720 image scale.
        // Offline 16:9 fixtures include cooldown 1 and ready skills; wider
        // layouts and other TW UI versions still need target validation.
        const val COOLDOWN_GLYPH_SIMILARITY = 0.60
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

        // TW: match the fixed 剩餘 prefix using its own image-scale region.
        // Leave the existing OCR region below unchanged. Only TW has this
        // template asset, so keep the lookup inside the server guard.
        if (prefs.gameServer is GameServer.Tw) {
            val glyphRegion = locations.battle.cooldownGlyphRegion(skill)
            if (glyphRegion.detectOutlinedTextHasMatch(
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
