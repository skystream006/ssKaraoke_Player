package com.sskaraoke.player

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun releaseJson(tag: String = "v0.01.01", size: Long = 3): JSONObject = JSONObject()
        .put("tag_name", tag).put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "ssKaraoke-Player-$tag.apk")
            .put("size", size).put("browser_download_url", "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/$tag/ssKaraoke-Player-$tag.apk")))

    @Test fun paddedVersionsCompareNumerically() {
        assertEquals(AppVersion.parse("0.1.0"), AppVersion.parse("v0.01.00"))
        assertTrue(AppVersion.parse("v0.01.10")!! > AppVersion.parse("0.01.09")!!)
        assertNull(AppVersion.parse("v0.01.01-beta"))
        assertNull(AppVersion.parse("v99999999999999999999999.1.1"))
        assertNull(AppVersion.parse("1.2"))
    }

    @Test fun releaseMustContainTheExactStableUniversalApk() {
        val release = ReleaseInfo.parse(releaseJson())
        assertEquals("ssKaraoke-Player-v0.01.01.apk", release.fileName)
        assertEquals("0.01.01", release.versionName)
        for (flag in listOf("draft", "prerelease")) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(releaseJson().put(flag, true)) }
        }
        val external = releaseJson()
        external.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/update.apk")
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(external) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(releaseJson(size = ReleaseInfo.MAX_APK_SIZE + 1)) }
    }

    @Test fun installsRequireMatchingPackageNewerCodeAndIdenticalSigners() {
        val release = ReleaseInfo.parse(releaseJson())
        val installed = ApkIdentity("com.sskaraoke.player", 2, "0.01.00", setOf("certificate"))
        val archive = installed.copy(versionCode = 3, versionName = "0.01.01")
        UpdateSecurity.validateUpgrade(installed, archive, release)
        listOf(
            archive.copy(packageName = "another.app"), archive.copy(versionCode = 2),
            archive.copy(versionName = "0.01.99"), archive.copy(signers = setOf("other")), archive.copy(signers = emptySet())
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.validateUpgrade(installed, invalid, release) }
        }
    }

    @Test fun downloadCompletesOnlyAfterSizeAndDigestVerification() = runBlocking {
        val json = releaseJson()
        json.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("abc".toResponseBody()).build()
        }.build()
        var finalProgress = 0L
        val downloaded = UpdateClient(client).download(ReleaseInfo.parse(json), temporary.newFolder()) { received, _ -> finalProgress = received }
        assertEquals("abc", downloaded.readText())
        assertEquals(3L, finalProgress)
        assertFalse(downloaded.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun latestReadsAnOrdinaryReleaseResponseWithoutExpectingPadding() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("api.github.com", chain.request().url.host)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(releaseJson().toString().toResponseBody()).build()
        }.build()
        assertEquals("v0.01.01", UpdateClient(client).latest()!!.tag)
    }

    @Test fun noPublishedReleaseIsNotAnError() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not found")
                .body("{}".toResponseBody()).build()
        }.build()
        assertNull(UpdateClient(client).latest())
    }

    @Test fun truncatedDownloadsAreRemoved() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ab".toResponseBody()).build()
        }.build()
        val directory = temporary.newFolder()
        try {
            UpdateClient(client).download(ReleaseInfo.parse(releaseJson()), directory) { _, _ -> }
            fail("Expected size rejection")
        } catch (_: IllegalArgumentException) { assertEquals(0, directory.listFiles()!!.size) }
    }

    @Test fun downloadNeverFollowsAnHttpOrUnrelatedHostRedirect() = runBlocking {
        for (destination in listOf("http://github.com/unsafe", "https://evil.example/update.apk")) {
            var requests = 0
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", destination).body("".toResponseBody()).build()
            }.build()
            try {
                UpdateClient(client).download(ReleaseInfo.parse(releaseJson()), temporary.newFolder()) { _, _ -> }
                fail("Expected redirect rejection")
            } catch (_: IllegalArgumentException) { assertEquals(1, requests) }
        }
    }

    @Test fun invalidChecksumsAreRejected() {
        val json = releaseJson()
        json.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:bad")
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(json) }
        json.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:${"0".repeat(64)}")
        val file = temporary.newFile().apply { writeText("abc") }
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.verifyFile(file, ReleaseInfo.parse(json)) }
    }
}