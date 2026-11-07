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
class Plan(
    val message: String,
    /** true : on parle d'abord, puis on exécute (utile pour l'enregistrement, pour ne pas s'enregistrer soi-même). */
    val afterSpeech: Boolean = false,
    /** Si renseigné : DeVE pose cette question et attend « oui » avant d'agir. */
    val confirm: String? = null,
    /** true : confirmation obligatoire, même si les confirmations d'appel sont désactivées. */
    val strict: Boolean = false,
    val run: (() -> Unit)? = null
) {
    /** true : le message contient des données privées (SMS, agenda...) : on ne le garde pas dans l'historique. */
    var privateReply: Boolean = false
}

class Actions(private val ctx: Context, private val svc: DeveService) {

    fun plan(name: String, a: JSONObject): Plan = when (name) {
        "appeler" -> call(a.optString("contact"))
        "lancer_app" -> launch(a.optString("app"))
        "quitter_app" -> quit()
        "enregistrer_audio" -> record(a.optInt("duree_secondes", 60))
        "alarme" -> alarm(a.optInt("heure", -1), a.optInt("minute", 0), a.optString("message"))
        "memoriser" -> remember(a.optString("fait"))
        "oublier" -> forget(a.optString("mot_cle"))
        "fin" -> Plan(if (a.optBoolean("merci")) "Avec plaisir !" else "D'accord.")
        "fournisseur" -> switchProvider(a.optString("nom"))
        else -> Skills.find(name)?.plan(ctx, a) ?: Plan("Je ne sais pas encore faire ça.")
    }

    // ---------- Changer de cerveau ----------
    private fun switchProvider(nom: String): Plan {
        val prefs = ctx.getSharedPreferences("deve", Context.MODE_PRIVATE)
        val current = prefs.getString("provider", "gemini") ?: "gemini"
        val target = when (nom.lowercase(Locale.ROOT)) {
            "claude" -> "claude"
            "gemini" -> "gemini"
            else -> if (current == "gemini") "claude" else "gemini"
        }
        val label = if (target == "claude") "Claude" else "Gemini"
        if (!Secure.has(prefs, target)) {
            return Plan("Tu n'as pas encore saisi de clé pour $label. Fais-le dans l'appli DeVE.")
        }
        return Plan("D'accord, j'utilise maintenant $label.") {
            prefs.edit().putString("provider", target).apply()
        }
    }

    // ---------- Mémoire ----------
    private fun remember(fact: String): Plan {
        if (fact.isBlank()) return Plan("Que dois-je retenir ?")
        return Plan("C'est noté.") { Memory.add(ctx, fact) }
    }

    private fun forget(keyword: String): Plan {
        val k = Memory.norm(keyword)
        if (k.isEmpty()) return Plan("Que dois-je oublier ?")
        if (k == "tout" || k == "tous" || k.contains("memoire")) {
            return Plan("Pour tout effacer, utilise le bouton dans l'appli DeVE.")
        }
        val n = Memory.count(ctx, keyword)
        if (n == 0) return Plan("Je n'avais rien noté à ce sujet.")
        return Plan(if (n == 1) "D'accord, j'ai oublié ce souvenir." else "D'accord, j'ai oublié $n souvenirs.") {
            Memory.remove(ctx, keyword)
        }
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
        val label = if (isNumber) "ce numéro" else who
        return Plan("J'appelle $label.", confirm = "Tu veux que j'appelle $label ?") {
            go(
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
            go(
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
        return Plan("J'enregistre pendant $dur. Touche l'orbe pour arrêter.", afterSpeech = true) { svc.startRecording(s) }
    }

    // ---------- Alarme ----------
    private fun alarm(h: Int, m: Int, label: String): Plan {
        if (h !in 0..23 || m !in 0..59) return Plan("Je n'ai pas compris l'heure de l'alarme.")
        val spoken = "Alarme réglée à ${h} heures" + if (m > 0) " $m" else ""
        return Plan("$spoken.") {
            go(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "DeVE" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Lance une activité. Depuis un service en arrière-plan, Android bloque parfois le lancement :
     * on passe par le service d'accessibilité quand il est actif, car il y est autorisé.
     */
    private fun go(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a11y = DeveAccessibilityService.instance
        if (a11y != null) {
            try {
                a11y.startActivity(intent)
                return
            } catch (e: Exception) {
                // repli ci-dessous
            }
        }
        ctx.startActivity(intent)
    }
}
