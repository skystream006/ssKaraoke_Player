package com.sskaraoke.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()) {
    @Volatile private var activeCall: Call? = null

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ssKaraoke-Player/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).also { activeCall = it }.awaitResponse().use { response ->
            if (response.code == 404) return@withContext null
            if (response.code == 403 || response.code == 429) throw IOException("GitHub's request limit was reached. Try again later.")
            if (!response.isSuccessful) throw IOException("Update check failed (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("GitHub returned an empty response.")
            require(body.contentLength() <= 2 * 1024 * 1024) { "Release response is too large." }
            val source = body.source()
            source.request(2 * 1024 * 1024L + 1)
            require(source.buffer.size <= 2 * 1024 * 1024) { "Release response is too large." }
            ReleaseInfo.parse(JSONObject(source.readUtf8()))
        }
    }

    suspend fun download(release: ReleaseInfo, directory: File, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create the update directory." }
        val partial = File(directory, "${release.fileName}.part")
        val completed = File(directory, release.fileName)
        partial.delete()
        try {
            downloadResponse(release).use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("The download is empty.")
                val declared = body.contentLength()
                require(declared < 0 || declared == release.size) { "Download size differs from the release." }
                var received = 0L
                var reportedAt = 0L
                body.byteStream().buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= release.size) { "Download exceeded its declared size." }
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - reportedAt > 100_000_000L) {
                                progress(received, release.size)
                                reportedAt = now
                            }
                        }
                    }
                }
                require(received == release.size) { "Download was interrupted. Please download again." }
            }
            currentCoroutineContext().ensureActive()
            UpdateSecurity.verifyFile(partial, release)
            if (completed.exists()) require(completed.delete()) { "Cannot replace the previous download." }
            require(partial.renameTo(completed)) { "Cannot finish the APK download." }
            progress(release.size, release.size)
            completed
        } finally {
            partial.delete()
        }
    }

    private suspend fun downloadResponse(release: ReleaseInfo): Response {
        var address = release.url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(address.isHttps && address.username.isEmpty() && address.password.isEmpty() &&
                address.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "The APK redirect is not an approved HTTPS download host."
            }
            val request = Request.Builder().url(address).header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity").header("User-Agent", "ssKaraoke-Player/${BuildConfig.VERSION_NAME}").build()
            val response = client.newCall(request).also { activeCall = it }.awaitResponse()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val destination = response.header("Location")
            response.close()
            address = destination?.let { address.resolve(it) } ?: throw IOException("The APK redirect is invalid.")
        }
        throw IOException("Too many APK redirects.")
    }

    fun cancel() { activeCall?.cancel() }
}