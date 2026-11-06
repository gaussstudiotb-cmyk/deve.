package com.deve.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var keyField: EditText
    private lateinit var journal: TextView
    private lateinit var memoryView: TextView
    private var provider = "gemini"
    private val ui = Handler(Looper.getMainLooper())

    private val violet = Color.parseColor("#5B2A9E")
    private val rose = Color.parseColor("#B0306A")
    private val soft = Color.parseColor("#CDBBE8")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(26).toFloat()
        }
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("deve", MODE_PRIVATE)
        provider = prefs.getString("provider", "gemini") ?: "gemini"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
        }

        root.addView(label("DeVE", 44f, Color.parseColor("#E0579A")).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(label("Ton assistant vocal. Dis « DeVE » puis ta demande.", 15f, soft))

        status = label("", 14f).apply { setPadding(0, dp(18), 0, dp(6)) }
        root.addView(status)

        root.addView(button("1. Autoriser micro, contacts, appels", violet) { askPermissions() })
        root.addView(button("2. Autoriser l'orbe flottante", violet) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Activer l'accessibilité (fermer les applis)", violet) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("4. Batterie : ne pas restreindre DeVE", violet) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        root.addView(label("Cerveau de DeVE", 16f).apply { setPadding(0, dp(24), 0, dp(4)) })

        val rbG = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Gemini (gratuit)"
            setTextColor(Color.WHITE)
        }
        val rbC = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Claude"
            setTextColor(Color.WHITE)
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbG)
            addView(rbC)
        }
        root.addView(group)

        keyField = EditText(this).apply {
            hint = "Clé API"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(keyField)
        root.addView(label("Clé Gemini gratuite : aistudio.google.com/apikey", 12f, soft))

        group.setOnCheckedChangeListener { _, id ->
            provider = if (id == rbC.id) "claude" else "gemini"
            prefs.edit().putString("provider", provider).apply()
            keyField.setText(prefs.getString("key_$provider", ""))
            refresh()
        }
        group.check(if (provider == "claude") rbC.id else rbG.id)

        root.addView(button("Enregistrer la clé", rose) {
            prefs.edit().putString("key_$provider", keyField.text.toString().trim()).apply()
            Toast.makeText(this, "Clé enregistrée", Toast.LENGTH_SHORT).show()
            refresh()
        })

        root.addView(android.widget.Switch(this).apply {
            text = "Démarrer DeVE automatiquement"
            setTextColor(Color.WHITE)
            setPadding(0, dp(22), 0, 0)
            isChecked = prefs.getBoolean("autostart", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("autostart", on).apply() }
        })
        root.addView(label("À l'ouverture de l'appli et au démarrage du téléphone.", 12f, soft))
        root.addView(android.widget.Switch(this).apply {
            text = "Bip à l'activation"
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, 0)
            isChecked = prefs.getBoolean("beep", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("beep", on).apply() }
        })

        root.addView(button("▶  Démarrer DeVE", rose) { startDeve(false) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
        root.addView(button("🎙  Parler maintenant", violet) { startDeve(true) })
        root.addView(button("■  Arrêter DeVE", Color.parseColor("#3A2A55")) {
            stopService(Intent(this, DeveService::class.java))
            ui.postDelayed({ refresh() }, 500)
        })

        root.addView(label("Mémoire de DeVE", 14f).apply { setPadding(0, dp(30), 0, 0) })
        root.addView(label("Dis « DeVE, retiens que… » ou raconte-lui des choses sur toi.", 12f, soft))
        memoryView = label("", 11f, Color.parseColor("#9C8AB8")).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(memoryView)
        root.addView(button("Effacer toute la mémoire", Color.parseColor("#7A1F3D")) { confirmClear() })

        root.addView(label("Journal de DeVE", 14f).apply { setPadding(0, dp(30), 0, 0) })
        root.addView(label("Si quelque chose bloque, envoie-moi une capture de ce journal.", 12f, soft))
        root.addView(button("Actualiser le journal", Color.parseColor("#3A2A55")) { refresh() })
        journal = label("", 11f, Color.parseColor("#9C8AB8")).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(journal)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1A0B2E"))
            addView(root)
        })

        autoStartIfReady()
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Effacer la mémoire ?")
            .setMessage("DeVE oubliera tout ce qu'il a retenu sur toi.")
            .setPositiveButton("Effacer") { _, _ ->
                Memory.clear(this)
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun autoStartIfReady() {
        if (!prefs.getBoolean("autostart", true) || DeveService.running) return
        if (!has(Manifest.permission.RECORD_AUDIO)) return
        if (prefs.getString("key_$provider", "").isNullOrBlank()) return
        startDeve(false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun startDeve(talkNow: Boolean) {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Autorise d'abord le micro (étape 1).", Toast.LENGTH_LONG).show()
            return
        }
        if (prefs.getString("key_$provider", "").isNullOrBlank()) {
            Toast.makeText(this, "Pense à saisir une clé API.", Toast.LENGTH_LONG).show()
        }
        val i = Intent(this, DeveService::class.java)
        if (talkNow) i.action = DeveService.ACTION_TRIGGER
        startForegroundService(i)
        ui.postDelayed({ refresh() }, 700)
    }

    private fun refresh() {
        fun m(ok: Boolean) = if (ok) "✅" else "⬜"
        val keyOk = !prefs.getString("key_$provider", "").isNullOrBlank()
        status.text =
            "${m(has(Manifest.permission.RECORD_AUDIO))} Micro   " +
                "${m(has(Manifest.permission.READ_CONTACTS))} Contacts   " +
                "${m(has(Manifest.permission.CALL_PHONE))} Appels\n" +
                "${m(Settings.canDrawOverlays(this))} Orbe flottante\n" +
                "${m(DeveAccessibilityService.instance != null)} Accessibilité\n" +
                "${m(keyOk)} Clé API\n" +
                "${m(DeveService.running)} DeVE actif"
        if (::memoryView.isInitialized) {
            val facts = Memory.facts(this)
            memoryView.text = if (facts.isEmpty()) {
                "(vide pour l'instant)"
            } else {
                "${facts.size} souvenir(s)\n" + facts.takeLast(15).joinToString("\n") { "• ${it.text}" }
            }
        }
        if (::journal.isInitialized) {
            journal.text = prefs.getString("trace", "")?.ifBlank { "(vide pour l'instant)" }
        }
    }
}
