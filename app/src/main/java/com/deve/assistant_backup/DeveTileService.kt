package com.deve.assistant

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Tuile « DeVE » dans les réglages rapides : un geste pour activer ou arrêter l'assistant. */
class DeveTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val i = Intent(this, DeveService::class.java)
        if (DeveService.running) {
            stopService(i)
        } else {
            try {
                startForegroundService(i)
            } catch (e: Exception) {
                Log.e("DeVE", "Tuile : démarrage refusé", e)
                DeveService.notifyRestart(this)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 700)
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (DeveService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "DeVE"
        t.updateTile()
    }
}
