package com.deve.assistant

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Le cerveau de DeVE : envoie la phrase à Gemini (gratuit) ou à Claude,
 * qui répond soit par du texte, soit par une action à exécuter.
 */
class Brain(private val prefs: SharedPreferences) {

    companion object {
        // "-latest" suit automatiquement le dernier modèle Flash de Google.
        const val GEMINI_MODEL = "gemini-flash-latest"
        const val CLAUDE_MODEL = "claude-haiku-5-5"
        private const val TAG = "DeVE"
    }

    class Call(val name: String, val args: JSONObject)
    class Result(val text: String?, val call: Call?)

    private class Param(val name: String, val type: String, val desc: String, val required: Boolean = true)
    private class Tool(val name: String, val desc: String, val params: List<Param>)

    private val tools = listOf(
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
        )
    )

    fun ask(text: String): Result {
        val provider = prefs.getString("provider", "gemini") ?: "gemini"
        val key = prefs.getString("key_$provider", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            return Result("Il manque la clé API. Ouvre l'appli DeVE pour la saisir.", null)
        }
        return try {
            if (provider == "claude") claude(text, key) else gemini(text, key)
        } catch (e: HttpException) {
            Log.e(TAG, "HTTP ${e.code}: ${e.message}")
            Result(
                when (e.code) {
                    400, 401, 403 -> "La clé API est refusée. Vérifie-la dans l'appli DeVE."
                    429 -> "La limite gratuite est atteinte. Réessaie dans une minute."
                    else -> "Le service ne répond pas correctement."
                }, null
            )
        } catch (e: UnknownHostException) {
            Result("Je n'ai pas de connexion internet.", null)
        } catch (e: SocketTimeoutException) {
            Result("Le service met trop de temps à répondre.", null)
        } catch (e: Exception) {
            Log.e(TAG, "Brain error", e)
            Result("Une erreur est survenue.", null)
        }
    }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy 'à' HH:mm", Locale.FRANCE).format(Date())
        return "Tu es DeVE, l'assistant vocal d'un téléphone Android. Tu parles français. " +
            "Tes réponses sont lues à voix haute : une ou deux phrases courtes, sans markdown ni émojis. " +
            "Quand l'utilisateur demande une action que tes fonctions permettent, appelle la fonction " +
            "au lieu d'en parler. Sinon, réponds directement. Nous sommes le $now."
    }

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
    private fun gemini(text: String, key: String): Result {
        val decls = JSONArray()
        tools.forEach { t ->
            val d = JSONObject().put("name", t.name).put("description", t.desc)
            if (t.params.isNotEmpty()) d.put("parameters", schema(t, true))
            decls.put(d)
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            )
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))

        val res = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            mapOf("x-goog-api-key" to key), body
        )
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: return Result("Je n'ai pas compris.", null)

        var out = ""
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                return Result(null, Call(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()))
            }
            out += p.optString("text")
        }
        return Result(out.trim().ifEmpty { "Je n'ai pas compris." }, null)
    }

    // ---------------- Claude ----------------
    private fun claude(text: String, key: String): Result {
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject().put("name", t.name).put("description", t.desc)
                    .put("input_schema", schema(t, false))
            )
        }
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", 400)
            .put("system", system())
            .put("tools", arr)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))

        val res = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body
        )
        val content = res.optJSONArray("content") ?: return Result("Je n'ai pas compris.", null)

        var out = ""
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "tool_use" -> return Result(
                    null, Call(b.getString("name"), b.optJSONObject("input") ?: JSONObject())
                )
                "text" -> out += b.optString("text")
            }
        }
        return Result(out.trim().ifEmpty { "Je n'ai pas compris." }, null)
    }

    // ---------------- HTTP ----------------
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
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
