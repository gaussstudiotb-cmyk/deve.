package com.deve.assistant

import android.content.Context
import org.json.JSONObject

class SkillParam(val name: String, val type: String, val desc: String, val required: Boolean = true)

/**
 * Une compétence est un module indépendant : sa déclaration pour l'IA et ce qu'elle fait.
 * Pour ajouter une capacité à DeVE : créer un objet qui implémente Skill et l'ajouter à Skills.all.
 */
interface Skill {
    val name: String
    val description: String
    val params: List<SkillParam>
    fun plan(ctx: Context, args: JSONObject): Plan
}

object Skills {
    val all: List<Skill> = listOf(
        SmsSendSkill, SmsReadSkill, CalendarAddSkill, CalendarReadSkill, NotificationsSkill
    )

    fun find(name: String): Skill? = all.firstOrNull { it.name == name }
}
