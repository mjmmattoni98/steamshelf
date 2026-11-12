import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing from keystore.properties (git-ignored, next to settings.gradle.kts) or, for CI, the
 * STEAMSHELF_* environment variables. Without either, release builds keep using the debug key.
 */
data class ReleaseKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val releaseKey: ReleaseKey? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    fun value(key: String, env: String): String? =
        props.getProperty(key)?.takeIf { it.isNotBlank() } ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
    val storeFile = value("storeFile", "STEAMSHELF_KEYSTORE") ?: return@run null
    ReleaseKey(
        storeFile = rootProject.file(storeFile),
        storePassword = value("storePassword", "STEAMSHELF_KEYSTORE_PASSWORD") ?: error("Release signing: storePassword is missing"),
        keyAlias = value("keyAlias", "STEAMSHELF_KEY_ALIAS") ?: error("Release signing: keyAlias is missing"),
        keyPassword = value("keyPassword", "STEAMSHELF_KEY_PASSWORD") ?: error("Release signing: keyPassword is missing"),
    )
}

android {
    namespace = "dev.steamshelf"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.steamshelf"
        minSdk = 26
        targetSdk = 36
        // Override with -PversionCode=… -PversionName=… (or ORG_GRADLE_PROJECT_versionCode in CI).
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0.0"
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = key.storeFile
                storePassword = key.storePassword
                keyAlias = key.keyAlias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // Without a release key: sign with this machine's debug key (~/.android/debug.keystore), so the APK
            // installs and updates over the debug build on your own phone.
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // OkHttp probes android.util.Log on the JVM; let unit tests get default values instead of crashing.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
