package com.sskaraoke.player

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionStoreDeviceTest {
    @Test fun keystoreEncryptedSessionSurvivesRecreation() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "vault-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir(): File = directory }
        val saved = SavedSession(origin = "https://karaoke.example", token = "device-test-token", level = "member",
            password = "device-test-password", username = "Alex", memberId = "member-1", route = "/guest/party-1/member-1")
        try {
            SessionStore(context).use { store -> store.update(saved); store.flush() }
            val encrypted = File(directory, "session.encrypted").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(encrypted.contains(saved.password))
            assertFalse(encrypted.contains(saved.token))
            SessionStore(context).use { reopened ->
                assertEquals(saved, reopened.current)
                reopened.update(saved.signedOut())
                reopened.flush()
            }
            SessionStore(context).use { reopened -> assertEquals("", reopened.current.password); assertEquals("", reopened.current.token) }
        } finally { directory.deleteRecursively() }
    }
}