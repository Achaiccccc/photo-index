package app.photoindex.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.photoindex.core.ApiKeyStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密 API Key，密文放在不参与备份的私有文件。
 * 明文不写入索引库。
 */
class KeystoreApiKeyStore(
    context: Context,
) : ApiKeyStore {
    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    override fun save(apiKey: String) {
        synchronized(this) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "GCM 初始向量长度不是 $IV_BYTES" }
            val cipherText = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(iv.size + cipherText.size)
            iv.copyInto(payload)
            cipherText.copyInto(payload, iv.size)
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeBytes(payload)
            if (!temporary.renameTo(file)) {
                file.writeBytes(payload)
                temporary.delete()
            }
        }
    }

    override fun read(): String? {
        synchronized(this) {
            if (!file.isFile || file.length() <= IV_BYTES) return null
            return try {
                val payload = file.readBytes()
                val iv = payload.copyOfRange(0, IV_BYTES)
                val cipherText = payload.copyOfRange(IV_BYTES, payload.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_BITS, iv),
                )
                cipher.doFinal(cipherText).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun clear() {
        synchronized(this) {
            if (file.exists() && !file.delete()) {
                file.writeBytes(ByteArray(0))
            }
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "api-key.bin"
        const val ALIAS = "photoindex.api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
