package com.deve.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Le cerveau de DeVE : Gemini ou Claude, avec basculement automatique sur l'autre si le premier échoue,
 * la mémoire à long terme, l'historique (base SQLite partagée par les deux) et les compétences.
 * Deux profils : « rapide » pour les phrases courtes, « réfléchi » pour les vraies questions.
 */
class Brain(private val ctx: Context, private val prefs: SharedPreferences) {

    companion object {
        // "-latest" suit automatiquement le dernier modèle Flash de Google.
        const val GEMINI_MODEL = "gemini-flash-latest"
        const val CLAUDE_FAST = "claude-haiku-5-5"
        const val CLAUDE_SMART = "claude-sonnet-5-5"
        private const val TAG = "DeVE"
        private const val MEMORY_MS = 30 * 60 * 1000L
        private const val MAX_TURNS = 10
    }

    class Call(val name: String, val args: JSONObject)

    /** text : ce que l'IA a dit (peut accompagner une action) ; call : l'action demandée. */
    class Result(val text: String?, val call: Call?)

    private class Param(val name: String, val type: String, val desc: String, val required: Boolean = true)
    private class Tool(val name: String, val desc: String, val params: List<Param>)
    private class Turn(val user: String, val reply: String)

    /** Dernier fournisseur qui a réellement répondu (pour le journal). */
    @Volatile
    var lastProvider: String = "gemini"
        private set

    private val builtIn = listOf(
        Tool(
            "appeler", "Appelle une personne de ses contacts ou un numéro de téléphone.",
            listOf(Param("contact", "string", "Nom du contact ou numéro de téléphone"))
        ),
        Tool(
            "lancer_app", "Ouvre une application installée sur le téléphone.",
            listOf(Param("app", "string", "Nom de l'application, par exemple WhatsApp ou YouTube"))
        ),
        Tool(
            "quitter_app", "Quitte l'application actuellement ouverte et revient à l'écran d'accueil.",
            emptyList()
        ),
        Tool(
            "enregistrer_audio", "Enregistre le son du micro dans un fichier audio.",
            listOf(Param("duree_secondes", "integer", "Durée en secondes (60 par défaut)", false))
        ),
        Tool(
            "alarme", "Règle une alarme à une heure précise.",
            listOf(
                Param("heure", "integer", "Heure de 0 à 23"),
                Param("minute", "integer", "Minute de 0 à 59"),
                Param("message", "string", "Nom de l'alarme", false)
            )
        ),
        Tool(
            "memoriser",
            "Enregistre dans ta mémoire à long terme un fait durable sur l'utilisateur " +
                "(prénom, proches, projets, goûts, habitudes, rendez-vous important).",
            listOf(Param("fait", "string", "Une phrase courte et autonome, par exemple « Il s'appelle Joan »"))
        ),
        Tool(
            "oublier", "Supprime de ta mémoire les souvenirs qui contiennent un mot-clé.",
            listOf(Param("mot_cle", "string", "Mot-clé du souvenir à supprimer"))
        )
    )

    // Les compétences (SMS, agenda, notifications...) sont déclarées automatiquement.
    private val tools: List<Tool> = builtIn + Skills.all.map { s ->
        Tool(s.name, s.description, s.params.map { Param(it.name, it.type, it.desc, it.required) })
    }

    // ------------------------------------------------------------ historique (SQLite)

    private fun historyOn() = prefs.getBoolean("history_on", true)

    fun remember(user: String, reply: String) {
        if (!historyOn()) return
        try {
            Db.get(ctx).addMessage(lastProvider, user, reply)
        } catch (e: Exception) {
            Log.e(TAG, "historique", e)
        }
    }

