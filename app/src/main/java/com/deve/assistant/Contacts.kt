package com.deve.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

object Contacts {

    private fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Numéro du contact dont le nom ressemble le plus à ce qui a été dit. */
    fun find(ctx: Context, who: String): String? {
        if (!granted(ctx)) return null
        val t = Memory.norm(who)
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
                val name = Memory.norm(c.getString(0) ?: continue)
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

    /** Nom du contact pour un numéro (null si inconnu ou sans autorisation). */
    fun nameFor(ctx: Context, number: String): String? {
        if (!granted(ctx)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
