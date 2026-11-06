package com.deve.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Sert uniquement à revenir à l'accueil (fermer l'appli au premier plan).
 * Android interdit à une appli d'en « tuer » une autre : on la renvoie en arrière-plan.
 */
class DeveAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DeveAccessibilityService? = null

        fun goHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
