package tools.fmc.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest (the FMC token, the watch's pairing secret, the stock authkey): AES-GCM under a key that never
 * leaves the Android Keystore, ciphertext in SharedPreferences. The iOS app keeps the same in the Keychain.
 * Backup is off in the manifest; a restore to another phone could not open these anyway.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(ALIAS, null) as SecretKey? ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    operator fun get(name: String): String? = prefs.getString(name, null)?.let { stored ->
        runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(MODE)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    /** Every name stored (the per-watch keys are `k-<watch id>` and `stock-<watch id>`). */
    fun names(): Set<String> = prefs.all.keys

    operator fun set(name: String, value: String?) {
        if (value == null) return prefs.edit().remove(name).apply()
        val c = Cipher.getInstance(MODE).apply { init(Cipher.ENCRYPT_MODE, key) }
        val raw = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
    }

    private companion object {
        const val ALIAS = "fmc-companion"
        const val MODE = "AES/GCM/NoPadding"
    }
}
