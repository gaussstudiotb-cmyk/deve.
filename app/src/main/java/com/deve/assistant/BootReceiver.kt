package com.deve.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Relance DeVE au démarrage du téléphone (si l'option est activée). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = ctx.getSharedPreferences("deve", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", true)) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            ctx.startForegroundService(Intent(ctx, DeveService::class.java))
        } catch (e: Exception) {
            Log.e("DeVE", "Démarrage auto refusé", e)
            DeveService.notifyRestart(ctx)
        }
    }
}