    private fun recentTurns(): List<Turn> {
        if (!historyOn()) return emptyList()
        return try {
            Db.get(ctx).recent(System.currentTimeMillis() - MEMORY_MS, MAX_TURNS)
                .map { Turn(it.first, it.second) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ profils

    private fun plain(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val question = Regex(
        "(?:^| )(?:pourquoi|comment|explique|expliquer|qu est ce|c est quoi|quelle|quel|quelles|quels|" +
            "combien|difference|compare|resume|raconte|aide moi|a quoi sert|que penses|ton avis|conseille)(?: |$)"
    )

    private fun isComplex(t: String) = t.length >= 45 || t.contains('?') || question.containsMatchIn(plain(t))

    private fun system(smart: Boolean, query: String): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy 'à' HH:mm", Locale.FRANCE).format(Date())
        val facts = Memory.relevant(ctx, query)
        val mem = if (facts.isEmpty()) {
            "Ta mémoire à long terme est vide pour l'instant."
        } else {
            "Ta mémoire à long terme (faits notés sur l'utilisateur ; utilise-les naturellement, " +
                "sans les réciter sans raison) :\n" + facts.joinToString("\n") { "- $it" }
        }
        val style = if (smart) {
            "La question demande de la réflexion : raisonne avec soin, donne d'abord l'essentiel puis explique " +
                "en trois à six phrases claires, avec un exemple simple si c'est utile. Si tu n'es pas sûr, " +
                "ou si l'information peut avoir changé récemment, dis-le franchement. "
        } else {
            "Réponds de façon naturelle, en une à trois phrases, comme dans une vraie conversation. "
        }
        return "Tu es DeVE, l'assistant personnel vocal de l'utilisateur, sur son téléphone Android. " +
            "Tu tutoies, tu parles français, tu es chaleureux, curieux et direct. " +
            "Tes réponses sont lues à voix haute : pas de markdown, pas de listes à puces, pas d'émojis, " +
            "pas d'adresses web. " + style +
            "Quand l'utilisateur te demande une action que tes fonctions permettent, appelle la fonction. " +
            "Quand il t'apprend un fait durable (son prénom, ses proches, ses projets, ses goûts, ses habitudes, " +
            "un rendez-vous important), appelle aussi la fonction memoriser avec une phrase courte et autonome, " +
            "en plus de lui répondre. Tu n'as pas accès à internet en direct. Nous sommes le $now.\n" + mem
    }

    // ------------------------------------------------------------ appel avec basculement

    fun ask(text: String): Result {
        val primary = prefs.getString("provider", "gemini") ?: "gemini"
        val secondary = if (primary == "gemini") "claude" else "gemini"
        val usable = listOf(primary, secondary).filter { Secure.has(prefs, it) }
        if (usable.isEmpty()) {
            return Result("Il manque la clé API. Ouvre l'appli DeVE pour la saisir.", null)
        }
        val smart = isComplex(text)
        var last = Result("Une erreur est survenue.", null)
        for (p in usable) {
            try {
                val key = Secure.get(prefs, p)
                val r = if (p == "claude") claude(text, key, smart) else gemini(text, key, smart)
                lastProvider = p
                return Result(r.text?.let { cleanForSpeech(it) }, r.call)
            } catch (e: Exception) {
                Log.e(TAG, "Échec avec $p", e)
                last = errorResult(e)
                if (e is UnknownHostException) break   // pas d'internet : inutile d'essayer l'autre
            }
        }
        return last
    }

    private fun errorResult(e: Exception): Result = when (e) {
        is HttpException -> Result(
            when (e.code) {
                400, 401, 403 -> "La clé API est refusée. Vérifie-la dans l'appli DeVE."
                429 -> "La limite gratuite est atteinte. Réessaie dans une minute."
                else -> "Le service ne répond pas correctement."
            }, null
        )
        is UnknownHostException -> Result("Je n'ai pas de connexion internet.", null)
        is SocketTimeoutException -> Result("Le service met trop de temps à répondre.", null)
        else -> Result("Une erreur est survenue.", null)
    }

    private fun cleanForSpeech(s: String): String =
        s.replace(Regex("[*#`_]+"), "")
            .replace(Regex("\\s*\\n+\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun schema(t: Tool, upper: Boolean): JSONObject {
        val props = JSONObject()
        t.params.forEach { p ->
            props.put(
                p.name,
                JSONObject()
                    .put("type", if (upper) p.type.uppercase() else p.type)
                    .put("description", p.desc)
            )
        }
        return JSONObject()
            .put("type", if (upper) "OBJECT" else "object")
            .put("properties", props)
            .put("required", JSONArray(t.params.filter { it.required }.map { it.name }))
    }

    // ---------------- Gemini ----------------
    private fun gemini(text: String, key: String, smart: Boolean): Result {
        val decls = JSONArray()
        tools.forEach { t ->
            val d = JSONObject().put("name", t.name).put("description", t.desc)
            if (t.params.isNotEmpty()) d.put("parameters", schema(t, true))
            decls.put(d)
        }
        val contents = JSONArray()
        recentTurns().forEach { t ->
            contents.put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", t.user)))
            )
            contents.put(
                JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", t.reply)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))
        )
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(smart, text))))
            )
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        val headers = mapOf("x-goog-api-key" to key)

        // Phrases courtes : on coupe la « réflexion » (plus rapide). Vraies questions : on la garde.
        val configs: List<JSONObject?> = if (smart) {
            listOf(JSONObject().put("maxOutputTokens", 2048), null)
        } else {
            listOf(
                JSONObject().put("maxOutputTokens", 400)
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)),
                JSONObject().put("maxOutputTokens", 400)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal")),
                null
            )
        }
        var res: JSONObject? = null
        for ((i, cfg) in configs.withIndex()) {
            if (cfg != null) body.put("generationConfig", cfg) else body.remove("generationConfig")
            try {
                res = post(url, headers, body)
                break
            } catch (e: HttpException) {
                if (e.code != 400 || i == configs.lastIndex) throw e
            }
        }
        val parts = res?.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: return Result("Je n'ai pas compris.", null)

        var out = ""
        var call: Call? = null
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val fc = p.optJSONObject("functionCall")
            if (fc != null && call == null) {
                call = Call(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
            }
            out += p.optString("text")
        }
        val spoken = out.trim().ifEmpty { null }
        if (spoken == null && call == null) return Result("Je n'ai pas compris.", null)
        return Result(spoken, call)
    }

    // ---------------- Claude ----------------
    private fun claude(text: String, key: String, smart: Boolean): Result {
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject().put("name", t.name).put("description", t.desc)
                    .put("input_schema", schema(t, false))
            )
        }
        val messages = JSONArray()
        recentTurns().forEach { t ->
            messages.put(JSONObject().put("role", "user").put("content", t.user))
            messages.put(JSONObject().put("role", "assistant").put("content", t.reply))
        }
        messages.put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject()
            .put("model", if (smart) CLAUDE_SMART else CLAUDE_FAST)
            .put("max_tokens", if (smart) 1500 else 400)
            .put("system", system(smart, text))
            .put("tools", arr)
            .put("messages", messages)

        val res = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body
        )
        val content = res.optJSONArray("content") ?: return Result("Je n'ai pas compris.", null)

        var out = ""
        var call: Call? = null
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "tool_use" -> if (call == null) {
                    call = Call(b.getString("name"), b.optJSONObject("input") ?: JSONObject())
                }
                "text" -> out += b.optString("text")
            }
        }
        val spoken = out.trim().ifEmpty { null }
        if (spoken == null && call == null) return Result("Je n'ai pas compris.", null)
        return Result(spoken, call)
    }

    // ---------------- HTTP ----------------
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpException(code, text.take(300))
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
