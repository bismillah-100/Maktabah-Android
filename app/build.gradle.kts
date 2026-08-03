@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.maktabah"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.maktabah.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        manifestPlaceholders["appName"] = "Maktabah"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            val inputStream = localPropertiesFile.inputStream()
            properties.load(inputStream)
            inputStream.close()
        }

        signingConfigs {
            create("release") {
                storeFile = file(properties.getProperty("signing.storeFile") ?: "release-key.jks")
                storePassword = properties.getProperty("signing.storePassword") ?: ""
                keyAlias = properties.getProperty("signing.keyAlias") ?: ""
                keyPassword = properties.getProperty("signing.keyPassword") ?: ""
            }
        }

        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "Maktabah Debug"

            val cloudKitToken = System.getenv("CLOUDKIT_DEBUG_TOKEN")
                ?: properties.getProperty("cloudkit.debug.token")
                ?: ""
            buildConfigField("String", "CLOUDKIT_TOKEN", "\"$cloudKitToken\"")

            val cfWorkerUrl = properties.getProperty("cloudflare.worker.url") ?: "https://maktabah-donations.dev-kbcdrn.workers.dev"
            buildConfigField("String", "CLOUDFLARE_WORKER_URL", "\"$cfWorkerUrl\"")

            val firebaseRtdbUrl = properties.getProperty("firebase.rtdb.url") ?: "https://maktabah-android-default-rtdb.firebaseio.com"
            buildConfigField("String", "FIREBASE_RTDB_URL", "\"$firebaseRtdbUrl\"")

            val cloudKitContainerId = properties.getProperty("cloudkit.container.id") ?: "iCloud.Maktabah"
            buildConfigField("String", "CLOUDKIT_CONTAINER_ID", "\"$cloudKitContainerId\"")

            val githubKitabIndexUrl = properties.getProperty("github.kitab.index.url") ?: "https://raw.githubusercontent.com/bismillah-100/Kitab/main/index.json"
            buildConfigField("String", "GITHUB_KITAB_INDEX_URL", "\"$githubKitabIndexUrl\"")

            val githubKitabVersionUrl = properties.getProperty("github.kitab.version.url") ?: "https://raw.githubusercontent.com/bismillah-100/Kitab/main/version.txt"
            buildConfigField("String", "GITHUB_KITAB_VERSION_URL", "\"$githubKitabVersionUrl\"")

            val githubReleaseBaseUrl = properties.getProperty("github.release.base.url") ?: "https://github.com/bismillah-100/Kitab/releases/download"
            buildConfigField("String", "GITHUB_RELEASE_BASE_URL", "\"$githubReleaseBaseUrl\"")

            val githubAppRepo = properties.getProperty("github.app.repo") ?: "bismillah-100/Maktabah-Android"
            buildConfigField("String", "GITHUB_APP_REPO", "\"$githubAppRepo\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val cloudKitToken = System.getenv("CLOUDKIT_RELEASE_TOKEN")
                ?: properties.getProperty("cloudkit.release.token")
                ?: ""
            buildConfigField("String", "CLOUDKIT_TOKEN", "\"$cloudKitToken\"")

            val cfWorkerUrl = properties.getProperty("cloudflare.worker.url") ?: "TODO_CONFIG"
            buildConfigField("String", "CLOUDFLARE_WORKER_URL", "\"$cfWorkerUrl\"")

            val firebaseRtdbUrl = properties.getProperty("firebase.rtdb.url") ?: "TODO_CONFIG"
            buildConfigField("String", "FIREBASE_RTDB_URL", "\"$firebaseRtdbUrl\"")

            val cloudKitContainerId = properties.getProperty("cloudkit.container.id") ?: "TODO_CONFIG"
            buildConfigField("String", "CLOUDKIT_CONTAINER_ID", "\"$cloudKitContainerId\"")

            val githubKitabIndexUrl = properties.getProperty("github.kitab.index.url") ?: "https://raw.githubusercontent.com/bismillah-100/Kitab/main/index.json"
            buildConfigField("String", "GITHUB_KITAB_INDEX_URL", "\"$githubKitabIndexUrl\"")

            val githubKitabVersionUrl = properties.getProperty("github.kitab.version.url") ?: "https://raw.githubusercontent.com/bismillah-100/Kitab/main/version.txt"
            buildConfigField("String", "GITHUB_KITAB_VERSION_URL", "\"$githubKitabVersionUrl\"")

            val githubReleaseBaseUrl = properties.getProperty("github.release.base.url") ?: "https://github.com/bismillah-100/Kitab/releases/download"
            buildConfigField("String", "GITHUB_RELEASE_BASE_URL", "\"$githubReleaseBaseUrl\"")

            val githubAppRepo = properties.getProperty("github.app.repo") ?: "bismillah-100/Maktabah-Android"
            buildConfigField("String", "GITHUB_APP_REPO", "\"$githubAppRepo\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.browser)
    implementation(libs.multiplatform.markdown.renderer.m3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // File Downloads & Decompression
    implementation(libs.okhttp)
    implementation(libs.zstd.jni) {
        artifact {
            type = "aar"
        }
    }

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
