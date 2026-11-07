package com.deve.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private fun Context.allowed(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

object CalendarAddSkill : Skill {
    override val name = "ajouter_evenement"
    override val description =
        "Ajoute un rendez-vous dans l'agenda du téléphone. Convertis toi-même les dates relatives " +
            "(demain, vendredi prochain...) en date exacte."
    override val params = listOf(
        SkillParam("titre", "string", "Titre du rendez-vous"),
        SkillParam("date", "string", "Date au format AAAA-MM-JJ"),
        SkillParam("heure", "string", "Heure au format HH:MM (09:00 par défaut)", false),
        SkillParam("duree_minutes", "integer", "Durée en minutes (60 par défaut)", false)
    )

    override fun plan(ctx: Context, args: JSONObject): Plan {
        if (!ctx.allowed(Manifest.permission.WRITE_CALENDAR) ||
            !ctx.allowed(Manifest.permission.READ_CALENDAR)
        ) {
            return Plan("Je n'ai pas l'autorisation d'accéder à ton agenda. Accorde-la dans l'appli DeVE.")
        }
        val title = args.optString("titre").trim()
        val hour = args.optString("heure", "09:00").trim().ifEmpty { "09:00" }
        val start = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
                .parse(args.optString("date").trim() + " " + hour)
        } catch (e: Exception) {
            null
        }
        if (title.isEmpty() || start == null) return Plan("Je n'ai pas compris le rendez-vous.")
        val dur = args.optInt("duree_minutes", 60).coerceIn(5, 24 * 60)
        val calId = writableCalendar(ctx) ?: return Plan("Je ne trouve pas d'agenda où écrire.")
        val label = SimpleDateFormat("EEEE d MMMM 'à' HH 'h' mm", Locale.FRANCE).format(start)
        return Plan("C'est noté : $title, $label.") {
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.time)
                put(CalendarContract.Events.DTEND, start.time + dur * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
        }
    }

    /** Un agenda où l'on peut écrire, en préférant celui du compte Google. */
    private fun writableCalendar(ctx: Context): Long? {
        var best: Long? = null
        var bestGoogle = false
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.VISIBLE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (c.getInt(3) == 0) continue
                val google = c.getString(2) == "com.google"
                if (best == null || (google && !bestGoogle)) {
                    best = c.getLong(0)
                    bestGoogle = google
                }
            }
        }
        return best
    }
}

object CalendarReadSkill : Skill {
    override val name = "lire_agenda"
    override val description = "Lit à voix haute les rendez-vous de l'agenda pour un jour."
    override val params = listOf(
        SkillParam("jour", "string", "« aujourd'hui », « demain » ou une date AAAA-MM-JJ", false)
    )

    override fun plan(ctx: Context, args: JSONObject): Plan {
        if (!ctx.allowed(Manifest.permission.READ_CALENDAR)) {
            return Plan("Je n'ai pas l'autorisation de lire ton agenda. Accorde-la dans l'appli DeVE.")
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val day = args.optString("jour").trim().lowercase(Locale.ROOT)
        var label = "aujourd'hui"
        if (day.contains("demain")) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            label = "demain"
        } else if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)?.let {
                    cal.time = it
                    label = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(it)
                }
            } catch (e: Exception) {
                // on garde aujourd'hui
            }
        }
        val begin = cal.timeInMillis
        val end = begin + 24L * 3600L * 1000L
        val items = ArrayList<String>()
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, begin)
            ContentUris.appendId(b, end)
            ctx.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, "begin ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "Sans titre"
                    if (c.getInt(2) == 1) {
                        items.add("$title, toute la journée")
                    } else {
                        val t = SimpleDateFormat("HH 'h' mm", Locale.FRANCE).format(java.util.Date(c.getLong(1)))
                        items.add("$title à $t")
                    }
                }
            }
        } catch (e: Exception) {
            return Plan("Je n'arrive pas à lire ton agenda.")
        }
        if (items.isEmpty()) return Plan("Tu n'as rien de prévu $label.")
        return Plan("$label, tu as : " + items.joinToString(", ") + ".").apply { privateReply = true }
    }
}
