package app.pulso.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Device-bound encrypted storage, deliberately excluded from the music JSON backup. */
internal class SocialVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pulso-social.v1"))
    private val alias = "pulso.social.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!file.baseFile.exists()) { "No se puede recuperar la clave local de Mensajes." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val data = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 16_000_000); output.write(buffer, 0, count) }
            output.toByteArray()
        }
        require(data.size in 29..16_000_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return JSONObject(cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8))
    }
    fun write(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        require(payload.size <= 16_000_000)
        val output = file.startWrite()
        try { output.write(payload); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
}
