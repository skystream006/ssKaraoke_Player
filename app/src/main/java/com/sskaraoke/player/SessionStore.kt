package com.sskaraoke.player

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) : AutoCloseable {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session.encrypted"))
    private val writer = Executors.newSingleThreadExecutor()
    var recoveryNeeded = false
        private set
    @Volatile var current: SavedSession = read()
        private set
    @Volatile var writeFailed = false
        private set

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("sskaraoke.session.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("sskaraoke.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun read(): SavedSession {
        if (!file.baseFile.exists()) return SavedSession()
        return try {
            val data = file.readFully()
            require(data.size in 30..65536 && data[0].toInt() == 1 && data[1].toInt() == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(2, 14)))
            SavedSession.fromJson(JSONObject(String(cipher.doFinal(data.copyOfRange(14, data.size)), Charsets.UTF_8)))
        } catch (_: Exception) {
            recoveryNeeded = true
            SavedSession()
        }
    }

    fun update(session: SavedSession) {
        if (session == current) return
        current = session
        writer.execute { flush() }
    }

    @Synchronized fun flush() {
        var output: java.io.FileOutputStream? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(current.toJson().toString().toByteArray(Charsets.UTF_8))
            output = file.startWrite()
            output.write(byteArrayOf(1, cipher.iv.size.toByte()))
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
            writeFailed = false
        } catch (_: Exception) {
            file.failWrite(output)
            writeFailed = true
        }
    }

    override fun close() { writer.shutdown() }
}