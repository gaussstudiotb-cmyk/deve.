package com.deve.assistant

import android.content.Context
import org.json.JSONObject

object NotificationsSkill : Skill {
    override val name = "lire_notifications"
    override val description = "Lit à voix haute les dernières notifications reçues sur le téléphone."
    override val params = listOf(
        SkillParam("nombre", "integer", "Nombre de notifications à lire (5 par défaut)", false)
    )

    override fun plan(ctx: Context, args: JSONObject): Plan {
        if (!DeveNotificationListener.connected) {
            return Plan("Pour lire tes notifications, active l'accès aux notifications pour DeVE dans l'appli.")
        }
        val n = args.optInt("nombre", 5).coerceIn(1, 8)
        val list = DeveNotificationListener.recent(n)
        if (list.isEmpty()) return Plan("Je n'ai aucune notification récente.")
        val text = list.joinToString(". ") { item ->
            val body = listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString(" : ")
            "${item.app}, $body"
        }
        return Plan(text).apply { privateReply = true }
    }
}
