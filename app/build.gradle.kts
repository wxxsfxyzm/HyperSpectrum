// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

import java.time.Instant
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitCount = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim() }

val gitVersionCode = gitCommitCount.map { count ->
    count.toInt()
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingProperty(name: String): String? =
    keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val localKeystoreFile = signingProperty("storeFile")
val localKeystorePassword = signingProperty("storePassword")
val localKeyAlias = signingProperty("keyAlias")
val localKeyPassword = signingProperty("keyPassword")
val hasLocalSigningConfig = listOf(
    localKeystoreFile,
    localKeystorePassword,
    localKeyAlias,
    localKeyPassword,
).all { it != null }

val envKeystoreFile = System.getenv("SIGNING_KEY")
val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val envAlias = System.getenv("ALIAS")
val envKeyPassword = System.getenv("KEY_PASSWORD")
val hasEnvSigningConfig = listOf(
    envKeystoreFile,
    envKeystorePassword,
    envAlias,
    envKeyPassword,
).all { !it.isNullOrBlank() }

val probeBuild = providers.gradleProperty("probeBuild")
    .orElse(Instant.now().toString()).get()

android {
    namespace = "io.github.wxxsfxyzm.hyperspectrum"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "io.github.wxxsfxyzm.hyperspectrum"
        minSdk = 36
        targetSdk = 37
        versionCode = gitVersionCode.get()
        versionName = "0.2"
        buildConfigField("String", "PROBE_BUILD", "\"${probeBuild.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "DIAGNOSTIC_MODE", providers.gradleProperty("diagnosticProbe").orElse("false").get())
        buildConfigField("boolean", "ROUTE_COMPENSATION", providers.gradleProperty("routeCompensation").orElse("true").get())
        buildConfigField("boolean", "NATIVE_CAPTURE", providers.gradleProperty("nativeCapture").orElse("false").get())
        val nativeTapBuild = providers.gradleProperty("nativeTapBuild").orElse("").get()
        require(nativeTapBuild.isEmpty() || nativeTapBuild.matches(Regex("[0-9a-f]{64}"))) { "Invalid nativeTapBuild" }
        require(!providers.gradleProperty("nativeCapture").orElse("false").get().toBoolean() || nativeTapBuild.isNotEmpty()) {
            "nativeCapture requires the source hash from out/native-tap/manifest.json"
        }
        buildConfigField("String", "NATIVE_TAP_BUILD", "\"$nativeTapBuild\"")
    }
    signingConfigs {
        create("release") {
            if (hasLocalSigningConfig) {
                storeFile = rootProject.file(localKeystoreFile!!)
                storePassword = localKeystorePassword
                keyAlias = localKeyAlias
                keyPassword = localKeyPassword
            } else if (hasEnvSigningConfig) {
                storeFile = file(envKeystoreFile!!)
                storePassword = envKeystorePassword
                keyAlias = envAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasLocalSigningConfig || hasEnvSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")

            if (hasLocalSigningConfig || hasEnvSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.merges += "META-INF/xposed/*"
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.preference)
    implementation(libs.compose.material.icons.extended)
    testImplementation(libs.junit)
}
