package com.deve.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Ce que DeVE dit à voix haute, puis exécute une fois la phrase terminée. */
class Plan(val message: String, val run: (() -> Unit)? = null)

class Actions(private val ctx: Context, private val svc: DeveService) {

    fun plan(name: String, a: JSONObject): Plan = when (name) {
        "appeler" -> call(a.optString("contact"))
        "lancer_app" -> launch(a.optString("app"))
        "quitter_app" -> quit()
        "enregistrer_audio" -> record(a.optInt("duree_secondes", 60))
        "alarme" -> alarm(a.optInt("heure", -1), a.optInt("minute", 0), a.optString("message"))
        else -> Plan("Je ne sais pas encore faire ça.")
    }

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    // ---------- Appeler ----------
    private fun call(who: String): Plan {
        if (who.isBlank()) return Plan("Qui dois-je appeler ?")
        if (!has(Manifest.permission.CALL_PHONE)) {
            return Plan("Je n'ai pas l'autorisation d'appeler. Accorde-la dans l'appli DeVE.")
        }
        val isNumber = who.count { it.isDigit() } >= 6
        val number: String
        if (isNumber) {
            number = who.filter { it.isDigit() || it == '+' }
        } else {
            if (!has(Manifest.permission.READ_CONTACTS)) {
                return Plan("Je n'ai pas accès à tes contacts. Accorde l'autorisation dans l'appli DeVE.")
            }
            number = lookupContact(who) ?: return Plan("Je ne trouve pas $who dans tes contacts.")
        }
        return Plan("J'appelle ${if (isNumber) "ce numéro" else who}.") {
            ctx.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun lookupContact(who: String): String? {
        val t = norm(who)
        if (t.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = norm(c.getString(0) ?: continue)
                val number = c.getString(1) ?: continue
                val s = when {
                    name == t -> 4
                    name.split(" ").contains(t) -> 3
                    name.startsWith(t) -> 2
                    name.contains(t) -> 1
                    else -> 0
                }
                if (s > bestScore) {
                    bestScore = s
                    best = number
                }
            }
        }
        return best
    }

    // ---------- Lancer une appli ----------
    private fun launch(name: String): Plan {
        val t = norm(name)
        if (t.isEmpty()) return Plan("Quelle application dois-je ouvrir ?")
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var bestPkg: String? = null
        var bestCls: String? = null
        var bestLabel = ""
        var bestScore = 0
        for (ri in pm.queryIntentActivities(q, 0)) {
            val label = ri.loadLabel(pm).toString()
            val l = norm(label)
            if (l.isEmpty()) continue
            val s = when {
                l == t -> 4
                l.startsWith(t) -> 3
                l.contains(t) -> 2
                l.length >= 3 && t.contains(l) -> 1
                else -> 0
            }
            if (s > bestScore) {
                bestScore = s
                bestPkg = ri.activityInfo.packageName
                bestCls = ri.activityInfo.name
                bestLabel = label
            }
        }
        val pkg = bestPkg
        val cls = bestCls
        if (pkg == null || cls == null) return Plan("Je ne trouve pas l'application $name.")
        return Plan("J'ouvre $bestLabel.") {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        }
    }

    // ---------- Quitter l'appli ----------
    private fun quit(): Plan {
        if (DeveAccessibilityService.instance == null) {
            return Plan("Pour fermer des applis, active le service d'accessibilité DeVE dans l'appli.")
        }
        return Plan("D'accord.") { DeveAccessibilityService.goHome() }
    }

    // ---------- Enregistrer ----------
    private fun record(seconds: Int): Plan {
        val s = seconds.coerceIn(5, 3600)
        val dur = if (s >= 120) "${s / 60} minutes" else "$s secondes"
        return Plan("J'enregistre pendant $dur. Touche l'orbe pour arrêter.") { svc.startRecording(s) }
    }

    // ---------- Alarme ----------
    private fun alarm(h: Int, m: Int, label: String): Plan {
        if (h !in 0..23 || m !in 0..59) return Plan("Je n'ai pas compris l'heure de l'alarme.")
        val spoken = "Alarme réglée à ${h} heures" + if (m > 0) " $m" else ""
        return Plan("$spoken.") {
            ctx.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "DeVE" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
