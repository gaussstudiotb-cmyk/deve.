package com.deve.assistant

import android.content.Context
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mémoire à long terme de DeVE : une liste de faits, gardée sur le téléphone (un fichier texte).
 * Elle survit aux redémarrages et sert de contexte à chaque question.
 */
object Memory {
    private const val FILE = "memoire.txt"
    private const val MAX_FACTS = 400

    class Fact(val date: String, val text: String)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    @Synchronized
    fun facts(ctx: Context): List<Fact> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i < 0 || i == line.length - 1) null
            else Fact(line.substring(0, i), line.substring(i + 1).trim())
        }
    }

    private fun write(ctx: Context, list: List<Fact>) {
        file(ctx).writeText(list.joinToString("\n") { it.date + "|" + it.text.replace('\n', ' ') })
    }

    @Synchronized
    fun add(ctx: Context, fact: String): Boolean {
        val text = fact.trim().trim { it == '.' || it.isWhitespace() }
        if (text.length < 3) return false
        val n = norm(text)
        val list = facts(ctx).toMutableList()
        if (list.any { norm(it.text) == n }) return false
        list.add(Fact(SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date()), text))
        while (list.size > MAX_FACTS) list.removeAt(0)
        write(ctx, list)
        return true
    }

    // Un souvenir correspond si la phrase du mot-clé y figure, ou si tous ses mots importants y figurent.
    private fun matches(fact: String, k: String): Boolean {
        val nf = norm(fact)
        if (nf.contains(k)) return true
        val words = k.split(" ").filter { it.length >= 4 }
        return words.isNotEmpty() && words.all { nf.contains(it) }
    }

    @Synchronized
    fun count(ctx: Context, keyword: String): Int {
        val k = norm(keyword)
        if (k.isEmpty()) return 0
        return facts(ctx).count { matches(it.text, k) }
    }

    @Synchronized
    fun remove(ctx: Context, keyword: String): Int {
        val k = norm(keyword)
        if (k.isEmpty()) return 0
        val all = facts(ctx)
        val kept = all.filter { !matches(it.text, k) }
        if (kept.size != all.size) write(ctx, kept)
        return all.size - kept.size
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Les souvenirs utiles pour cette question (tous s'il y en a peu, sinon les plus pertinents et les plus récents). */
    fun relevant(ctx: Context, query: String, max: Int = 60): List<String> {
        val all = facts(ctx)
        if (all.isEmpty()) return emptyList()
        val pick: List<Fact> = if (all.size <= max) {
            all
        } else {
            val words = norm(query).split(" ").filter { it.length >= 4 }.toSet()
            val scored = all.withIndex().map { (i, f) ->
                val nf = norm(f.text)
                Triple(i, f, if (words.isEmpty()) 0 else words.count { nf.contains(it) })
            }
            val matched = scored.filter { it.third > 0 }.sortedByDescending { it.third }.take(max / 2)
            val recent = scored.takeLast(max - matched.size)
            (matched + recent).distinctBy { it.first }.sortedBy { it.first }.map { it.second }
        }
        return pick.map { "${it.text} (noté le ${it.date})" }
    }
}
