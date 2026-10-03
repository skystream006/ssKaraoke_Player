package com.sskaraoke.player

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class AppVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        fun parse(text: String): AppVersion? {
            val parts = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(text)?.groupValues ?: return null
            return AppVersion(parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null, parts[3].toLongOrNull() ?: return null)
        }
    }
}

data class ReleaseInfo(val tag: String, val version: AppVersion, val url: HttpUrl, val size: Long, val sha256: String?) {
    val versionName: String get() = tag.removePrefix("v")
    val fileName: String get() = "ssKaraoke-Player-$tag.apk"

    companion object {
        const val MAX_APK_SIZE = 200L * 1024 * 1024

        fun parse(json: JSONObject, repository: String = BuildConfig.UPDATE_REPOSITORY): ReleaseInfo {
            require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "This is not a stable release." }
            val tag = json.getString("tag_name")
            require(tag.startsWith("v")) { "Invalid release tag." }
            val version = AppVersion.parse(tag) ?: throw IllegalArgumentException("Invalid release version.")
            val name = "ssKaraoke-Player-$tag.apk"
            val assets = json.getJSONArray("assets")
            val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            require(matching.size == 1) { "The release does not contain one universal ssKaraoke APK." }
            val asset = matching.single()
            val url = asset.getString("browser_download_url").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid update address.")
            val expected = "https://github.com/$repository/releases/download/".toHttpUrl().newBuilder()
                .addPathSegment(tag).addPathSegment(name).build()
            require(url == expected) { "Update must come from this app's GitHub release." }
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_SIZE) { "Invalid APK size." }
            val digest = asset.optString("digest", "").takeIf { it.isNotBlank() && it != "null" }?.let {
                require(Regex("sha256:[a-fA-F0-9]{64}").matches(it)) { "Invalid release checksum." }
                it.removePrefix("sha256:").lowercase()
            }
            return ReleaseInfo(tag, version, url, size, digest)
        }
    }
}

data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String, val signers: Set<String>)

object UpdateSecurity {
    fun validateUpgrade(installed: ApkIdentity, archive: ApkIdentity, release: ReleaseInfo) {
        require(archive.packageName == installed.packageName) { "This APK belongs to a different application." }
        require(archive.versionName == release.versionName && archive.versionCode > installed.versionCode) {
            "This APK is not a newer version of the installed app."
        }
        require(installed.signers.isNotEmpty() && archive.signers == installed.signers) {
            "Signing certificate mismatch. Obtain an update signed with the same key; do not uninstall and lose your saved login."
        }
    }

    fun verifyFile(file: File, release: ReleaseInfo) {
        require(file.isFile && file.length() == release.size) { "The APK download is incomplete." }
        release.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32768)
                var count = input.read(buffer)
                while (count >= 0) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            require(digest.digest().hex() == expected) { "The APK checksum does not match the release." }
        }
    }

    @Suppress("DEPRECATION")
    fun verifyApk(context: Context, file: File, release: ReleaseInfo) {
        verifyFile(file, release)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("The downloaded file is not an installable APK.")
        validateUpgrade(identity(installed), identity(archive), release)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkIdentity(
            info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.versionName.orEmpty(),
            signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}