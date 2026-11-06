package com.deve.assistant

import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * Comprend les commandes simples sans passer par l'IA : instantané, sans quota, sans internet.
 * Tout ce qui n'est pas reconnu ici est envoyé à l'IA.
 */
object LocalParser {

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9+: ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // Mémoire : appliquée au texte brut pour garder les accents du souvenir.
    private val REMEMBER = Regex(
        "^(?:retiens|retenir|souviens[- ]toi|rappelle[- ]toi|n['’ ]oublie pas|m[ée]morise[rz]?|garde en m[ée]moire)" +
            "(?: bien)?(?: que| qu['’]| ceci| :)?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val REMEMBER2 = Regex(
        "^(?:note|noter|enregistre)(?: bien)? (?:que|qu['’]|:)\\s*(.+)$", RegexOption.IGNORE_CASE
    )
    private val FORGET = Regex(
        "^(?:oublie|oublier|efface|supprime)(?: de ta m[ée]moire)?" +
            "(?: que | qu['’]| tout ce qui concerne | ce que je t['’]ai dit (?:sur|à propos de) )?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val END = Regex(
        "^(?:merci|merci beaucoup|c est tout|ca sera tout|ca sera tout merci|stop|annule|laisse tomber|" +
            "au revoir|a plus tard|a plus|bye|tais toi|silence)$"
    )

    private val POLITE_START = Regex(
        "^(?:(?:s il te plait|stp|peux tu|tu peux|pourrais tu|veux tu|je veux|je voudrais|j aimerais|deve|dev) )+"
    )
    private val POLITE_END = Regex(" (?:s il te plait|stp|merci|maintenant)$")

    private val QUIT = Regex(
        "^(?:quitte|quitter|ferme|fermer|retourne|retour|reviens|revenir|va|aller)" +
            "(?: a| sur)?(?: l accueil| accueil)?" +
            "(?: l application| l appli| l app| cette application| cette appli| ca| tout)?$"
    )
    private val RECORD = Regex(
        "^(?:enregistre|enregistrer|commence a enregistrer|commence l enregistrement|" +
            "demarre l enregistrement|lance l enregistrement)(?: .*)?$"
    )
    private val DURATION = Regex(
        "(\\d+|une|un|deux|trois|quatre|cinq|dix|quinze|trente) (seconde|secondes|minute|minutes)"
    )
    private val ALARM_WORD = Regex("(?:^| )(?:alarme|reveil|reveille)(?: |$)")
    private val IN_TIME = Regex("dans (\\d+) (minute|minutes|heure|heures)")
    private val AT_TIME = Regex("(\\d{1,2}) ?(?:heures|heure|h|:) ?(\\d{2})?")
    private val CALL = Regex(
        "^(?:appelle|appeler|telephone a|telephone|passe un coup de fil a|donne un coup de fil a|contacte)\\s+(.+)$"
    )
    private val CALL_ART = Regex("^(?:(?:a|au|le|la|l|mon|ma|mes) )+")
    private val OPEN = Regex(
        "^(?:ouvre|ouvrir|lance|lancer|demarre|demarrer|va sur|aller sur)\\s+(.+)$"
    )
    private val OPEN_ART = Regex(
        "^(?:(?:l application|l appli|l app|application|appli|app|la|le|les|l|d|de|du|des) )+"
    )

    private val WORDS = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
        "cinq" to 5, "dix" to 10, "quinze" to 15, "trente" to 30
    )

    fun parse(raw: String): Brain.Call? {
        val r = raw.trim()
        (REMEMBER.find(r) ?: REMEMBER2.find(r))?.let { m ->
            val fact = m.groupValues[1].trim()
            if (fact.length >= 3) return Brain.Call("memoriser", JSONObject().put("fait", fact))
        }
        FORGET.find(r)?.let { m ->
            val kw = m.groupValues[1].trim()
            if (kw.isNotEmpty()) return Brain.Call("oublier", JSONObject().put("mot_cle", kw))
        }

        var t = norm(raw)
        t = POLITE_START.replace(t, "")
        t = POLITE_END.replace(t, "").trim()
        if (t.isEmpty()) return null

        if (END.matches(t)) return Brain.Call("fin", JSONObject().put("merci", t.startsWith("merci")))

        if (QUIT.matches(t)) return Brain.Call("quitter_app", JSONObject())

        if (RECORD.matches(t)) {
            var secs = 60
            DURATION.find(t)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: WORDS[m.groupValues[1]] ?: 1
                secs = if (m.groupValues[2].startsWith("minute")) n * 60 else n
            }
            return Brain.Call("enregistrer_audio", JSONObject().put("duree_secondes", secs))
        }

        if (ALARM_WORD.containsMatchIn(t)) {
            val inM = IN_TIME.find(t)
            if (inM != null) {
                val n = inM.groupValues[1].toIntOrNull() ?: return null
                val cal = Calendar.getInstance()
                cal.add(
                    if (inM.groupValues[2].startsWith("heure")) Calendar.HOUR_OF_DAY else Calendar.MINUTE, n
                )
                return Brain.Call(
                    "alarme",
                    JSONObject().put("heure", cal.get(Calendar.HOUR_OF_DAY))
                        .put("minute", cal.get(Calendar.MINUTE)).put("message", "")
                )
            }
            val at = AT_TIME.find(t)
            if (at != null) {
                val h = at.groupValues[1].toIntOrNull() ?: return null
                val min = at.groupValues[2].toIntOrNull() ?: 0
                if (h in 0..23 && min in 0..59) {
                    return Brain.Call(
                        "alarme", JSONObject().put("heure", h).put("minute", min).put("message", "")
                    )
                }
            }
            return null
        }

        CALL.find(t)?.let { m ->
            val who = CALL_ART.replace(m.groupValues[1], "").trim()
            if (who.isNotEmpty()) return Brain.Call("appeler", JSONObject().put("contact", who))
        }

        OPEN.find(t)?.let { m ->
            val app = OPEN_ART.replace(m.groupValues[1], "").trim()
            if (app.isNotEmpty()) return Brain.Call("lancer_app", JSONObject().put("app", app))
        }
        return null
    }
}
