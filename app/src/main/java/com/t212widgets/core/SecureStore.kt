package com.t212widgets.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for the Trading 212 API key.
 *
 * The key is sealed with AES-256/GCM using a secret that lives inside the Android
 * Keystore. On devices with a secure element or TEE that secret is hardware-backed and
 * is never exposed to the app process, to `adb backup`, or to anything reading the app's
 * data directory: only the ciphertext is ever written to disk.
 *
 * Design notes:
 *  - `setUserAuthenticationRequired` is deliberately NOT set on the keystore key. Widgets
 *    refresh in the background, where no biometric prompt can be shown; requiring auth for
 *    decryption would break every background refresh. The optional biometric lock in
 *    [Settings.requireAuthToReveal] instead guards *reading the key back into the UI*,
 *    which is the operation an attacker with an unlocked phone would actually want.
 *  - A fresh random IV is generated per encryption and stored alongside the ciphertext.
 *  - Nothing here ever logs, and the plaintext is only materialised inside the network
 *    layer for the duration of a request.
 */
object SecureStore {

    private const val PREFS = "t212_secure"
    private const val KEY_ALIAS = "t212_api_key_v1"
    private const val PREF_CIPHERTEXT = "api_key_ct"
    private const val PREF_IV = "api_key_iv"
    private const val PREF_FINGERPRINT = "api_key_fp"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Keep the key usable while the screen is locked so background
                    // widget refreshes keep working in the user's pocket.
                    setUserAuthenticationRequired(false)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** True when an API key has been stored. Cheap: does not decrypt anything. */
    fun hasApiKey(context: Context): Boolean =
        prefs(context).contains(PREF_CIPHERTEXT)

    /**
     * A short, non-reversible fingerprint of the stored key, safe to show in the UI so the
     * user can tell which key is installed without ever displaying the key itself.
     */
    fun fingerprint(context: Context): String? =
        prefs(context).getString(PREF_FINGERPRINT, null)

    fun saveApiKey(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

        prefs(context).edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREF_FINGERPRINT, maskOf(apiKey))
            .commit()
    }

    /**
     * Returns the decrypted API key, or null when none is stored or the keystore entry has
     * been invalidated (which happens if the user removes their device lock on some OEMs).
     */
    fun readApiKey(context: Context): String? {
        val p = prefs(context)
        val ct = p.getString(PREF_CIPHERTEXT, null) ?: return null
        val iv = p.getString(PREF_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Wipes the stored key and destroys the keystore secret that protected it. */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(PREF_CIPHERTEXT)
            .remove(PREF_IV)
            .remove(PREF_FINGERPRINT)
            .commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /** `abcd…wxyz` style mask used anywhere the key would otherwise be shown. */
    fun maskOf(apiKey: String): String {
        val trimmed = apiKey.trim()
        if (trimmed.length <= 8) return "•".repeat(trimmed.length.coerceAtLeast(4))
        return trimmed.take(4) + "…" + trimmed.takeLast(4)
    }
}
