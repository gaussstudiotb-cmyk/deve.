package com.deve.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

object SmsSendSkill : Skill {
    override val name = "envoyer_sms"
    override val description =
        "Envoie un SMS à un contact ou à un numéro. DeVE demande toujours confirmation avant l'envoi."
    override val params = listOf(
        SkillParam("contact", "string", "Nom du contact ou numéro de téléphone"),
        SkillParam("message", "string", "Texte du SMS")
    )

    override fun plan(ctx: Context, args: JSONObject): Plan {
        val who = args.optString("contact").trim()
        val body = args.optString("message").trim()
        if (who.isEmpty() || body.isEmpty()) return Plan("Il me faut un destinataire et un message.")
        if (!ctx.has(Manifest.permission.SEND_SMS)) {
            return Plan("Je n'ai pas l'autorisation d'envoyer des SMS. Accorde-la dans l'appli DeVE.")
        }
        val isNumber = who.count { it.isDigit() } >= 6
        val number: String
        if (isNumber) {
            number = who.filter { it.isDigit() || it == '+' }
        } else {
            if (!ctx.has(Manifest.permission.READ_CONTACTS)) {
                return Plan("Je n'ai pas accès à tes contacts. Accorde l'autorisation dans l'appli DeVE.")
            }
            number = Contacts.find(ctx, who) ?: return Plan("Je ne trouve pas $who dans tes contacts.")
        }
        val label = if (isNumber) "ce numéro" else who
        return Plan(
            "Message envoyé à $label.",
            confirm = "Je vais envoyer à $label : $body. Tu confirmes ?",
            strict = true
        ) {
            val sm = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
        }
    }
}

object SmsReadSkill : Skill {
    override val name = "lire_sms"
    override val description = "Lit à voix haute les derniers SMS reçus."
    override val params = listOf(
        SkillParam("nombre", "integer", "Nombre de messages à lire (3 par défaut)", false)
    )

    override fun plan(ctx: Context, args: JSONObject): Plan {
        if (!ctx.has(Manifest.permission.READ_SMS)) {
            return Plan("Je n'ai pas l'autorisation de lire tes SMS. Accorde-la dans l'appli DeVE.")
        }
        val n = args.optInt("nombre", 3).coerceIn(1, 5)
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < n) {
                    val addr = c.getString(0) ?: continue
                    val text = c.getString(1) ?: ""
                    val who = Contacts.nameFor(ctx, addr) ?: addr
                    val hour = SimpleDateFormat("HH 'h' mm", Locale.FRANCE).format(Date(c.getLong(2)))
                    out.add("De $who, à $hour : $text")
                }
            }
        } catch (e: Exception) {
            return Plan("Je n'arrive pas à lire tes messages.")
        }
        if (out.isEmpty()) return Plan("Tu n'as aucun message.")
        return Plan(out.joinToString(". ")).apply { privateReply = true }
    }
}
