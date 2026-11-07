package com.deve.assistant

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Garde les clés API chiffrées avec une clé du coffre Android (Keystore) :
 * elles ne sont plus lisibles en clair dans les fichiers de l'appli.
 */
object Secure {
    private const val ALIAS = "deve_keys"
    private const val PROVIDER = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(prefs: SharedPreferences, name: String, value: String) {
        val v = value.trim()
        val stored = if (v.isEmpty()) {
            ""
        } else {
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key())
                val ct = c.doFinal(v.toByteArray(Charsets.UTF_8))
                "enc:" + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
            } catch (e: Exception) {
                "plain:$v"   // repli si le coffre est indisponible
            }
        }
        prefs.edit().putString("sec_$name", stored).remove("key_$name").apply()
    }

    fun get(prefs: SharedPreferences, name: String): String {
        val s = prefs.getString("sec_$name", null)
        if (s == null) {
            // Ancienne clé non chiffrée : on la migre vers le coffre.
            val old = prefs.getString("key_$name", "")?.trim().orEmpty()
            if (old.isNotEmpty()) put(prefs, name, old)
            return old
        }
        return try {
            when {
                s.startsWith("enc:") -> {
                    val raw = Base64.decode(s.substring(4), Base64.NO_WRAP)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
                    String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
                }
                s.startsWith("plain:") -> s.substring(6)
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun has(prefs: SharedPreferences, name: String): Boolean = get(prefs, name).isNotEmpty()
}
