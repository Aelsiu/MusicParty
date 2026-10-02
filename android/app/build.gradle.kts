import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedVersionFile = rootProject.file("../VERSION")
require(sharedVersionFile.isFile) { "The root VERSION file is required to build the Android client." }
val sharedVersion = sharedVersionFile.readText().trim()
val versionParts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(sharedVersion)
    ?: error("The root VERSION file must contain a major.minor.patch project version.")
val calculatedVersionCode = versionParts.groupValues[1].toLong() * 10000 +
    versionParts.groupValues[2].toLong() * 100 + versionParts.groupValues[3].toLong()
val requestedVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: run {
    require(versionParts.groupValues[2].toLong() < 100 && versionParts.groupValues[3].toLong() < 100) {
        "Versions with minor or patch >= 100 require an explicit -PversionCode to avoid code collisions."
    }
    val defaultVersionCode = if (calculatedVersionCode == 0L) 1L else calculatedVersionCode
    require(defaultVersionCode in 1L..2100000000L) {
        "Calculated Android versionCode is outside 1..2100000000; supply -PversionCode explicitly."
    }
    defaultVersionCode.toInt()
}
require(requestedVersionCode in 1..2100000000) { "Android versionCode must be in 1..2100000000." }

android {
    namespace = "com.thornex.musicparty"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.thornex.musicparty"
        minSdk = 24
        targetSdk = 35
        versionCode = requestedVersionCode
        versionName = (project.findProperty("versionName") as String?) ?: sharedVersion
    }

    buildTypes {
        release {
            // 默认生成未签名 Release，由 build-app.ps1 或发布工作流用已有密钥签名。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")   // MediaSessionCompat（JS 桥时用）
}
