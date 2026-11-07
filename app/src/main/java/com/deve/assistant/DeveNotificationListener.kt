package com.deve.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Garde en mémoire les dernières notifications reçues (si tu as activé l'accès) pour pouvoir les lire. */
class DeveNotificationListener : NotificationListenerService() {

    class Item(val app: String, val title: String, val text: String)

    companion object {
        @Volatile
        var connected = false

        private val items = ArrayList<Item>()

        fun recent(n: Int): List<Item> = synchronized(items) { items.takeLast(n).reversed() }
    }

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        synchronized(items) {
            items.add(Item(app, title, text))
            while (items.size > 30) items.removeAt(0)
        }
    }
}
