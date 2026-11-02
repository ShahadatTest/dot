import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dj.dot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dj.dot"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        // Dot's UI is English and it ships for phones; drop library translations and other CPUs.
        androidResources.localeFilters += "en"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf(
            "META-INF/**/LICENSE.txt", "META-INF/*.version", "META-INF/*.kotlin_module",
            "kotlin/**", "DebugProbesKt.bin", "kotlin-tooling-metadata.json",
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Nothing's Glyph SDK isn't on Maven. It is fetched from a fixed commit of the official repo and
// checked against a known hash, so nothing that changes upstream can slip into a build.
val glyphSdkCommit = "8ee807a9312a640b0d43051450924e3446bc1d78"
val glyphSdkSha256 = "329393019db5f0f987c6245855d13fa273d06756c68829ca0f6ae686ba336da1"
val glyphSdk = layout.projectDirectory.file("libs/glyph-matrix-sdk-2.0.aar")
val fetchGlyphSdk by tasks.registering {
    val target = glyphSdk.asFile
    inputs.property("sha256", glyphSdkSha256)
    outputs.file(target)
    doLast {
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (target.exists() && sha256(target) == glyphSdkSha256) return@doLast
        target.parentFile.mkdirs()
        val download = File(target.path + ".download")
        uri("https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit/raw/$glyphSdkCommit/sdk/glyph-matrix-sdk-2.0.aar")
            .toURL().openStream().use { input -> download.outputStream().use { input.copyTo(it) } }
        val actual = sha256(download)
        if (actual != glyphSdkSha256) {
            download.delete()
            throw GradleException("Glyph SDK checksum mismatch: expected $glyphSdkSha256, got $actual")
        }
        Files.move(download.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
tasks.named("preBuild") { dependsOn(fetchGlyphSdk) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files(glyphSdk))
}
