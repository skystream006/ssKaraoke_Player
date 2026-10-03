import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun gitOutput(vararg arguments: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *arguments)
}.standardOutput.asText.get().trim()

require(gitOutput("rev-parse", "--is-shallow-repository") == "false") {
    "Versioning requires full Git history. Run git fetch --unshallow."
}
val revision = gitOutput("rev-list", "--first-parent", "--count", "HEAD").toInt()
val version = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
val firstAppRevision = version.getProperty("firstAppRevision").toInt()
val patch = version.getProperty("patch").toInt() + (revision - firstAppRevision).coerceAtLeast(0)
val appVersion = "${version.getProperty("major")}.${version.getProperty("minor").padStart(2, '0')}.${patch.toString().padStart(2, '0')}"
val signingStore = providers.environmentVariable("APK_SIGNING_STORE_FILE").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.sskaraoke.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sskaraoke.player"
        minSdk = 26
        targetSdk = 36
        versionCode = revision.coerceAtLeast(firstAppRevision)
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPOSITORY", "\"skystream006/ssKaraoke_Player\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (signingStore != null) create("distribution") {
            storeFile = file(signingStore)
            storePassword = providers.environmentVariable("APK_SIGNING_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("APK_SIGNING_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("APK_SIGNING_KEY_PASSWORD").get()
            require(!storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
                "All APK_SIGNING_* values must be configured together."
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingStore == null) "debug" else "distribution")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.15.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}